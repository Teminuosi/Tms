package socket

// 合体面板(协议+限速)· 节点端 sing-box 管理模块
// 面板通过 WebSocket 下发 SetSingboxConfig 命令,节点负责:
//   1) 确保 sing-box 外部二进制已安装(没有则下载 v1.13.12,和 s-ui 同版);
//   2) 写入面板生成的完整 sing-box 配置 JSON;
//   3) 用 systemd 起/热重启 sing-box(自带崩溃重启,和 gost 同套路)。
// sing-box 只在 127.0.0.1 监听,公网口由 gost 转发占用并限速(见 flux合体面板设计.md)。

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	installDir        = "/etc/gost" // 与 install.sh 的 INSTALL_DIR 一致,systemd WorkingDirectory 也是这
	singboxVersion    = "1.13.12"   // 与 s-ui 内嵌的 sing-box 版本对齐,配置格式兼容
	singboxServiceUnit = "/etc/systemd/system/sing-box.service"
)

// 串行化配置写入 + 重载,避免并发下发时打架
var singboxMu sync.Mutex

// sing-box 的安装进度。存在的理由是「刚建完协议」那一小段:
// sing-box 不是装节点时就有的,而是面板下发配置后才现下 57MB 的二进制,
// 这中间它当然没在跑 —— 面板只看 running 的话会立刻弹血红的
// 「协议全部不可用」,新用户第一眼就以为装坏了,而实际上只是还没装完。
// 报出「正在安装」,面板就能显示成灰色的等待态,只有真失败才转红。
var (
	singboxStateMu    sync.Mutex
	singboxInstalling bool
	// 上一次安装失败的原因。装成功或重新开始安装时清空 ——
	// 留着旧错误会让已经修好的机器一直显示红字。
	singboxInstallErr string
)

func setSingboxInstalling(v bool) {
	singboxStateMu.Lock()
	singboxInstalling = v
	if v {
		singboxInstallErr = ""
	}
	singboxStateMu.Unlock()
}

func setSingboxInstallErr(msg string) {
	singboxStateMu.Lock()
	singboxInstallErr = msg
	singboxStateMu.Unlock()
}

// SingboxProgress 给上报用:是否正在安装、以及上次安装失败的原因(没有则空)
func SingboxProgress() (installing bool, lastErr string) {
	singboxStateMu.Lock()
	defer singboxStateMu.Unlock()
	return singboxInstalling, singboxInstallErr
}

// SetSingboxConfig 命令下发的数据:面板给完整 sing-box 配置 + 可选国内下载镜像
type singboxConfigRequest struct {
	Config json.RawMessage `json:"config"`           // 完整 sing-box 配置(log/inbounds/outbounds…)
	Mirror string          `json:"mirror,omitempty"` // 国内 GitHub 镜像前缀(如 https://ghfast.top/),可空
}

func singboxBinPath() string    { return filepath.Join(installDir, "sing-box") }
func singboxConfigPath() string { return filepath.Join(installDir, "sing-box.json") }

// ---- 命令处理(在 routeCommand 里被调用)----

func (w *WebSocketReporter) handleSetSingboxConfig(data interface{}) error {
	singboxMu.Lock()
	defer singboxMu.Unlock()

	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化 sing-box 配置失败: %v", err)
	}
	var req singboxConfigRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析 sing-box 配置失败: %v", err)
	}
	if len(req.Config) == 0 {
		return fmt.Errorf("sing-box 配置为空")
	}

	if err := ensureSingboxInstalled(req.Mirror); err != nil {
		return err
	}
	if err := ensureSelfCert(); err != nil { // Hysteria2/TUIC/AnyTLS 用的自签证书,没有则生成
		return err
	}
	if err := writeSingboxConfig(req.Config); err != nil {
		return err
	}
	if err := reloadSingbox(); err != nil {
		return err
	}
	return nil
}

func selfCertPath() string { return filepath.Join(installDir, "certs", "self.crt") }
func selfKeyPath() string  { return filepath.Join(installDir, "certs", "self.key") }

// ensureSelfCert 没有自签证书就用 sing-box 生成一张(Hysteria2/TUIC/AnyTLS 共用,客户端用 insecure=1)。
// 面板侧配置固定引用 /etc/gost/certs/self.crt|self.key。
func ensureSelfCert() error {
	crt := selfCertPath()
	if fi, err := os.Stat(crt); err == nil && fi.Size() > 0 {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(crt), 0o755); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, singboxBinPath(), "generate", "tls-keypair", "www.bing.com", "--months", "120").CombinedOutput()
	if err != nil {
		return fmt.Errorf("生成自签证书失败: %v, %s", err, string(out))
	}
	key, cert := splitPem(string(out))
	if key == "" || cert == "" {
		return fmt.Errorf("解析自签证书失败: %s", string(out))
	}
	if err := os.WriteFile(selfKeyPath(), []byte(key), 0o600); err != nil {
		return err
	}
	if err := os.WriteFile(crt, []byte(cert), 0o644); err != nil {
		return err
	}
	return nil
}

