package socket

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/core/service"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/udp"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/udp"
	"github.com/go-gost/x/registry"
)

func TestMain(m *testing.M) {
	corelogger.SetDefault(xlogger.NewLogger())
	os.Exit(m.Run())
}

// echo 后端:收到什么回什么
func startEcho(t *testing.T) string {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return ln.Addr().String()
}

func freePort(t *testing.T) int {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return p
}

func hasIPv6(t *testing.T) bool {
	ln, err := net.Listen("tcp6", "[::1]:0")
	if err != nil {
		return false
	}
	ln.Close()
	return true
}

func setupService(t *testing.T, name, addr, backend string, ltype string, paused bool) *config.ServiceConfig {
	sc := &config.ServiceConfig{
		Name:      name,
		Addr:      addr,
		Handler:   &config.HandlerConfig{Type: ltype},
		Listener:  &config.ListenerConfig{Type: ltype},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "n1", Addr: backend}}},
	}
	if paused {
		sc.Metadata = map[string]any{"paused": true}
	}
	cp := *sc
	svc, err := parseServiceFn(&cp)
	if err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}
	if err := registry.ServiceRegistry().Register(name, svc); err != nil {
		t.Fatal(err)
	}
	if paused {
		svc.Close() // 和 pauseServices 一致:关掉但仍留在注册表里
	} else {
		go svc.Serve()
	}
	config.OnUpdate(func(c *config.Config) error {
		c.Services = append(c.Services, sc)
		return nil
	})
	t.Cleanup(func() {
		if s := registry.ServiceRegistry().Get(name); s != nil {
			registry.ServiceRegistry().Unregister(name)
			s.Close()
		}
		config.OnUpdate(func(c *config.Config) error {
			var keep []*config.ServiceConfig
			for _, s := range c.Services {
				if s.Name != name {
					keep = append(keep, s)
				}
			}
			c.Services = keep
			return nil
		})
	})
	return sc
}

func echoVia(network, addr string) error {
	c, err := net.DialTimeout(network, addr, 2*time.Second)
	if err != nil {
		return err
	}
	defer c.Close()
	return echoOn(c)
}

func echoOn(c net.Conn) error {
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte("ping")); err != nil {
		return err
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		return err
	}
	if string(buf) != "ping" {
		return fmt.Errorf("got %q", buf)
	}
	return nil
}

func storedAddr(name string) string {
	if c := findServiceConfig(name); c != nil {
		return c.Addr
	}
	return ""
}

func TestSwitchSuccessKeepsExistingConn(t *testing.T) {
	if !hasIPv6(t) {
		t.Skip("no IPv6 in this environment")
	}
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_ok_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)

	v4 := fmt.Sprintf("127.0.0.1:%d", p)
	pre, err := net.Dial("tcp4", v4)
	if err != nil {
		t.Fatal(err)
	}
	defer pre.Close()
	if err := echoOn(pre); err != nil {
		t.Fatal("pre conn:", err)
	}
	if err := echoVia("tcp6", fmt.Sprintf("[::1]:%d", p)); err == nil {
		t.Fatal("v6 should not connect before switch")
	}

	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_ok_tcp", Addr: fmt.Sprintf(":%d", p)}}})
	if len(res) != 1 || !res[0].OK {
		t.Fatalf("switch failed: %+v", res)
	}
	time.Sleep(100 * time.Millisecond)
	if err := echoOn(pre); err != nil {
		t.Fatal("pre-existing conn broken by switch:", err)
	}
	if err := echoVia("tcp6", fmt.Sprintf("[::1]:%d", p)); err != nil {
		t.Fatal("v6 after switch:", err)
	}
	if err := echoVia("tcp4", v4); err != nil {
		t.Fatal("v4 after switch:", err)
	}
	if got := storedAddr("s_ok_tcp"); got != fmt.Sprintf(":%d", p) {
		t.Fatalf("stored addr = %q", got)
	}
	if registry.ServiceRegistry().Get("s_ok_tcp") == nil {
		t.Fatal("not registered")
	}
}

