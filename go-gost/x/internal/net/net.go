package net

import (
	"context"
	"fmt"
	"io"
	"net"
	"runtime"
	"strings"
	"syscall"

	"github.com/vishvananda/netns"
)

type SetBuffer interface {
	SetReadBuffer(bytes int) error
	SetWriteBuffer(bytes int) error
}

type SyscallConn interface {
	SyscallConn() (syscall.RawConn, error)
}

type RemoteAddr interface {
	RemoteAddr() net.Addr
}

// tcpraw.TCPConn
type SetDSCP interface {
	SetDSCP(int) error
}

// NormalizeWildcardAddr 把 "[::]:p" 改写成 ":p"。
// 两者在支持 IPv6 的机器上等价(都是双栈监听);但禁用了 IPv6 的机器上
// "[::]:p" 会直接 bind 失败,而 ":p" 由 Go 自动回落到 IPv4,不至于整个程序起不来。
func NormalizeWildcardAddr(address string) string {
	if strings.HasPrefix(address, "[::]:") {
		return address[4:]
	}
	return address
}

func IsIPv4(address string) bool {
	return address != "" && address[0] != ':' && address[0] != '['
}

type ListenConfig struct {
	Netns string
	net.ListenConfig
}

func (lc *ListenConfig) Listen(ctx context.Context, network, address string) (net.Listener, error) {
	if lc.Netns != "" {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		originNs, err := netns.Get()
		if err != nil {
			return nil, fmt.Errorf("netns.Get(): %v", err)
		}
		defer netns.Set(originNs)

		var ns netns.NsHandle
		if strings.HasPrefix(lc.Netns, "/") {
			ns, err = netns.GetFromPath(lc.Netns)
		} else {
			ns, err = netns.GetFromName(lc.Netns)
		}
		if err != nil {
			return nil, fmt.Errorf("netns.Get(%s): %v", lc.Netns, err)
		}
		defer ns.Close()

		if err := netns.Set(ns); err != nil {
			return nil, fmt.Errorf("netns.Set(%s): %v", lc.Netns, err)
		}
	}

	return lc.ListenConfig.Listen(ctx, network, address)
}

func (lc *ListenConfig) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	if lc.Netns != "" {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		originNs, err := netns.Get()
		if err != nil {
			return nil, fmt.Errorf("netns.Get(): %v", err)
		}
		defer netns.Set(originNs)

		var ns netns.NsHandle
		if strings.HasPrefix(lc.Netns, "/") {
			ns, err = netns.GetFromPath(lc.Netns)
		} else {
			ns, err = netns.GetFromName(lc.Netns)
		}
		if err != nil {
			return nil, fmt.Errorf("netns.Get(%s): %v", lc.Netns, err)
		}
		defer ns.Close()

		if err := netns.Set(ns); err != nil {
			return nil, fmt.Errorf("netns.Set(%s): %v", lc.Netns, err)
		}
	}
	return lc.ListenConfig.ListenPacket(ctx, network, address)
}

type readWriteConn struct {
	net.Conn
	r io.Reader
	w io.Writer
}

func NewReadWriteConn(r io.Reader, w io.Writer, c net.Conn) net.Conn {
	return &readWriteConn{
		Conn: c,
		r:    r,
		w:    w,
	}
}

func (c *readWriteConn) Read(p []byte) (int, error) {
	return c.r.Read(p)
}

func (c *readWriteConn) Write(p []byte) (int, error) {
	return c.w.Write(p)
}