// splitPem 从 `sing-box generate tls-keypair` 输出里拆出 私钥块 和 证书块
func splitPem(out string) (key, cert string) {
	const kb, ke = "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----"
	const cb, ce = "-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----"
	if i := strings.Index(out, kb); i >= 0 {
		if j := strings.Index(out, ke); j >= 0 {
			key = out[i:j+len(ke)] + "\n"
		}
	}
	if i := strings.Index(out, cb); i >= 0 {
		if j := strings.Index(out, ce); j >= 0 {
			cert = out[i:j+len(ce)] + "\n"
		}
	}
	return key, cert
}

func (w *WebSocketReporter) handleDeleteSingbox(data interface{}) error {
	singboxMu.Lock()
	defer singboxMu.Unlock()
	return stopSingbox()
}

// handleGenerateRealityKeypair 用 sing-box 生成 Reality 密钥对(比在后端手搓 x25519 可靠)
// 返回 {"privateKey": "...", "publicKey": "..."},面板存起来:私钥入服务端配置、公钥进客户端链接。
func (w *WebSocketReporter) handleGenerateRealityKeypair(data interface{}) (map[string]string, error) {
	fmt.Println("🔑 [reality] 收到,等 singboxMu 锁...")
	singboxMu.Lock()
	defer singboxMu.Unlock()
	fmt.Println("🔑 [reality] 已拿锁,检查 sing-box 是否就绪...")

	// 请求可带 mirror,用于首次下载 sing-box 二进制
	var req struct {
		Mirror string `json:"mirror,omitempty"`
	}
	if data != nil {
		if b, err := json.Marshal(data); err == nil {
			_ = json.Unmarshal(b, &req)
		}
	}
	if err := ensureSingboxForCommand(req.Mirror); err != nil {
		fmt.Printf("🔑 [reality] ensureSingboxInstalled 失败: %v\n", err)
		return nil, err
	}
	fmt.Println("🔑 [reality] sing-box 就绪, exec generate reality-keypair...")

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, singboxBinPath(), "generate", "reality-keypair").CombinedOutput()
	fmt.Printf("🔑 [reality] exec 返回 err=%v out=%q\n", err, string(out))
	if err != nil {
		return nil, fmt.Errorf("生成 reality 密钥失败: %v, %s", err, string(out))
	}
	priv, pub := parseRealityKeypair(string(out))
	if priv == "" || pub == "" {
		return nil, fmt.Errorf("解析 reality 密钥失败: %s", string(out))
	}
	fmt.Printf("🔑 [reality] 成功,priv=%d pub=%d 字符\n", len(priv), len(pub))
	return map[string]string{"privateKey": priv, "publicKey": pub}, nil
}

// parseRealityKeypair 解析 `sing-box generate reality-keypair` 的输出:
//   PrivateKey: xxxx
//   PublicKey: yyyy
func parseRealityKeypair(out string) (priv, pub string) {
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "PrivateKey:") {
			priv = strings.TrimSpace(strings.TrimPrefix(line, "PrivateKey:"))
		} else if strings.HasPrefix(line, "PublicKey:") {
			pub = strings.TrimSpace(strings.TrimPrefix(line, "PublicKey:"))
		}
	}
	return priv, pub
}

// ---- 安装 / 配置 / 服务管理 ----

// 安装本身单独串行。后台预装(websocket_reporter 启动时那个 goroutine)不拿 singboxMu,
// 原来它和"一键建协议"会同时跑进这里:一个正在写二进制、还没 chmod,另一个看到"文件已存在"
// 就直接去 exec,报 fork/exec /etc/gost/sing-box: permission denied(2026-10 用户实报)。
// 另起一把锁而不是让预装也拿 singboxMu:配置下发/生成密钥本来就要等装好才能干活,
// 拿同一把锁只会让"装完之前"的其它 sing-box 操作(删除、查询)也跟着卡住。
var singboxInstallMu sync.Mutex

