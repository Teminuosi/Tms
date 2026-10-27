package socket

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// 这组测试要在 Linux 上跑(执行权限、exec 脚本都是 Linux 语义)。
// 2026-10 用户实报 fork/exec /etc/gost/sing-box: permission denied,根因:
// 二进制先以 0644 写到正式路径、写完才 chmod,同时进来的人看到"存在且非空"就去 exec。

const fakeSingbox = "#!/bin/sh\necho sing-box version 1.13.12\n"

// 并发测试用的大号假二进制:真实 sing-box 有 57MB,写盘要好一阵;40 字节的脚本几微秒就写完,
// "文件已存在但还没执行权限"的窗口太短,测不出原 bug。补到几 MB,io.Copy 会分很多次写。
var fakeSingboxBig = fakeSingbox + strings.Repeat("# padding padding padding padding padding padding\n", 80000)

func makeArchive(t *testing.T, dir string, withBin bool) string {
	return makeArchiveBody(t, dir, withBin, fakeSingbox)
}

func makeArchiveBody(t *testing.T, dir string, withBin bool, bin string) string {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	name := "sing-box-1.13.12-linux-amd64/README"
	body := []byte("readme")
	if withBin {
		name = "sing-box-1.13.12-linux-amd64/sing-box"
		body = []byte(bin)
	}
	if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg}); err != nil {
		t.Fatal(err)
	}
	tw.Write(body)
	tw.Close()
	gz.Close()
	p := filepath.Join(dir, "sb.tar.gz")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func needLinux(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("执行权限语义只在 Linux 上有意义")
	}
}

func TestInstallFromArchiveIsExecutableAndAtomic(t *testing.T) {
	needLinux(t)
	dir := t.TempDir()
	bin := filepath.Join(dir, "sing-box")
	if err := installSingboxFromArchive(makeArchive(t, dir, true), bin); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(bin)
	if err != nil || fi.Mode().Perm()&0o111 == 0 {
		t.Fatalf("装完应可执行, mode=%v err=%v", fi.Mode(), err)
	}
	if _, err := os.Stat(bin + ".part"); !os.IsNotExist(err) {
		t.Fatal("临时文件 .part 应已改名掉")
	}
	if !singboxUsable(bin) {
		t.Fatal("装好的应判定为可用")
	}
}

func TestInstallFromBadArchiveLeavesNothing(t *testing.T) {
	needLinux(t)
	dir := t.TempDir()
	bin := filepath.Join(dir, "sing-box")
	if err := installSingboxFromArchive(makeArchive(t, dir, false), bin); err == nil {
		t.Fatal("归档里没有二进制应报错")
	}
	for _, p := range []string{bin, bin + ".part"} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Fatalf("失败后不应留下 %s", p)
		}
	}
}

// 老版本节点留下的"有文件、没执行权限、内容完整"的二进制:补权限后能用
func TestUsableRepairsMissingExecBit(t *testing.T) {
	needLinux(t)
	bin := filepath.Join(t.TempDir(), "sing-box")
	os.WriteFile(bin, []byte(fakeSingbox), 0o644)
	if !singboxUsable(bin) {
		t.Fatal("完整但缺执行权限的应补上权限后判定可用")
	}
	fi, _ := os.Stat(bin)
	if fi.Mode().Perm()&0o111 == 0 {
		t.Fatal("应已补上执行权限")
	}
}

// 老版本节点留下的半截文件:判不可用并删掉,让调用方重新下载(否则永远 permission denied)
func TestUsableRemovesBrokenBinary(t *testing.T) {
	needLinux(t)
	bin := filepath.Join(t.TempDir(), "sing-box")
	os.WriteFile(bin, []byte("\x7fELF-truncated-garbage"), 0o644)
	if singboxUsable(bin) {
		t.Fatal("半截文件不应判定可用")
	}
	if _, err := os.Stat(bin); !os.IsNotExist(err) {
		t.Fatal("半截文件应被删掉")
	}
}

func TestUsableMissingOrEmpty(t *testing.T) {
	dir := t.TempDir()
	if singboxUsable(filepath.Join(dir, "nope")) {
		t.Fatal("不存在的不可用")
	}
	empty := filepath.Join(dir, "empty")
	os.WriteFile(empty, nil, 0o755)
	if singboxUsable(empty) {
		t.Fatal("空文件不可用")
	}
}

// 复现原 bug 的时序:一边在装,一边反复"看见文件就 exec"。
// 改后正式路径上只会出现完整可执行的文件,所以任何时刻 Usable==true 时都必须能真跑起来。
func TestConcurrentObserverNeverSeesHalfInstalled(t *testing.T) {
	needLinux(t)
	dir := t.TempDir()
	bin := filepath.Join(dir, "sing-box")
	arc := makeArchiveBody(t, dir, true, fakeSingboxBig)
	stop := make(chan struct{})
	var wg sync.WaitGroup
	var bad error
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			if fi, err := os.Stat(bin); err == nil && fi.Size() > 0 && fi.Mode().Perm()&0o111 == 0 {
				bad = os.ErrPermission
				return
			}
		}
	}()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		os.Remove(bin)
		if err := installSingboxFromArchive(arc, bin); err != nil {
			t.Fatal(err)
		}
	}
	close(stop)
	wg.Wait()
	if bad != nil {
		t.Fatal("观察到了存在但不可执行的正式文件 —— 原 bug 还在")
	}
}
