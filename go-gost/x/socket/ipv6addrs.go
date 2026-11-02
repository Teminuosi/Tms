package socket

import (
	"net"
	"os"
)

// 不算「公网 IPv6」的段:ULA(可用 TMS_ALLOW_ULA_V6=1 放开,给测试用)、文档段、
// Cloudflare WARP(2a09:bac0::/29、2606:4700:110::/48 —— 装了 WARP 的机器出口是这个,外面连不进来)
var ipv6ExcludedNets = mustCIDRs("2001:db8::/32", "2a09:bac0::/29", "2606:4700:110::/48")
var ipv6ULANet = mustCIDRs("fc00::/7")[0]

func mustCIDRs(cidrs ...string) []*net.IPNet {
	out := make([]*net.IPNet, 0, len(cidrs))
	for _, c := range cidrs {
		_, n, err := net.ParseCIDR(c)
		if err != nil {
			panic(err)
		}
		out = append(out, n)
	}
	return out
}

func isUsableGlobalIPv6(ip net.IP) bool {
	if ip == nil || ip.To4() != nil || ip.To16() == nil {
		return false
	}
	if ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() {
		return false
	}
	if ipv6ULANet.Contains(ip) && os.Getenv("TMS_ALLOW_ULA_V6") != "1" {
		return false
	}
	for _, n := range ipv6ExcludedNets {
		if n.Contains(ip) {
			return false
		}
	}
	return true
}

// collectGlobalIPv6 出口地址(udp6 Dial 只选路不发包)排第一,再补网卡上的地址,去重。
func collectGlobalIPv6() []string {
	var out []string
	seen := map[string]bool{}
	add := func(ip net.IP) {
		if !isUsableGlobalIPv6(ip) {
			return
		}
		s := ip.String()
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	if c, err := net.Dial("udp6", "[2001:4860:4860::8888]:53"); err == nil {
		if ua, ok := c.LocalAddr().(*net.UDPAddr); ok {
			add(ua.IP)
		}
		c.Close()
	}
	if addrs, err := net.InterfaceAddrs(); err == nil {
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				add(ipn.IP)
			}
		}
	}
	return out
}
