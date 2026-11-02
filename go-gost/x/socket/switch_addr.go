package socket

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	xnet "github.com/go-gost/x/internal/net"
	parser "github.com/go-gost/x/config/parsing/service"
	"github.com/go-gost/x/registry"
	xservice "github.com/go-gost/x/service"
)

// SwitchServiceAddr:只换服务的监听地址(典型用途:0.0.0.0:p ↔ :p,让同一个端口同时收 IPv6)。
// 和 UpdateService 的区别:
//   - 逐条处理、逐条回滚,一条失败不影响其它条,也不中断整个命令;
//   - 新地址是双栈时先试绑 [::]:p(tcp6,有 udp 监听再试 udp6),绑不上直接拒绝,旧服务原封不动;
//   - 关旧监听后重建失败,立刻用原配置重建并注册回去;
//   - 已暂停的服务只改存储里的地址,不启动。
// 已建立的连接不受影响:Close 只关监听 socket,已接受的连接在各自 goroutine 里继续跑。

type switchAddrItem struct {
	Name string `json:"name"`
	Addr string `json:"addr"`
}

type switchAddrRequest struct {
	Items []switchAddrItem `json:"items"`
}

type switchAddrResult struct {
	Name  string `json:"name"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

// 测试里替换,用来模拟「预检过了、真正重建时失败」
var parseServiceFn = parser.ParseService

// 测试里替换
var disableIPv6Path = "/proc/sys/net/ipv6/conf/all/disable_ipv6"

// serviceRunningFromState 先看服务对象自己的状态:Serve 循环因监听被关而退出时置 closed(暂停就是 Close);
// ready / failed(accept 临时出错、仍在重试)说明还在监听。running 是刚建好还没 Serve 过,判断不了。
func serviceRunningFromState(svc service.Service) (running, known bool) {
	sp, ok := svc.(interface{ Status() *xservice.Status })
	if !ok || sp.Status() == nil {
		return false, false
	}
	switch sp.Status().State() {
	case xservice.StateClosed:
		return false, true
	case xservice.StateReady, xservice.StateFailed:
		return true, true
	}
	return false, false
}

// serviceListeningFn 状态判断不了时的兜底:自己试绑旧地址。绑得上 → 确定没在监听;
// 绑不上 → 可能是自己在监听,也可能是别的程序占着端口,算「不知道」。
var serviceListeningFn = func(c *config.ServiceConfig) (listening, known bool) {
	if hasUDPListener(c) {
		pc, err := net.ListenPacket("udp", c.Addr)
		if err != nil {
			return false, false
		}
		pc.Close()
		return false, true
	}
	ln, err := net.Listen("tcp", c.Addr)
	if err != nil {
		return false, false
	}
	ln.Close()
	return false, true
}

// pausedServiceIdle:标了暂停的服务是不是确实没在跑(没跑就只改配置)。
// 状态和试绑都判断不了时按配置里的暂停标记走,只改配置——不能去关它再重绑,
// 否则端口被别的程序占着时新旧地址都绑不上,服务会从注册表里消失。
func pausedServiceIdle(svc service.Service, c *config.ServiceConfig) bool {
	if running, known := serviceRunningFromState(svc); known {
		return !running
	}
	if listening, known := serviceListeningFn(c); known {
		return !listening
	}
	return true
}

func switchServiceAddrs(req switchAddrRequest) []switchAddrResult {
	results := make([]switchAddrResult, 0, len(req.Items))
	for _, it := range req.Items {
		name := strings.TrimSpace(it.Name)
		r := switchAddrResult{Name: name}
		if err := switchOneServiceAddr(name, strings.TrimSpace(it.Addr)); err != nil {
			r.Error = err.Error()
		} else {
			r.OK = true
		}
		results = append(results, r)
	}
	return results
}

func findServiceConfig(name string) *config.ServiceConfig {
	cfg := config.Global()
	if cfg == nil {
		return nil
	}
	for _, s := range cfg.Services {
		if s != nil && s.Name == name {
			return s
		}
	}
	return nil
}

func setServiceConfig(name string, newCfg *config.ServiceConfig) {
	config.OnUpdate(func(c *config.Config) error {
		for i := range c.Services {
			if c.Services[i] != nil && c.Services[i].Name == name {
				c.Services[i] = newCfg
				break
			}
		}
		return nil
	})
}

func isPausedConfig(c *config.ServiceConfig) bool {
	if c.Metadata == nil {
		return false
	}
	v, ok := c.Metadata["paused"]
	return ok && v == true
}

func hasUDPListener(c *config.ServiceConfig) bool {
	return c.Listener != nil && strings.Contains(strings.ToLower(c.Listener.Type), "udp")
}

// isDualStackAddr: ":p" 或 "[::]:p"
func isDualStackAddr(addr string) (port string, ok bool) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", false
	}
	return port, host == "" || host == "::"
}

// precheckIPv6Bind 试绑 [::]:p(Go 的 tcp6/udp6 会设 IPV6_V6ONLY,不和旧的 0.0.0.0:p 冲突),
// 绑上立刻关。用来确认本机 IPv6 可用、端口在 v6 上没被别人占。
// 只试这个服务自己的协议:TMS 的转发是同端口一对 _tcp / _udp 两个服务,
// udp 服务若也去试绑 tcp6,会撞上刚切换完的兄弟 tcp 服务(Docker 实测)。
func precheckIPv6Bind(port string, udp bool) error {
	if b, err := os.ReadFile(disableIPv6Path); err == nil && strings.TrimSpace(string(b)) == "1" {
		return errors.New("IPv6 预检失败:本机已禁用 IPv6(net.ipv6.conf.all.disable_ipv6=1)")
	}
	if !udp {
		ln, err := net.Listen("tcp6", "[::]:"+port)
		if err != nil {
			return fmt.Errorf("IPv6 预检失败(tcp6 [::]:%s): %v", port, err)
		}
		ln.Close()
	} else {
		pc, err := net.ListenPacket("udp6", "[::]:"+port)
		if err != nil {
			return fmt.Errorf("IPv6 预检失败(udp6 [::]:%s): %v", port, err)
		}
		pc.Close()
	}
	return nil
}

// startService 解析(含 bind)→ 注册 → 启动。bind 偶尔因旧 socket 还没完全释放失败,重试一次。
func startService(cfg *config.ServiceConfig) error {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 {
			time.Sleep(100 * time.Millisecond)
		}
		svc, err := parseServiceFn(cfg)
		if err != nil {
			lastErr = err
			continue
		}
		if err := registry.ServiceRegistry().Register(cfg.Name, svc); err != nil {
			svc.Close()
			return fmt.Errorf("注册失败: %v", err)
		}
		go svc.Serve()
		return nil
	}
	return lastErr
}

func switchOneServiceAddr(name, addr string) error {
	if name == "" {
		return errors.New("service name is required")
	}
	if addr == "" {
		return errors.New("addr is required")
	}
	if _, _, err := net.SplitHostPort(addr); err != nil {
		return fmt.Errorf("地址格式不对: %v", err)
	}

	old := registry.ServiceRegistry().Get(name)
	if old == nil {
		return fmt.Errorf("service %s not found", name)
	}
	origCfg := findServiceConfig(name)
	if origCfg == nil {
		return fmt.Errorf("service %s configuration not found", name)
	}

	newCfg := *origCfg // 浅拷贝:只改 Addr,其它字段(含名字)不动
	newCfg.Addr = addr

	// "[::]:p" 和 ":p" 是同一个监听,当作没变
	if xnet.NormalizeWildcardAddr(origCfg.Addr) == xnet.NormalizeWildcardAddr(addr) {
		return nil
	}

	// 暂停中且确实没在监听的服务:只改存储的地址,恢复时按新地址起。
	// 标了暂停却还在监听的(旧版节点程序启动时不认 paused 标记),按正常服务重绑。
	if isPausedConfig(origCfg) && pausedServiceIdle(old, origCfg) {
		setServiceConfig(name, &newCfg)
		return nil
	}

	if port, dual := isDualStackAddr(addr); dual {
		if err := precheckIPv6Bind(port, hasUDPListener(origCfg)); err != nil {
			return err
		}
	}

	// 关旧监听 → 按新地址重建
	old.Close()
	registry.ServiceRegistry().Unregister(name)

	if err := startService(&newCfg); err != nil {
		// 回滚:用原配置重建
		if rbErr := startService(origCfg); rbErr != nil {
			// 兜底:把原服务对象注册回去、配置不动,至少不会「service not found」,之后还能恢复/删除
			if regErr := registry.ServiceRegistry().Register(name, old); regErr != nil {
				return fmt.Errorf("切换到 %s 失败: %v;回滚到 %s 也失败: %v;重新登记原服务失败: %v", addr, err, origCfg.Addr, rbErr, regErr)
			}
			return fmt.Errorf("切换失败,配置没改、服务仍在节点上,但端口被别的程序占着、暂时没在监听;端口放开后在面板恢复这条转发即可: 新地址 %s: %v;原地址 %s: %v", addr, err, origCfg.Addr, rbErr)
		}
		return fmt.Errorf("切换到 %s 失败,已恢复原地址 %s: %v", addr, origCfg.Addr, err)
	}

	setServiceConfig(name, &newCfg)
	return nil
}

func (w *WebSocketReporter) handleSwitchServiceAddr(data interface{}) (map[string]interface{}, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return nil, fmt.Errorf("序列化数据失败: %v", err)
	}
	var req switchAddrRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return nil, fmt.Errorf("解析切换监听地址请求失败: %v", err)
	}
	if len(req.Items) == 0 {
		return nil, errors.New("items cannot be empty")
	}
	results := switchServiceAddrs(req)
	var failed []string
	for _, r := range results {
		if !r.OK {
			failed = append(failed, r.Name+": "+r.Error)
		}
	}
	out := map[string]interface{}{"results": results}
	if len(failed) > 0 {
		return out, fmt.Errorf("%d/%d 条切换失败: %s", len(failed), len(results), strings.Join(failed, "; "))
	}
	return out, nil
}