func TestPrecheckFailureLeavesServiceIntact(t *testing.T) {
	if !hasIPv6(t) {
		t.Skip("no IPv6 in this environment")
	}
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_pre_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)
	before := registry.ServiceRegistry().Get("s_pre_tcp")

	// 别人占着 v6 的同端口
	blk, err := net.Listen("tcp6", fmt.Sprintf("[::]:%d", p))
	if err != nil {
		t.Fatal(err)
	}
	defer blk.Close()

	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_pre_tcp", Addr: fmt.Sprintf(":%d", p)}}})
	if res[0].OK || !strings.Contains(res[0].Error, "预检") {
		t.Fatalf("expected precheck error, got %+v", res)
	}
	if registry.ServiceRegistry().Get("s_pre_tcp") != before {
		t.Fatal("service object changed")
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("old listener not serving:", err)
	}
	if got := storedAddr("s_pre_tcp"); got != fmt.Sprintf("0.0.0.0:%d", p) {
		t.Fatalf("stored addr changed: %q", got)
	}
}

func TestRebindFailureRollsBack(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_rb_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)

	// 目标地址被占(非双栈地址,不走预检,真正重建时失败)
	occ, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occ.Close()

	// 另一个条目放在后面,确认前一条失败不会中断整个命令
	p2 := freePort(t)
	setupService(t, "s_rb2_tcp", fmt.Sprintf("0.0.0.0:%d", p2), be, "tcp", false)

	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{
		{Name: "s_rb_tcp", Addr: occ.Addr().String()},
		{Name: "nope", Addr: ":1"},
		{Name: "s_rb2_tcp", Addr: fmt.Sprintf("127.0.0.1:%d", p2)},
	}})
	if res[0].OK || !strings.Contains(res[0].Error, "已恢复原地址") {
		t.Fatalf("expected rollback error, got %+v", res[0])
	}
	if res[1].OK {
		t.Fatalf("missing service should fail: %+v", res[1])
	}
	if !res[2].OK {
		t.Fatalf("third item should succeed: %+v", res[2])
	}
	time.Sleep(100 * time.Millisecond)
	if registry.ServiceRegistry().Get("s_rb_tcp") == nil {
		t.Fatal("rolled-back service not registered")
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("old addr not listening after rollback:", err)
	}
	if got := storedAddr("s_rb_tcp"); got != fmt.Sprintf("0.0.0.0:%d", p) {
		t.Fatalf("stored addr changed: %q", got)
	}
}

func TestRebindFailureRollsBackInjected(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_inj_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)

	orig := parseServiceFn
	defer func() { parseServiceFn = orig }()
	parseServiceFn = func(c *config.ServiceConfig) (service.Service, error) {
		if c.Addr == "127.0.0.1:1" {
			return nil, errors.New("injected bind failure")
		}
		return orig(c)
	}
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_inj_tcp", Addr: "127.0.0.1:1"}}})
	if res[0].OK || !strings.Contains(res[0].Error, "injected") {
		t.Fatalf("got %+v", res[0])
	}
	time.Sleep(100 * time.Millisecond)
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("old addr not listening after rollback:", err)
	}
}

