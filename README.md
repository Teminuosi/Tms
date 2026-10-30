# TMS 面板

> 一个面板同时搞定**翻墙协议**、**转发中转**、以及**每用户限速 / 流量 / 到期**。

> 💡 **推荐使用 TMS 客户端管理面板**:常用功能都在客户端里,更省事。[下载 TMS 客户端](https://3yuedaohang.com/proxy-download)

<p>
  <a href="https://3yuedaohang.com">站长博客</a> ·
  <a href="https://www.youtube.com/@zhanzhang3yue">YouTube</a> ·
  <a href="https://3yuedaohang.com/cn2/banwagong">机器推荐</a>
</p>

---

## 能做什么

| | 说明 |
|---|---|
| **协议管理** | 一键搭全套协议(VLESS-Reality / Trojan / VMess / Hysteria2 / TUIC / AnyTLS),出订阅给用户 |
| **中转** | 前置机搭协议 + 落地出口(住宅 socks / 机场节点 / 自己的节点),给用户干净出口 IP,自带在线测落地 |
| **端口转发 / 隧道转发** | 通用端口搬运、两级加密中转 |
| **限速 / 流量 / 到期** | 每个用户独立限速(TCP + UDP 都限)、流量配额、到期时间 |
| **订阅按线路** | 一个用户可以有多条订阅,直连 / 各中转各自独立,互不影响 |
| **中央管理** | 一台面板管所有转发机,节点一条命令上线 |

订阅同时支持两种格式:**通用**(v2rayN / 小火箭 / v2rayNG)和 **Clash / Mihomo**(Clash Verge、ClashMeta)。

<sub>本项目基于 [go-gost/gost](https://github.com/go-gost/gost) 和 [go-gost/x](https://github.com/go-gost/x) 两个开源库。</sub>


## 部署

要装两样东西:

| | 装在哪 | 装什么 | 需要 Docker |
|---|---|---|---|
| **面板端** | 一台机器即可 | 中央管理面板 | 是(脚本自动装) |
| **节点端** | 每台转发机 | gost 裸二进制 | 否 |

<br>

### 第一步 · 装面板端

找一台机器执行:

```bash
curl -L https://raw.githubusercontent.com/Teminuosi/Tms/main/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh
```

装完会打印访问地址。默认账号 **admin_user** / **admin_user**。

> [!WARNING]
> 首次登录后**立刻改密码**。面板是公网可访问的,默认口令等于没有口令。

<br>

### 第二步 · 装节点端

**不用手敲命令,在面板里生成:**

```
登录面板 → 左侧「转发机监控」→「新增」填这台机器的 IP → 保存
        → 点该机器的「安装」→ 复制弹出的命令 → 到那台机器上执行
```

弹出的命令已经带好了「面板地址 + 该机器专属密钥」,全自动、无需手输。

> [!NOTE]
> 密钥是**新增转发机时才生成的、只有面板知道**,所以节点端命令必须从面板里拿,
> 没法自己拼出来。

<br>

> [!IMPORTANT]
> **国内机器(阿里云 / 腾讯云 / 华为云等)看这里**
>
> 直连 GitHub 会超时,表现是卡在下载那一步不动,或者装完面板报
> 「sing-box 未运行」。用镜像加速,并加 `-c` 强制内部下载也走国内镜像:
>
> ```bash
> curl -L https://ghfast.top/https://raw.githubusercontent.com/Teminuosi/Tms/main/install.sh -o install.sh && chmod +x install.sh && ./install.sh -c -a 面板地址:端口 -s 你的密钥
> ```
>
> `面板地址` 和 `密钥` 就从上面「点安装」弹出的那条命令里抄。
>
> **镜像失效了怎么办**:把命令里的 `ghfast.top` 整体换成下面任一个,
> 并在命令最前面加 `GH_MIRROR=https://新镜像/`(让内部下载 gost 也走它):
> `gh-proxy.com` · `ghproxy.net` · `mirror.ghproxy.com`

<details>
<summary>手动装节点端(不推荐)</summary>

<br>

也可以直接在机器上跑裸命令,它会**交互式询问**面板地址和密钥
(密钥同样得先在面板「转发机监控」新增该转发机才有):

```bash
curl -L https://raw.githubusercontent.com/Teminuosi/Tms/main/install.sh -o install.sh && chmod +x install.sh && ./install.sh
```

</details>

<br>

### 装完之后 · tms 命令

面板机上会生成一个 `tms` 命令(类似 x-ui),直接输入打开管理菜单:

```bash
tms
```

也可以带参数直接用:

| 命令 | 作用 |
|---|---|
| `tms` | 打开管理菜单 |
| `tms update` | 更新面板到最新版 |
| `tms status` | 查看运行状态 |
| `tms info` | 查看访问地址 / 账号 |
| `tms domain 域名` | 给面板配域名 + HTTPS |
| `tms domain` | 查看当前域名状态 |
| `tms domain off` | 关闭域名,回到 IP:端口 |
| `tms export` | 导出数据库备份 |
| `tms purge` | 彻底清理(卸载并清空容器 / 镜像 / 卷 / 命令) |

---


## 域名配置

面板和转发机的域名是**两件独立的事**,解决的问题也不一样。

### 一、给面板套域名(HTTPS)

默认只能 `http://IP:6366` 访问,浏览器会标"不安全"。配了域名之后走 HTTPS,**订阅链接也会跟着变成域名**。

```bash
tms domain panel.example.com
```

背后用 Caddy 自动申请和续期 Let's Encrypt 证书,会依次检查:域名解析是否指向本机 → 80/443 有没有被占 → 写配置 → 起 Caddy → 等证书签发(最多 60 秒)。

**前置条件:**
- 域名已经解析(A 记录)到面板服务器
- 80 和 443 端口空闲(装了宝塔的话先停掉它的 nginx)
- 云服务器安全组放行 80、443

> 💡 原来的 `IP:6366` 会保留作为备用入口,域名出问题时还能进得去。
>
> ⚠️ 配了域名后,**已经发出去的旧订阅(IP 版)不会自动更新**,要让车友重新拉一次。所以建议装好就配,人越少越好办。

### 二、给转发机配域名(不让车友看到你的 IP)

车友拿到订阅后,能在客户端里看到每个节点的地址。默认显示的是**转发机的真实 IP**。

在「转发机」→ 编辑 → **连接域名(可选)** 里填一个域名,车友看到的就变成域名了:

```
美国机   us.example.com   →  解析到 203.0.113.10
香港机   hk.example.com   →  解析到 203.0.113.20
国内机   cn.example.com   →  解析到 203.0.113.30
```

**一台转发机一个子域名**(同一个域名下开子域名即可,不用买多个),填之前先去 DNS 加好 A 记录。留空则维持原样显示 IP。

好处除了不暴露 IP,还有:**机器 IP 被墙时改条 DNS 解析就活了,不用通知车友重新拉订阅。**

> ⚠️ **这只是"不直接显示",不是真正的隐藏。** 对方 `ping` 一下域名照样拿到 IP。
> 要做到查都查不到,只有走 CDN(Cloudflare 橙云),而目前一键搭建的六个协议
> (VLESS-Reality / Trojan-Reality / VMess / Hysteria2 / TUIC / AnyTLS)都过不了 CDN
> —— Reality 要跟真实服务端直接握手、Hysteria2 和 TUIC 走 UDP,CF 都不转发。
> 挡普通车友足够,防封锁不行。

## 卸载

**先分清两种机器,卸载方式完全不同:**

| 角色 | 装了什么 | 有 `tms` 命令吗 |
|---|---|---|
| **面板机**(只有一台) | Docker:MySQL + 后端 + 前端 | ✅ 有 |
| **节点机 / 转发机**(每台) | gost + sing-box(systemd 服务) | ❌ 没有 |

> ⚠️ `tms purge` 和 `panel_install.sh purge` **只清面板**,对节点机上的 gost 一点作用都没有。反过来,清节点也不会影响面板。两边要分别执行。

### 一、卸载面板机

在面板安装目录下执行:

```bash
tms purge
```

删除所有容器、镜像、数据卷、网络、配置文件和 `tms` 管理命令。也可以直接输入 `tms` 打开菜单选「彻底清理」。

如果 `tms` 命令不在了(比如当初就没装成功),用一次性脚本:

```bash
curl -L https://raw.githubusercontent.com/Teminuosi/Tms/main/panel_install.sh -o /tmp/tms.sh && bash /tmp/tms.sh purge
```

> 💡 最好 **cd 到当初安装面板的目录**再执行。不在那个目录时,脚本会从 `/usr/local/bin/tms` 里读回安装目录并自动切过去;
> 那个文件也没了的话,容器和镜像照样按名字清掉,只是安装目录里的 `docker-compose.yml` / `.env` 要你自己删。
> 脚本会检查当前目录的 `docker-compose.yml` 是不是 TMS 的,不是就跳过 compose 清理,避免误删你其它项目的容器和 `.env`。

#### 源码编译版(合体部署)怎么卸

用 `git clone` + `docker-compose-hybrid.yml` 本地构建起来的面板,管理命令同样是 `tms`:

```bash
tms purge
```

或者输入 `tms` 打开菜单选「7) 彻底卸载」。它会删掉容器、**本地构建的镜像**、数据卷(含数据库数据)、
网络和 `tms` 命令;**源码目录会保留**,确认不要了自己 `rm -rf` 即可。

### 二、卸载节点机(转发机)

> 🚨 **面板机同时也当转发机用的话,千万别在它上面跑这段。**
> 很多人把面板和第一台转发机装在同一台机器上,这段命令会把**本机的节点服务一起停掉并 disable**
> —— 所有协议瞬间全部失效,而面板里节点还显示「在线」(gost 是另一个服务,它还活着),
> 极难联想到是刚才那条命令干的。只想卸载**其它**转发机时,请 SSH 到那台机器上执行。
>
> 万一误跑了,恢复:`systemctl enable --now sing-box`
> ——必须带 `enable`,因为它被 `disable` 过,只 `start` 的话重启机器又会消失。

**每台转发机都要单独执行**,直接复制这段:

```bash
systemctl stop gost sing-box 2>/dev/null
systemctl disable gost sing-box 2>/dev/null
rm -rf /etc/systemd/system/sing-box.service.d /etc/gost
find /etc/systemd /run/systemd \( -name 'gost.service' -o -name 'sing-box.service' \) -delete 2>/dev/null
systemctl daemon-reload
systemctl reset-failed 2>/dev/null
echo "✅ 节点已卸载(gost + sing-box + 配置 + 证书)"
```

> ⚠️ **别只删 `/etc/gost`**。搭过协议的机器上还有 sing-box,它的服务文件在 `/etc/systemd/system/`,只删安装目录的话二进制没了、服务还注册着,systemd 会一直重启失败刷满日志。

> 💡 上面用 `find ... -delete` 而不是直接 `rm` 服务文件,是为了连 `multi-user.target.wants/` 里的**软链接**一起清掉。正常情况 `systemctl disable` 会删它们,但服务已经异常时可能残留,结果 `systemctl list-units --all` 里一直挂着一条 `not-found`,看着像没卸干净。

也可以重新下节点脚本走菜单(选 `3` 卸载):

```bash
curl -L https://raw.githubusercontent.com/Teminuosi/Tms/main/install.sh -o /tmp/n.sh && chmod +x /tmp/n.sh && /tmp/n.sh
```

> 💡 **国内机器**(阿里云等)大概率下不动 GitHub,直接用上面那段命令。

### 三、验证是否清干净

**面板机:**
```bash
docker ps -a | grep -E 'gost-mysql|springboot-backend|vite-frontend'
command -v tms
```

**节点机:**
```bash
systemctl list-units --all | grep -E 'gost|sing-box'
ls /etc/gost
```

都没有输出就说明干净了。

### 四、顺手清理防火墙(可选)

卸载不会动防火墙规则,之前给转发开的端口还留着。不打算再装的话:

```bash
ufw status numbered      # 看编号
ufw delete <编号>        # 逐条删
```

云服务器还要去控制台把**安全组**里对应的入方向规则删掉(阿里云、腾讯云、evoxt 等)。端口后面没服务在听,留着也不影响安全,看个人习惯。


## 免责声明

本项目仅供个人学习与研究使用，基于开源项目进行二次开发。  

使用本项目所带来的任何风险均由使用者自行承担，包括但不限于：  

- 配置不当或使用错误导致的服务异常或不可用；  
- 使用本项目引发的网络攻击、封禁、滥用等行为；  
- 服务器因使用本项目被入侵、渗透、滥用导致的数据泄露、资源消耗或损失；  
- 因违反当地法律法规所产生的任何法律责任。  

本项目为开源的流量转发工具，仅限合法、合规用途。  
使用者必须确保其使用行为符合所在国家或地区的法律法规。  

**作者不对因使用本项目导致的任何法律责任、经济损失或其他后果承担责任。**  
**禁止将本项目用于任何违法或未经授权的行为，包括但不限于网络攻击、数据窃取、非法访问等。**  

如不同意上述条款，请立即停止使用本项目。  

作者对因使用本项目所造成的任何直接或间接损失概不负责，亦不提供任何形式的担保、承诺或技术支持。  


请务必在合法、合规、安全的前提下使用本项目。  

---

[![Star History Chart](https://api.star-history.com/svg?repos=Teminuosi/Tms&type=Date)](https://www.star-history.com/#Teminuosi/Tms&Date)