// singboxUsable 二进制在、非空、能执行。
// 有文件但没执行权限:多半是老版本节点程序装到一半被打断(写完文件还没 chmod 进程就重启了,
// 或者所有源都在解压中途失败、留下半截文件)。先补权限再跑一次 version 确认是完整的,
// 跑不起来就删掉,让调用方重新下载 —— 不删的话这台机以后每次都 permission denied,永远好不了。
// 权限本来就对的(绝大多数已在用的节点)直接返回 true,行为和以前一样,不多跑任何命令。
func singboxUsable(bin string) bool {
	fi, err := os.Stat(bin)
	if err != nil || fi.Size() == 0 {
		return false
	}
	if fi.Mode().Perm()&0o111 != 0 {
		return true
	}
	fmt.Println("⚠️ sing-box 二进制没有执行权限(上次安装可能被打断),补上权限并检查是否完整")
	if err := os.Chmod(bin, 0o755); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if out, err := exec.CommandContext(ctx, bin, "version").CombinedOutput(); err == nil && strings.Contains(string(out), "sing-box") {
			return true
		}
	}
	fmt.Println("⚠️ 现有 sing-box 二进制不完整,删掉重新下载")
	os.Remove(bin)
	return false
}

// 面板命令在节点上是单线程顺序处理的,而面板等回复只等 10 秒。后台预装正在下载时,
// 建协议的命令如果也排队等它装完,会把这台节点的所有面板命令都堵住,最后还只报一句「等待响应超时」。
// 所以面板来的命令发现正在装,就立刻回一句人话,让用户稍后重试。
var errSingboxInstalling = fmt.Errorf("这台转发机正在下载安装 sing-box(刚装好的转发机一般要 1~2 分钟,国内机可能更久),装完再试一次")

// ensureSingboxForCommand 正在装就立刻返回 errSingboxInstalling,不排队等。
// 只给「生成 Reality 密钥」用(用户在界面上点的,失败了会看到、会重试)。
// 下发配置(SetSingboxConfig)不用它:那条照旧排队等装完 —— 面板超时后节点仍会把配置写进去,
// 改成立刻报错的话,节点重连时面板自动重推的配置就会被丢掉。
func ensureSingboxForCommand(mirror string) error {
	if installing, _ := SingboxProgress(); installing {
		return errSingboxInstalling
	}
	return ensureSingboxInstalled(mirror)
}

// ensureSingboxInstalled 二进制不存在(或不能用)则下载指定版本并解压
func ensureSingboxInstalled(mirror string) error {
	singboxInstallMu.Lock()
	defer singboxInstallMu.Unlock()

	bin := singboxBinPath()
	if singboxUsable(bin) {
		return nil
	}

	// 走到这说明二进制不在,真要下载了 —— 从这一刻起面板显示「安装中」
	setSingboxInstalling(true)
	defer setSingboxInstalling(false)

	// 下载和解压当成一件事:任一步失败就换下一个源,
	// 免得国内机留下半个包却只报"解压失败",让人以为是归档坏了
	tmp := filepath.Join(installDir, "sing-box.tar.gz")
	var lastErr error
	for _, url := range singboxDownloadURLs(mirror) {
		if err := downloadFile(url, tmp); err != nil {
			os.Remove(tmp)
			lastErr = fmt.Errorf("%s: %v", url, err)
			fmt.Printf("⚠️ 下载 sing-box 失败,换下一个源: %v\n", lastErr)
			continue
		}
		if err := installSingboxFromArchive(tmp, bin); err != nil {
			os.Remove(tmp)
			lastErr = fmt.Errorf("%s 解压失败: %v", url, err)
			fmt.Printf("⚠️ %v,换下一个源\n", lastErr)
			continue
		}
		os.Remove(tmp)
		fmt.Printf("✅ sing-box %s 安装完成(源: %s)\n", singboxVersion, url)
		return nil
	}
	msg := fmt.Sprintf("所有下载源都失败,最后一个 %v", lastErr)
	// 记下来报给面板:否则那台机只会显示「没装上」,而为什么装不上
	// 只能上机器翻 journalctl,这正是几个用户卡住的地方。
	setSingboxInstallErr(msg)
	return fmt.Errorf("%s", msg)
}

// GitHub 加速镜像,给国内机器兜底 —— 拼在完整 github 地址前面即可。
var singboxMirrors = []string{
	"https://ghfast.top/",
	"https://gh-proxy.com/",
	"https://ghproxy.net/",
}