func TestPausedServiceOnlyUpdatesConfig(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_pause_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", true)
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_pause_tcp", Addr: fmt.Sprintf(":%d", p)}}})
	if !res[0].OK {
		t.Fatalf("got %+v", res[0])
	}
	if got := storedAddr("s_pause_tcp"); got != fmt.Sprintf(":%d", p) {
		t.Fatalf("stored addr = %q", got)
	}
	if c := findServiceConfig("s_pause_tcp"); !isPausedConfig(c) {
		t.Fatal("paused flag lost")
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err == nil {
		t.Fatal("paused service must not be started")
	}
}

// 标了暂停却在跑(旧节点程序重启后不认 paused):要按正常服务重绑,不能只改配置
func TestPausedButRunningServiceIsRebound(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_pr_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	config.OnUpdate(func(c *config.Config) error {
		for _, s := range c.Services {
			if s.Name == "s_pr_tcp" {
				s.Metadata = map[string]any{"paused": true}
			}
		}
		return nil
	})
	time.Sleep(100 * time.Millisecond)
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_pr_tcp", Addr: fmt.Sprintf("127.0.0.2:%d", p)}}})
	if !res[0].OK {
		t.Fatalf("got %+v", res[0])
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.2:%d", p)); err != nil {
		t.Fatal("new addr not listening:", err)
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err == nil {
		t.Fatal("old listener still alive: service was not rebound")
	}
}

// 真暂停(Serve 过再 Close,状态 closed)+ 端口被别的程序占着:关/开 IPv6 只改配置,服务不能从注册表消失;端口放开后能恢复
func TestPausedServicePortOccupiedByOther(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_po_tcp", fmt.Sprintf(":%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)
	if err := pauseServices(pauseServicesRequest{Services: []string{"s_po_tcp"}}); err != nil {
		t.Fatal("pause:", err)
	}
	time.Sleep(100 * time.Millisecond)
	occ, err := net.Listen("tcp4", fmt.Sprintf("0.0.0.0:%d", p))
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range []string{fmt.Sprintf("0.0.0.0:%d", p), fmt.Sprintf(":%d", p)} {
		res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_po_tcp", Addr: a}}})
		if !res[0].OK {
			t.Fatalf("switch to %s: %+v", a, res[0])
		}
		if registry.ServiceRegistry().Get("s_po_tcp") == nil {
			t.Fatalf("service vanished after switch to %s", a)
		}
		if got := storedAddr("s_po_tcp"); got != a {
			t.Fatalf("stored addr = %q, want %q", got, a)
		}
		if !isPausedConfig(findServiceConfig("s_po_tcp")) {
			t.Fatal("paused flag lost")
		}
	}
	occ.Close()
	if err := resumeServices(resumeServicesRequest{Services: []string{"s_po_tcp"}}); err != nil {
		t.Fatal("resume:", err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("not serving after resume:", err)
	}
}

// 兜底试绑:端口被别人占着算「不知道」,标了暂停就只改配置
func TestPausedFallbackPortBusyIsConfigOnly(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_pf_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", true) // 从没 Serve 过,状态判断不了
	before := registry.ServiceRegistry().Get("s_pf_tcp")
	occ, err := net.Listen("tcp4", fmt.Sprintf("0.0.0.0:%d", p))
	if err != nil {
		t.Fatal(err)
	}
	defer occ.Close()
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_pf_tcp", Addr: fmt.Sprintf("127.0.0.3:%d", p)}}})
	if !res[0].OK {
		t.Fatalf("got %+v", res[0])
	}
	if registry.ServiceRegistry().Get("s_pf_tcp") != before {
		t.Fatal("service object replaced/removed")
	}
	if storedAddr("s_pf_tcp") != fmt.Sprintf("127.0.0.3:%d", p) {
		t.Fatal("config not updated")
	}
}

// 在跑的服务:新地址和回滚都绑不上 → 原服务对象仍在注册表,配置不变,之后能恢复
func TestRebindAndRollbackBothFailKeepsRegistered(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_bf_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	time.Sleep(100 * time.Millisecond)
	before := registry.ServiceRegistry().Get("s_bf_tcp")
	orig := parseServiceFn
	defer func() { parseServiceFn = orig }()
	parseServiceFn = func(c *config.ServiceConfig) (service.Service, error) {
		return nil, errors.New("address already in use")
	}
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_bf_tcp", Addr: fmt.Sprintf("127.0.0.4:%d", p)}}})
	parseServiceFn = orig
	if res[0].OK || !strings.Contains(res[0].Error, "切换失败,配置没改、服务仍在节点上") {
		t.Fatalf("got %+v", res[0])
	}
	if registry.ServiceRegistry().Get("s_bf_tcp") != before {
		t.Fatal("original service not re-registered")
	}
	if storedAddr("s_bf_tcp") != fmt.Sprintf("0.0.0.0:%d", p) {
		t.Fatal("config changed")
	}
	config.OnUpdate(func(c *config.Config) error {
		for _, s := range c.Services {
			if s.Name == "s_bf_tcp" {
				s.Metadata = map[string]any{"paused": true}
			}
		}
		return nil
	})
	if err := resumeServices(resumeServicesRequest{Services: []string{"s_bf_tcp"}}); err != nil {
		t.Fatal("resume:", err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("not serving after resume:", err)
	}
}

func TestWildcardEquivalentIsNoop(t *testing.T) {
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_wc_tcp", fmt.Sprintf(":%d", p), be, "tcp", false)
	old := registry.ServiceRegistry().Get("s_wc_tcp")
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_wc_tcp", Addr: fmt.Sprintf("[::]:%d", p)}}})
	if !res[0].OK {
		t.Fatalf("got %+v", res[0])
	}
	if registry.ServiceRegistry().Get("s_wc_tcp") != old || storedAddr("s_wc_tcp") != fmt.Sprintf(":%d", p) {
		t.Fatal("equivalent wildcard addr must be a no-op")
	}
}

func TestPrecheckRefusesWhenIPv6DisabledBySysctl(t *testing.T) {
	f := t.TempDir() + "/disable_ipv6"
	os.WriteFile(f, []byte("1\n"), 0644)
	orig := disableIPv6Path
	disableIPv6Path = f
	defer func() { disableIPv6Path = orig }()
	be := startEcho(t)
	p := freePort(t)
	setupService(t, "s_sd_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{{Name: "s_sd_tcp", Addr: fmt.Sprintf(":%d", p)}}})
	if res[0].OK || !strings.Contains(res[0].Error, "disable_ipv6") {
		t.Fatalf("got %+v", res[0])
	}
	if storedAddr("s_sd_tcp") != fmt.Sprintf("0.0.0.0:%d", p) {
		t.Fatal("addr changed")
	}
	if err := echoVia("tcp4", fmt.Sprintf("127.0.0.1:%d", p)); err != nil {
		t.Fatal("old service broken:", err)
	}
}

func TestUDPServiceSwitch(t *testing.T) {
	if !hasIPv6(t) {
		t.Skip("no IPv6 in this environment")
	}
	p := freePort(t)
	be := startEcho(t)
	// TMS 的转发是同端口一对 _tcp / _udp
	setupService(t, "s_u_tcp", fmt.Sprintf("0.0.0.0:%d", p), be, "tcp", false)
	setupService(t, "s_u_udp", fmt.Sprintf("0.0.0.0:%d", p), "127.0.0.1:9", "udp", false)
	time.Sleep(100 * time.Millisecond)
	res := switchServiceAddrs(switchAddrRequest{Items: []switchAddrItem{
		{Name: "s_u_tcp", Addr: fmt.Sprintf(":%d", p)},
		{Name: "s_u_udp", Addr: fmt.Sprintf("[::]:%d", p)},
	}})
	if !res[0].OK || !res[1].OK {
		t.Fatalf("got %+v", res)
	}
	// 现在 v6 上 udp 端口应被本服务占着
	if pc, err := net.ListenPacket("udp6", fmt.Sprintf("[::]:%d", p)); err == nil {
		pc.Close()
		t.Fatal("udp6 port should be in use by switched service")
	}
}

func TestHandleSwitchServiceAddrEnvelope(t *testing.T) {
	w := &WebSocketReporter{}
	out, err := w.handleSwitchServiceAddr(map[string]interface{}{"items": []interface{}{map[string]interface{}{"name": "missing", "addr": ":1"}}})
	if err == nil {
		t.Fatal("expected overall error")
	}
	rs := out["results"].([]switchAddrResult)
	if len(rs) != 1 || rs[0].OK || rs[0].Name != "missing" {
		t.Fatalf("%+v", rs)
	}
	if _, err := w.handleSwitchServiceAddr(map[string]interface{}{"items": []interface{}{}}); err == nil {
		t.Fatal("empty items must error")
	}
}

func TestIPv6Filter(t *testing.T) {
	cases := map[string]bool{
		"2001:4860:4860::8888": true,
		"240e:1:2::3":          true,
		"fe80::1":              false,
		"::1":                  false,
		"fd00::5":              false,
		"2001:db8::1":          false,
		"2a09:bac1::1":         false,
		"2606:4700:110:8a::1":  false,
		"2606:4700:4700::1111": true,
		"1.2.3.4":              false,
	}
	os.Unsetenv("TMS_ALLOW_ULA_V6")
	for s, want := range cases {
		if got := isUsableGlobalIPv6(net.ParseIP(s)); got != want {
			t.Errorf("%s: got %v want %v", s, got, want)
		}
	}
	t.Setenv("TMS_ALLOW_ULA_V6", "1")
	if !isUsableGlobalIPv6(net.ParseIP("fd00::5")) {
		t.Error("ULA should be allowed with TMS_ALLOW_ULA_V6=1")
	}
}