// singboxDownloadURLs 按优先级列出候选下载地址:直连排第一(境外机秒过),
// 连不上或下到一半断流就顺着镜像往下换。
//
// 光靠调用方传 mirror 不够 —— 后台预装那条路径压根没传,国内机必然
// context deadline exceeded,所以兜底放在这里,谁调用都能自愈。
func singboxDownloadURLs(mirror string) []string {
	asset := fmt.Sprintf("sing-box-%s-linux-%s.tar.gz", singboxVersion, runtime.GOARCH)
	origin := fmt.Sprintf("https://github.com/SagerNet/sing-box/releases/download/v%s/%s", singboxVersion, asset)

	urls := make([]string, 0, len(singboxMirrors)+2)
	if mirror != "" {
		urls = append(urls, mirror+origin)
	}
	urls = append(urls, origin)
	for _, m := range singboxMirrors {
		if m != mirror {
			urls = append(urls, m+origin)
		}
	}
	return urls
}

func writeSingboxConfig(cfg json.RawMessage) error {
	if err := os.WriteFile(singboxConfigPath(), cfg, 0o600); err != nil {
		return fmt.Errorf("写 sing-box.json 失败: %v", err)
	}
	return nil
}

// reloadSingbox 确保 systemd 服务存在并(热)重启 sing-box
func reloadSingbox() error {
	if err := ensureSingboxService(); err != nil {
		return err
	}
	_ = exec.Command("systemctl", "enable", "sing-box").Run()
	if out, err := exec.Command("systemctl", "restart", "sing-box").CombinedOutput(); err != nil {
		return fmt.Errorf("重启 sing-box 失败: %v, %s", err, string(out))
	}
	return nil
}

func stopSingbox() error {
	_ = exec.Command("systemctl", "stop", "sing-box").Run()
	_ = exec.Command("systemctl", "disable", "sing-box").Run()
	return nil
}

// ensureSingboxService 写入/更新 systemd 单元(带 Restart=on-failure 自愈)
func ensureSingboxService() error {
	unit := fmt.Sprintf(`[Unit]
Description=sing-box (flux hybrid)
After=network.target
StartLimitIntervalSec=0

[Service]
WorkingDirectory=%s
ExecStart=%s run -c %s
Restart=on-failure
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
`, installDir, singboxBinPath(), singboxConfigPath())

	if existing, err := os.ReadFile(singboxServiceUnit); err == nil && string(existing) == unit {
		return nil // 已是最新,无需 daemon-reload
	}
	if err := os.WriteFile(singboxServiceUnit, []byte(unit), 0o644); err != nil {
		return fmt.Errorf("写 sing-box.service 失败: %v", err)
	}
	if out, err := exec.Command("systemctl", "daemon-reload").CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl daemon-reload 失败: %v, %s", err, string(out))
	}
	return nil
}

// ---- 下载 / 解压工具 ----

func downloadFile(url, dest string) error {
	// 总超时给足(二进制十几兆,慢线路也得下完),但连不上/服务端不吭声要快速失败,
	// 否则国内机会在 GitHub 那一个源上干等十分钟,轮不到后面的镜像
	client := &http.Client{
		Timeout: 10 * time.Minute,
		Transport: &http.Transport{
			DialContext:           (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, resp.Body); err != nil {
		return err
	}
	return nil
}

// installSingboxFromArchive 解压到同目录的临时文件 → 加执行权限 → 原子改名成正式文件。
// 【为什么不直接解压到正式路径】那样文件会先以 0644 出现、写到一半时就"存在且非空",
// 任何同时在看它的人(别的协程、重启后的自己)都会把半成品当成装好了。
// 改名在同一目录内是原子的:正式路径上要么没有文件,要么是完整、可执行的那个。
func installSingboxFromArchive(tarGzPath, dest string) error {
	part := dest + ".part"
	os.Remove(part)
	if err := extractSingboxBinary(tarGzPath, part); err != nil {
		os.Remove(part)
		return err
	}
	if err := os.Chmod(part, 0o755); err != nil {
		os.Remove(part)
		return fmt.Errorf("给 sing-box 加执行权限失败: %v", err)
	}
	if err := os.Rename(part, dest); err != nil {
		os.Remove(part)
		return fmt.Errorf("替换 sing-box 二进制失败: %v", err)
	}
	return nil
}

// extractSingboxBinary 从 sing-box release 的 tar.gz 里抽出 sing-box 二进制
// 归档结构形如 sing-box-1.13.12-linux-amd64/sing-box
func extractSingboxBinary(tarGzPath, dest string) error {
	f, err := os.Open(tarGzPath)
	if err != nil {
		return err
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if hdr.Typeflag == tar.TypeReg && filepath.Base(hdr.Name) == "sing-box" {
			out, err := os.Create(dest)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			// 显式关闭并检查:写盘失败(比如磁盘满)有时到 Close 才报出来,
			// 原来 defer 掉了这个错误,半截文件会被当成装好了
			return out.Close()
		}
	}
	return fmt.Errorf("归档里没找到 sing-box 二进制")
}
