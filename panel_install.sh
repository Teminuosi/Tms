#!/bin/bash
set -e

# 解决 macOS 下 tr 可能出现的非法字节序列问题
export LANG=en_US.UTF-8
export LC_ALL=C



# IPv6:默认关闭。自动改 Docker daemon.json + 内部 IPv6 网络在部分机器上会导致 mysql 启动失败(容器 unhealthy),
# 而面板根本不需要 Docker 内部 IPv6(容器间走 IPv4 即可;公网 IPv6 访问靠端口映射,与内部网络无关)。
# 确实需要 Docker 内部 IPv6 的,安装时加 TMS_IPV6=1 开启。
TMS_IPV6="${TMS_IPV6:-0}"

# 全局下载地址配置
# 【必须用 raw main,别用 releases/latest】:
# 节点的 gost 是按 gost-vN 单独发版的,一发版 GitHub 的 "latest release" 就会指向它,
# 而那个 release 里没有 compose 和 gost.sql —— 于是这里会下到 9 字节的 "Not Found",
# 把 docker-compose.yml 覆盖成垃圾、面板直接起不来(踩过)。
# raw main 永远是仓库当前内容,不受发版影响。
DOCKER_COMPOSEV4_URL="https://raw.githubusercontent.com/Teminuosi/Tms/main/docker-compose-v4.yml"
DOCKER_COMPOSEV6_URL="https://raw.githubusercontent.com/Teminuosi/Tms/main/docker-compose-v6.yml"
GOST_SQL_URL="https://raw.githubusercontent.com/Teminuosi/Tms/main/gost.sql"
# 管理脚本自身的 raw 地址(curl|bash 场景下 tms 命令的兜底下载源)
PANEL_INSTALL_RAW_URL="https://raw.githubusercontent.com/Teminuosi/Tms/main/panel_install.sh"

COUNTRY=$(curl -s --max-time 5 https://ipinfo.io/country || true)
if [ "$COUNTRY" = "CN" ]; then
    # 拼接 URL
    DOCKER_COMPOSEV4_URL="https://ghfast.top/${DOCKER_COMPOSEV4_URL}"
    DOCKER_COMPOSEV6_URL="https://ghfast.top/${DOCKER_COMPOSEV6_URL}"
    GOST_SQL_URL="https://ghfast.top/${GOST_SQL_URL}"
    PANEL_INSTALL_RAW_URL="https://ghfast.top/${PANEL_INSTALL_RAW_URL}"
fi



# 根据IPv6支持情况选择docker-compose URL
get_docker_compose_url() {
  # 默认 IPv4(最稳);仅在 TMS_IPV6=1 时用 IPv6 版 compose
  if [ "$TMS_IPV6" = "1" ]; then
    echo "$DOCKER_COMPOSEV6_URL"
  else
    echo "$DOCKER_COMPOSEV4_URL"
  fi
}

# 检查 docker-compose 或 docker compose 命令
check_docker() {
  # 全自动一键:没装 Docker 就用官方脚本自动装
  if ! command -v docker &> /dev/null; then
    echo "🔧 未检测到 Docker，正在自动安装..."
    curl -fsSL https://get.docker.com | sh
    if command -v systemctl &> /dev/null; then
      systemctl enable docker &> /dev/null || true
      systemctl start docker &> /dev/null || true
    fi
  fi

  if command -v docker-compose &> /dev/null; then
    DOCKER_CMD="docker-compose"
  elif command -v docker &> /dev/null; then
    if docker compose version &> /dev/null; then
      DOCKER_CMD="docker compose"
    else
      echo "错误：检测到 docker，但不支持 'docker compose' 命令。请更新 docker 版本。"
      exit 1
    fi
  else
    echo "错误：Docker 自动安装失败，请手动安装后重试。"
    exit 1
  fi
  echo "检测到 Docker 命令：$DOCKER_CMD"
}

# 检测系统是否支持 IPv6
check_ipv6_support() {
  echo "🔍 检测 IPv6 支持..."

  # 检查是否有 IPv6 地址（排除 link-local 地址）
  if ip -6 addr show | grep -v "scope link" | grep -q "inet6"; then
    echo "✅ 检测到系统支持 IPv6"
    return 0
  elif ifconfig 2>/dev/null | grep -v "fe80:" | grep -q "inet6"; then
    echo "✅ 检测到系统支持 IPv6"
    return 0
  else
    echo "⚠️ 未检测到 IPv6 支持"
    return 1
  fi
}



# 配置 Docker 启用 IPv6
configure_docker_ipv6() {
  echo "🔧 配置 Docker IPv6 支持..."

  # 检查操作系统类型
  OS_TYPE=$(uname -s)

  if [[ "$OS_TYPE" == "Darwin" ]]; then
    # macOS 上 Docker Desktop 已默认支持 IPv6
    echo "✅ macOS Docker Desktop 默认支持 IPv6"
    return 0
  fi

  # Docker daemon 配置文件路径
  DOCKER_CONFIG="/etc/docker/daemon.json"

  # 检查是否需要 sudo
  if [[ $EUID -ne 0 ]]; then
    SUDO_CMD="sudo"
  else
    SUDO_CMD=""
  fi

  # 检查 Docker 配置文件
  if [ -f "$DOCKER_CONFIG" ]; then
    # 检查是否已经配置了 IPv6
    if grep -q '"ipv6"' "$DOCKER_CONFIG"; then
      echo "✅ Docker 已配置 IPv6 支持"
    else
      echo "📝 更新 Docker 配置以启用 IPv6..."
      # 备份原配置
      $SUDO_CMD cp "$DOCKER_CONFIG" "${DOCKER_CONFIG}.backup"

      # 使用 jq 或 sed 添加 IPv6 配置
      if command -v jq &> /dev/null; then
        $SUDO_CMD jq '. + {"ipv6": true, "fixed-cidr-v6": "fd00::/80"}' "$DOCKER_CONFIG" > /tmp/daemon.json && $SUDO_CMD mv /tmp/daemon.json "$DOCKER_CONFIG"
      else
        # 如果没有 jq，使用 sed
        $SUDO_CMD sed -i 's/^{$/{\n  "ipv6": true,\n  "fixed-cidr-v6": "fd00::\/80",/' "$DOCKER_CONFIG"
      fi

      echo "🔄 重启 Docker 服务..."
      if command -v systemctl &> /dev/null; then
        $SUDO_CMD systemctl restart docker
      elif command -v service &> /dev/null; then
        $SUDO_CMD service docker restart
      else
        echo "⚠️ 请手动重启 Docker 服务"
      fi
      sleep 5
    fi
  else
    # 创建新的配置文件
    echo "📝 创建 Docker 配置文件..."
    $SUDO_CMD mkdir -p /etc/docker
    echo '{
  "ipv6": true,
  "fixed-cidr-v6": "fd00::/80"
}' | $SUDO_CMD tee "$DOCKER_CONFIG" > /dev/null

    echo "🔄 重启 Docker 服务..."
    if command -v systemctl &> /dev/null; then
      $SUDO_CMD systemctl restart docker
    elif command -v service &> /dev/null; then
      $SUDO_CMD service docker restart
    else
      echo "⚠️ 请手动重启 Docker 服务"
    fi
    sleep 5
  fi
}

# 显示菜单
show_menu() {
  echo "==============================================="
  echo "          TMS 面板管理菜单"
  echo "==============================================="
  echo "  1. 安装面板"
  echo "  2. 更新面板"
  echo "  3. 卸载面板"
  echo "  4. 彻底清理(卸载并清空容器/镜像/卷/命令)"
  echo "  5. 查看运行状态"
  echo "  6. 查看访问信息(地址/账号)"
  echo "  7. 导出数据库备份"
  echo "  8. 配置域名 + HTTPS"
  echo "  0. 退出"
  echo "==============================================="
}

generate_random() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c16
}

# 删除脚本自身(仅删一次性下载的安装脚本;常驻的 tms 管理脚本不自删)
delete_self() {
  SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  # 常驻管理命令 tms 走的就是 /usr/local/bin 下这两个,保留不删,否则 tms 会失效
  case "$SCRIPT_PATH" in
    /usr/local/bin/tms-panel.sh|/usr/local/bin/tms) return 0 ;;
  esac
  echo ""
  echo "🗑️ 操作已完成，正在清理临时安装脚本..."
  sleep 1
  rm -f "$SCRIPT_PATH" && echo "✅ 临时脚本已删除" || echo "❌ 删除临时脚本失败"
}

# 收尾信息框:装完最重要的就是「地址/账号/密码」,单独框出来别被上面的日志淹掉
print_access_box() {
  local ip="$1" fport="$2"
  echo ""
  echo "╔══════════════════════════════════════════════════════╗"
  echo "║              TMS 面板安装完成                        ║"
  echo "╚══════════════════════════════════════════════════════╝"
  echo ""
  echo "    访问地址 :  http://${ip}:${fport}"
  echo "    账    号 :  admin_user"
  echo "    密    码 :  admin_user"
  echo ""
  echo "    ⚠️  登录后请立即修改默认密码"
  echo ""
  echo "  ──────────────────────────────────────────────────────"
  echo "    管理面板 :  输入  tms  (更新/卸载/彻底清理/查看状态)"
  echo "    项目地址 :  https://github.com/Teminuosi/Tms"
  echo "  ──────────────────────────────────────────────────────"
  echo ""
}

# 安装常驻管理命令 tms(类似 x-ui:装完后随时输 tms 打开管理菜单)
install_tms_command() {
  echo "🔗 安装 tms 管理命令..."
  local self panel_dir
  panel_dir="$(pwd)"
  self="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  # 把当前脚本持久化为管理脚本;拿不到自身(curl|bash)则现下载一份
  if [ -f "$self" ]; then
    cp -f "$self" /usr/local/bin/tms-panel.sh 2>/dev/null || true
  fi
  if [ ! -f /usr/local/bin/tms-panel.sh ]; then
    curl -L "$PANEL_INSTALL_RAW_URL" -o /usr/local/bin/tms-panel.sh 2>/dev/null || true
  fi
  chmod +x /usr/local/bin/tms-panel.sh 2>/dev/null || true
  # tms 启动器:cd 回面板目录再进管理菜单(compose 操作需要工作目录)
  cat > /usr/local/bin/tms <<EOF
#!/bin/bash
# TMS 面板管理命令(类似 x-ui)。直接输 tms 打开管理菜单。
TMS_DIR="$panel_dir"
[ -d "\$TMS_DIR" ] && cd "\$TMS_DIR"
# 参数要全部透传:tms domain a.com 有两个参数,只传 \$1 会把域名丢掉
# (这个坑让「配置域名」整个功能形同虚设过一阵子)
if [ \$# -eq 0 ]; then exec bash /usr/local/bin/tms-panel.sh menu; fi
exec bash /usr/local/bin/tms-panel.sh "\$@"
EOF
  chmod +x /usr/local/bin/tms 2>/dev/null || true
  echo "✅ 管理命令已就绪:以后输入  tms  即可打开管理菜单(更新/卸载/彻底清理/查看状态)"
}

# 查看运行状态
show_status() {
  echo "📊 TMS 面板容器状态:"
  docker ps -a --filter "name=gost-mysql" --filter "name=springboot-backend" --filter "name=vite-frontend" \
    --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || docker ps -a
}

# 取本机公网 IP(拿不到就回退成占位串,调用方自己判断)
get_server_ip() {
  curl -s --max-time 8 https://api.ipify.org 2>/dev/null \
    || curl -s --max-time 8 https://ipinfo.io/ip 2>/dev/null \
    || echo '你的服务器IP'
}

# 查域名的 A 记录(IPv4)。
# 【为什么不用 getent hosts】它按 getaddrinfo 的顺序返回,域名同时有 AAAA 记录时
# 头一条可能是 IPv6,拿去和公网 IPv4 比必然「对不上」,给人一个假警报;
# 而且精简系统的 nsswitch.conf 里 hosts 少了 dns 时它直接返回空 = 假「解析不到」。
# 改用 ahostsv4(只要 IPv4),再依次回退 dig / host / nslookup。
resolve_a_record() {
  local d="$1" ip=""
  ip="$(getent ahostsv4 "$d" 2>/dev/null | awk '{print $1}' | head -n1)"
  if [ -z "$ip" ] && command -v dig >/dev/null 2>&1; then
    ip="$(dig +short +time=3 +tries=2 A "$d" 2>/dev/null | grep -E '^[0-9.]+$' | head -n1)"
  fi
  if [ -z "$ip" ] && command -v host >/dev/null 2>&1; then
    ip="$(host -t A "$d" 2>/dev/null | awk '/has address/{print $4; exit}')"
  fi
  if [ -z "$ip" ] && command -v nslookup >/dev/null 2>&1; then
    # 等 Name: 出现后再取 Address: —— 前面那段是 DNS 服务器自己的地址,
    # 虽然它那行用的是制表符、多半匹配不上,但加个守卫更稳
    ip="$(nslookup -type=A "$d" 2>/dev/null | awk '/^Name:/{f=1} f && /^Address: /{print $2; exit}')"
  fi
  echo "$ip"
}
# 取面板前端端口(.env 里的,默认 6366)
get_frontend_port() {
  local fport=""
  [ -f ".env" ] && fport="$(grep '^FRONTEND_PORT=' .env | cut -d'=' -f2)"
  [ -z "$fport" ] && fport="6366"
  echo "$fport"
}

# 查看访问信息(地址 / 默认账号)
show_access_info() {
  print_access_box "$(get_server_ip)" "$(get_frontend_port)"
  local d
  d="$(current_domain)"
  [ -n "$d" ] && echo "🌐 已配置域名,也可以用: https://$d"
}

# 彻底清理 / 完整卸载:容器、镜像、数据卷、网络、配置、管理命令 全部删除,不依赖任何文件
# 当前目录的 docker-compose.yml 是不是 TMS 自己的。
# purge 里的 `down -v --rmi all` 和 `rm .env` 杀伤力很大,在别人的项目目录里
# 跑一下能把人家的容器、数据卷、镜像连同 .env 一锅端 —— 认准了再动手。
is_tms_compose() {
  [ -f docker-compose.yml ] && grep -q "teminuosi\|gost-mysql" docker-compose.yml
}

purge_panel() {
  echo "🧨 彻底清理 TMS 面板(删除所有容器/镜像/数据卷/网络/配置和 tms 管理命令)..."

  # 用 curl 一键跑 purge 时,当前目录多半不是面板安装目录 —— 那样容器能清掉,
  # 但 docker-compose.yml / .env / gost.sql 这些会原地留下,下次安装还会被复用。
  # 安装目录当时写进了 /usr/local/bin/tms 的 TMS_DIR,这里读回来切过去。
  if ! is_tms_compose && [ -f /usr/local/bin/tms ]; then
    recorded_dir="$(grep -m1 '^TMS_DIR=' /usr/local/bin/tms 2>/dev/null | cut -d'"' -f2)"
    if [ -n "$recorded_dir" ] && [ -d "$recorded_dir" ]; then
      cd "$recorded_dir" 2>/dev/null && echo "📁 已切到记录的面板目录: $recorded_dir"
    fi
  fi

  if [ -f docker-compose.yml ] && ! is_tms_compose; then
    echo "⚠️  当前目录的 docker-compose.yml 不是 TMS 的,已跳过 compose 清理和配置文件删除,"
    echo "    只按名字清 TMS 自己的容器/镜像。要清面板请先 cd 到面板安装目录。"
  fi
  if command -v docker &> /dev/null; then
    # 有 compose 就先规范地 down 一把(确认是 TMS 的才动)
    if is_tms_compose; then
      docker compose down -v --rmi all --remove-orphans 2>/dev/null \
        || docker-compose down -v --rmi all --remove-orphans 2>/dev/null || true
    fi
    # 不依赖任何文件,按名字强制删干净。
    # caddy 也要一起清:它连着 gost-network,不删的话后面 network rm 一定失败
    docker rm -f gost-mysql springboot-backend vite-frontend tms-caddy 2>/dev/null || true
    # 卷名会被 compose 加上项目名前缀(项目名 = 安装目录名),写死名字删不掉
    # xxx_mysql_data 这种。上面的 compose down -v 能处理,但 compose 文件丢了就只剩这里,
    # 所以按后缀匹配再兜一次 —— 否则数据卷留着,重装时会拿到上一次的旧数据库。
    docker volume rm mysql_data backend_logs tms_caddy_data tms_caddy_config 2>/dev/null || true
    docker volume ls -q 2>/dev/null       | grep -E '(^|_)(mysql_data|backend_logs|tms_caddy_data|tms_caddy_config)$'       | xargs -r docker volume rm 2>/dev/null || true
    docker network rm gost-network 2>/dev/null || true
    docker rmi -f ghcr.io/teminuosi/springboot-backend:latest ghcr.io/teminuosi/vite-frontend:latest mysql:5.7 2>/dev/null || true
    # 只清悬空镜像(不动其他应用),回收磁盘
    docker image prune -f 2>/dev/null || true
  fi
  # 删配置文件 —— 只在确认是 TMS 目录时删。.env 这名字太常见,
  # 在别人的项目目录里跑一下就把人家的环境变量文件删了
  if is_tms_compose || [ ! -f docker-compose.yml ]; then
    rm -f docker-compose.yml docker-compose-v4.yml docker-compose-v6.yml gost.sql .env temp_migration.sql 2>/dev/null || true
  fi
  # 删管理命令自身
  rm -f /usr/local/bin/tms /usr/local/bin/tms-panel.sh 2>/dev/null || true
  rm -rf /etc/tms 2>/dev/null || true
  echo "✅ 已彻底清理完成,系统恢复到未安装状态。"
  echo "ℹ️  这只清了【面板】。转发机上的 gost / sing-box 节点程序不在此列,"
  echo "    要卸载节点请到对应机器上单独执行节点卸载(见 README)。"
}



# 获取用户输入的配置参数
# 端口被占就往后找一个空闲的。
#
# TMS 默认用 6365/6366,同机再装 s-ui(2095/2096)或 3x-ui 通常不冲突,
# 但装过两次 TMS、或机器上跑着别的服务时照样会撞。撞了的表现是容器起不来
# 或者反复重启,日志里只有 "address already in use" 一行,不看仔细很难发现。
#
# 整行匹配「:端口 + 空白/行尾」而不是按 ss 输出的第几列取 —— 不同版本 ss 的
# 列数不一样,按列取会悄悄失效,而失效表现是「误判端口空闲」,比报错更难查。
# 末尾的边界防止 16366 这种包含关系被误判成 6366。
port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -qE "[:.]${port}([[:space:]]|$)" && return 0
    return 1
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | grep -qE "[:.]${port}([[:space:]]|$)" && return 0
    return 1
  fi
  return 1
}

pick_free_port() {
  # 三个变量必须分开声明:挤在一个 local 里时算术展开拿不到值,
  # limit 会是空字符串,while 直接报 integer expression expected 并退出 ——
  # 表现是端口检测【静默失效】,永远返回默认端口(实测踩到)
  local start="$1"
  local p="$start"
  local limit=$((start + 100))
  while [ "$p" -lt "$limit" ]; do
    if ! port_in_use "$p"; then
      echo "$p"
      return 0
    fi
    p=$((p + 1))
  done
  echo "$start"
}

get_config_params() {
  echo "🔧 自动配置参数（全自动安装，无需交互）..."

  # 端口可用环境变量覆盖(FRONTEND_PORT=xxx BACKEND_PORT=xxx),否则用默认值,不再交互
  FRONTEND_PORT=${FRONTEND_PORT:-6366}
  BACKEND_PORT=${BACKEND_PORT:-6365}

  # 端口被别的服务占着的话自动往后挪,免得容器起不来只在日志里留一行
  # "address already in use" —— 那种失败看着像面板装坏了,其实只是端口冲突
  local want_f="$FRONTEND_PORT" want_b="$BACKEND_PORT"
  FRONTEND_PORT=$(pick_free_port "$FRONTEND_PORT")
  BACKEND_PORT=$(pick_free_port "$BACKEND_PORT")
  [ "$FRONTEND_PORT" != "$want_f" ] && echo "   ⚠️ 端口 $want_f 被占用,前端改用 $FRONTEND_PORT"
  [ "$BACKEND_PORT" != "$want_b" ] && echo "   ⚠️ 端口 $want_b 被占用,后端改用 $BACKEND_PORT"

  echo "   前端端口：$FRONTEND_PORT   后端端口：$BACKEND_PORT"

  DB_NAME=$(generate_random)
  DB_USER=$(generate_random)
  DB_PASSWORD=$(generate_random)
  JWT_SECRET=$(generate_random)
}

# 安装功能
install_panel() {
  echo "🚀 开始安装面板..."
  check_docker
  get_config_params

  echo "[1/4] 下载配置文件..."
  DOCKER_COMPOSE_URL=$(get_docker_compose_url)
  # -fsSL:404 直接失败而不是把 "Not Found" 写进文件;静默但保留错误提示
  curl -fsSL -o docker-compose.yml "$DOCKER_COMPOSE_URL" || { echo "❌ 下载配置文件失败,请检查网络"; exit 1; }
  grep -q "services:" docker-compose.yml || { echo "❌ 配置文件内容不对(可能下到了错误页),请重试"; exit 1; }
  if [[ ! -f "gost.sql" ]]; then
    curl -fsSL -o gost.sql "$GOST_SQL_URL" || { echo "❌ 下载数据库文件失败,请检查网络"; exit 1; }
    grep -qi "CREATE TABLE" gost.sql || { echo "❌ 数据库文件内容不对,请重试"; exit 1; }
  fi
  echo "      ✔ 完成"

  # IPv6 默认关闭(避免改 Docker daemon 导致 mysql 启动失败);需要时用 TMS_IPV6=1 开启
  if [ "$TMS_IPV6" = "1" ]; then
    echo "🚀 TMS_IPV6=1，启用 Docker IPv6 配置..."
    configure_docker_ipv6
  fi

  cat > .env <<EOF
DB_NAME=$DB_NAME
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASSWORD
JWT_SECRET=$JWT_SECRET
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
EOF

  # 清理上一次失败/中断留下的旧容器与数据卷。
  # 关键坑:MySQL 初始化中断过一次后,mysql_data 卷里会残留半拉子文件,
  # 再启动时报 "--initialize specified but the data directory has files in it. Aborting.",
  # 容器一直 unhealthy。全新安装本就该是干净空卷,这里强制清一遍,保证一键装到底。
  echo "[2/4] 清理旧容器与数据卷(确保全新安装干净)..."
  $DOCKER_CMD down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -f gost-mysql springboot-backend vite-frontend >/dev/null 2>&1 || true
  docker volume rm mysql_data backend_logs >/dev/null 2>&1 || true
  echo "      ✔ 完成"

  echo "[3/4] 拉取镜像并启动服务(首次约 1-3 分钟,请耐心等待)..."
  # 进度条太吵会把最后的访问信息刷走,这里只留结果;失败时再把日志打出来
  if ! $DOCKER_CMD up -d >/tmp/tms_up.log 2>&1; then
    echo "      ✘ 启动失败,以下是错误信息:"
    tail -30 /tmp/tms_up.log
    exit 1
  fi
  echo "      ✔ 三个容器已启动"

  # 自动写入「面板后端地址」(转发机对接要用),省得登录后再手动到网站配置里填
  echo "[4/4] 检测公网IP并配置面板后端地址..."
  PUBLIC_IP=$(curl -s --max-time 8 https://api.ipify.org || curl -s --max-time 8 https://ipinfo.io/ip || echo "")
  if [ -n "$PUBLIC_IP" ]; then
    for i in $(seq 1 30); do
      if docker exec gost-mysql mysqladmin ping -h localhost --silent >/dev/null 2>&1; then
        if docker exec gost-mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
             -e "INSERT IGNORE INTO vite_config (name, value, time) VALUES ('ip', '${PUBLIC_IP}:${BACKEND_PORT}', $(date +%s)000);" >/dev/null 2>&1; then
          echo "      ✔ 后端地址已设为 ${PUBLIC_IP}:${BACKEND_PORT}"
        fi
        break
      fi
      sleep 2
    done
  else
    echo "      ⚠ 未获取到公网IP,登录后请到「网站配置」手动填(格式 IP:${BACKEND_PORT})"
  fi

  # 安装常驻管理命令 tms
  install_tms_command >/dev/null 2>&1

  # 收尾信息框:安装过程刷屏很正常,最后必须让人一眼看到地址/账号/密码
  print_access_box "${PUBLIC_IP:-你的服务器IP}" "$FRONTEND_PORT"


}

# 更新功能
update_panel() {
  # 先自更新管理脚本本身。/usr/local/bin/tms-panel.sh 是装机时拷的副本,
  # 仓库里修了 bug 它也不知道 —— 结果就是"脚本已经修好了,服务器上跑的还是旧的"。
  # 用环境变量兜底,防止 exec 递归。
  if [ -z "$TMS_SELF_UPDATED" ] && [ -w /usr/local/bin ]; then
    if curl -fsSL -o /tmp/tms-panel.new "$PANEL_INSTALL_RAW_URL" 2>/dev/null \
       && grep -q "install_tms_command" /tmp/tms-panel.new; then
      if ! cmp -s /tmp/tms-panel.new /usr/local/bin/tms-panel.sh; then
        mv -f /tmp/tms-panel.new /usr/local/bin/tms-panel.sh
        chmod +x /usr/local/bin/tms-panel.sh
        echo "🔄 管理脚本已更新到最新,继续..."
        export TMS_SELF_UPDATED=1
        exec bash /usr/local/bin/tms-panel.sh update
      fi
    fi
    rm -f /tmp/tms-panel.new 2>/dev/null

  # 顺带把 tms 启动器重装一遍:老机器上那份可能是有 bug 的旧版本
  # (比如只透传 $1 的那版,会让 tms domain 完全失效)。装它很便宜,每次更新都刷新一下最稳。
  install_tms_command >/dev/null 2>&1 || true
  fi

  echo "🔄 开始更新面板..."
  check_docker

  echo "🔽 下载最新配置文件..."
  DOCKER_COMPOSE_URL=$(get_docker_compose_url)
  # 先下到临时文件并校验,确认是正经 compose 再覆盖:
  # 直接 curl -o docker-compose.yml 的话,一旦 404("Not Found" 9 字节)就把现有配置
  # 冲成垃圾,面板当场起不来、还回不去(踩过)。
  if curl -fsSL -o docker-compose.yml.new "$DOCKER_COMPOSE_URL" && grep -q "services:" docker-compose.yml.new; then
    mv -f docker-compose.yml.new docker-compose.yml
    echo "      ✔ 配置文件已更新"
  else
    rm -f docker-compose.yml.new
    echo "      ✘ 配置文件下载失败,保留原有配置继续更新(不影响已有服务)"
  fi

  # IPv6 默认关闭(避免改 Docker daemon 导致 mysql 启动失败);需要时用 TMS_IPV6=1 开启
  if [ "$TMS_IPV6" = "1" ]; then
    echo "🚀 TMS_IPV6=1，启用 Docker IPv6 配置..."
    configure_docker_ipv6
  fi

  echo "🛑 停止当前服务..."
  $DOCKER_CMD down

  echo "⬇️ 拉取最新镜像..."
  $DOCKER_CMD pull

  echo "🚀 启动更新后的服务..."
  $DOCKER_CMD up -d

  # 等待服务启动
  echo "⏳ 等待服务启动..."

  # 检查后端容器健康状态
  echo "🔍 检查后端服务状态..."
  for i in {1..90}; do
    if docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
      BACKEND_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo "unknown")
      if [[ "$BACKEND_HEALTH" == "healthy" ]]; then
        echo "✅ 后端服务健康检查通过"
        break
      elif [[ "$BACKEND_HEALTH" == "starting" ]]; then
        # 继续等待
        :
      elif [[ "$BACKEND_HEALTH" == "unhealthy" ]]; then
        echo "⚠️ 后端健康状态：$BACKEND_HEALTH"
      fi
    else
      echo "⚠️ 后端容器未找到或未运行"
      BACKEND_HEALTH="not_running"
    fi
    if [ $i -eq 90 ]; then
      echo "❌ 后端服务启动超时（90秒）"
      echo "🔍 当前状态：$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
    # 每15秒显示一次进度
    if [ $((i % 15)) -eq 1 ]; then
      echo "⏳ 等待后端服务启动... ($i/90) 状态：${BACKEND_HEALTH:-unknown}"
    fi
    sleep 1
  done

  # 检查数据库容器健康状态
  echo "🔍 检查数据库服务状态..."
  for i in {1..60}; do
    if docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
      DB_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' gost-mysql 2>/dev/null || echo "unknown")
      if [[ "$DB_HEALTH" == "healthy" ]]; then
        echo "✅ 数据库服务健康检查通过"
        break
      elif [[ "$DB_HEALTH" == "starting" ]]; then
        # 继续等待
        :
      elif [[ "$DB_HEALTH" == "unhealthy" ]]; then
        echo "⚠️ 数据库健康状态：$DB_HEALTH"
      fi
    else
      echo "⚠️ 数据库容器未找到或未运行"
      DB_HEALTH="not_running"
    fi
    if [ $i -eq 60 ]; then
      echo "❌ 数据库服务启动超时（60秒）"
      echo "🔍 当前状态：$(docker inspect -f '{{.State.Health.Status}}' gost-mysql 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
    # 每10秒显示一次进度
    if [ $((i % 10)) -eq 1 ]; then
      echo "⏳ 等待数据库服务启动... ($i/60) 状态：${DB_HEALTH:-unknown}"
    fi
    sleep 1
  done

  # 从容器环境变量获取数据库信息
  echo "🔍 获取数据库配置信息..."

  # 等待一下让服务完全就绪
  echo "⏳ 等待服务完全就绪..."
  sleep 5

  # 先检查后端容器是否在运行
  if ! docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
    echo "❌ 后端容器未运行，无法获取数据库配置"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Status}}"
    echo "🛑 更新终止"
    return 1
  fi

  DB_INFO=$(docker exec springboot-backend env | grep "^DB_" 2>/dev/null || echo "")

  if [[ -n "$DB_INFO" ]]; then
    DB_NAME=$(echo "$DB_INFO" | grep "^DB_NAME=" | cut -d'=' -f2)
    DB_PASSWORD=$(echo "$DB_INFO" | grep "^DB_PASSWORD=" | cut -d'=' -f2)
    DB_USER=$(echo "$DB_INFO" | grep "^DB_USER=" | cut -d'=' -f2)
    DB_HOST=$(echo "$DB_INFO" | grep "^DB_HOST=" | cut -d'=' -f2)

    echo "📋 数据库配置："
    echo "   数据库名: $DB_NAME"
    echo "   用户名: $DB_USER"
    echo "   主机: $DB_HOST"
  else
    echo "❌ 无法获取数据库配置信息"
    echo "🔍 尝试诊断问题："
    echo "   容器状态: $(docker inspect -f '{{.State.Status}}' springboot-backend 2>/dev/null || echo '容器不存在')"
    echo "   健康状态: $(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo '无健康检查')"

    # 尝试从 .env 文件读取配置
    if [[ -f ".env" ]]; then
      echo "🔄 尝试从 .env 文件读取配置..."
      DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
      DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
      DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

      if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
        echo "✅ 从 .env 文件成功读取数据库配置"
        echo "📋 数据库配置："
        echo "   数据库名: $DB_NAME"
        echo "   用户名: $DB_USER"
      else
        echo "❌ .env 文件中的数据库配置不完整"
        echo "🛑 更新终止"
        return 1
      fi
    else
      echo "❌ 未找到 .env 文件"
      echo "🛑 更新终止"
      return 1
    fi
  fi

  # 检查必要的数据库配置
  if [[ -z "$DB_PASSWORD" || -z "$DB_USER" || -z "$DB_NAME" ]]; then
    echo "❌ 数据库配置不完整（缺少必要参数）"
    echo "🛑 更新终止"
    return 1
  fi

  # 执行数据库字段变更
  echo "🔄 执行数据库结构更新..."

  # 创建临时迁移文件（现在有了数据库信息）
  cat > temp_migration.sql <<EOF
-- 数据库结构更新
USE \`$DB_NAME\`;

-- user 表：删除 name 字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'user'
        AND column_name = 'name'
    ),
    'ALTER TABLE \`user\` DROP COLUMN \`name\`;',
    'SELECT "Column \`name\` not exists in \`user\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：删除 port 字段、添加 server_ip 字段（如果不存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port'
    ),
    'ALTER TABLE \`node\` DROP COLUMN \`port\`;',
    'SELECT "Column \`port\` not exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'server_ip'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`server_ip\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
    'SELECT "Column \`server_ip\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 将 ip 赋值给 server_ip（如果字段都存在）
UPDATE \`node\`
SET \`server_ip\` = \`ip\`
WHERE \`server_ip\` IS NULL;

-- node 表：修改 ip 字段类型为 longtext
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'ip'
        AND data_type = 'varchar'
    ),
    'ALTER TABLE \`node\` MODIFY COLUMN \`ip\` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
    'SELECT "Column \`ip\` not exists or already modified in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 version 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'version'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`version\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`version\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 port_sta 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port_sta'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`port_sta\` INT(10) DEFAULT 1000 COMMENT "端口起始范围";',
    'SELECT "Column \`port_sta\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 port_end 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port_end'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`port_end\` INT(10) DEFAULT 65535 COMMENT "端口结束范围";',
    'SELECT "Column \`port_end\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有节点设置默认端口范围
UPDATE \`node\`
SET \`port_sta\` = 1000, \`port_end\` = 65535
WHERE \`port_sta\` IS NULL OR \`port_end\` IS NULL;

-- node 表：添加 http、tls、socks 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'http'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`http\` INT(10) DEFAULT 0 COMMENT "HTTP 服务端口";',
    'SELECT "Column \`http\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'tls'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`tls\` INT(10) DEFAULT 0 COMMENT "TLS 服务端口";',
    'SELECT "Column \`tls\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'socks'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`socks\` INT(10) DEFAULT 0 COMMENT "SOCKS 服务端口";',
    'SELECT "Column \`socks\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有节点设置 http、tls、socks 默认值
UPDATE \`node\`
SET \`http\` = IFNULL(\`http\`, 0),
    \`tls\` = IFNULL(\`tls\`, 0),
    \`socks\` = IFNULL(\`socks\`, 0);

-- tunnel 表：删除废弃字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'in_port_sta'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`in_port_sta\`;',
    'SELECT "Column \`in_port_sta\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'in_port_end'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`in_port_end\`;',
    'SELECT "Column \`in_port_end\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'out_ip_sta'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`out_ip_sta\`;',
    'SELECT "Column \`out_ip_sta\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'out_ip_end'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`out_ip_end\`;',
    'SELECT "Column \`out_ip_end\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- tunnel 表：添加 tcp_listen_addr、udp_listen_addr、protocol（如果不存在）

-- tcp_listen_addr
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'tcp_listen_addr'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`tcp_listen_addr\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "0.0.0.0";',
    'SELECT "Column \`tcp_listen_addr\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- udp_listen_addr
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'udp_listen_addr'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`udp_listen_addr\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "0.0.0.0";',
    'SELECT "Column \`udp_listen_addr\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- protocol
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'protocol'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`protocol\` VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "tls";',
    'SELECT "Column \`protocol\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- traffic_ratio (流量倍率)
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'traffic_ratio'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`traffic_ratio\` DECIMAL(5,1) DEFAULT 1.0 COMMENT "流量倍率";',
    'SELECT "Column \`traffic_ratio\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认流量倍率
UPDATE \`tunnel\`
SET \`traffic_ratio\` = 1.0
WHERE \`traffic_ratio\` IS NULL;

-- forward 表：删除 proxy_protocol 字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'proxy_protocol'
    ),
    'ALTER TABLE \`forward\` DROP COLUMN \`proxy_protocol\`;',
    'SELECT "Column \`proxy_protocol\` not exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：修改 remote_addr 字段类型为 longtext
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'remote_addr'
        AND data_type = 'varchar'
    ),
    'ALTER TABLE \`forward\` MODIFY COLUMN \`remote_addr\` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;',
    'SELECT "Column \`remote_addr\` not exists or already modified in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：添加 strategy 字段（负载均衡策略）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'strategy'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`strategy\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "fifo" COMMENT "负载均衡策略";',
    'SELECT "Column \`strategy\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认负载均衡策略
UPDATE \`forward\`
SET \`strategy\` = 'fifo'
WHERE \`strategy\` IS NULL;

-- forward 表：添加 inx 字段（排序索引）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'inx'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`inx\` INT(10) DEFAULT 0 COMMENT "排序索引";',
    'SELECT "Column \`inx\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认排序索引
UPDATE \`forward\`
SET \`inx\` = 0
WHERE \`inx\` IS NULL;

-- tunnel 表：添加 interface_name 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'interface_name'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`interface_name\` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`interface_name\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：添加 interface_name 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'interface_name'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`interface_name\` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`interface_name\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 创建 vite_config 表（如果不存在）
CREATE TABLE IF NOT EXISTS \`vite_config\` (
  \`id\` int(10) NOT NULL AUTO_INCREMENT,
  \`name\` varchar(200) NOT NULL,
  \`value\` varchar(200) NOT NULL,
  \`time\` bigint(20) NOT NULL,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`unique_name\` (\`name\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 创建 statistics_flow 表（如果不存在）
CREATE TABLE IF NOT EXISTS \`statistics_flow\` (
  \`id\` bigint(20) NOT NULL AUTO_INCREMENT,
  \`user_id\` int(10) NOT NULL,
  \`flow\` bigint(20) NOT NULL,
  \`total_flow\` bigint(20) NOT NULL,
  \`time\` varchar(100) NOT NULL,
  \`created_time\` bigint(20) NOT NULL,
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- statistics_flow 表：添加 created_time 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'statistics_flow'
        AND column_name = 'created_time'
    ),
    'ALTER TABLE \`statistics_flow\` ADD COLUMN \`created_time\` BIGINT(20) NOT NULL DEFAULT 0 COMMENT "创建时间毫秒时间戳";',
    'SELECT "Column \`created_time\` already exists in \`statistics_flow\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有记录设置当前毫秒时间戳（仅当 created_time 为 0 或 NULL 时）
UPDATE \`statistics_flow\`
SET \`created_time\` = UNIX_TIMESTAMP() * 1000
WHERE \`created_time\` = 0 OR \`created_time\` IS NULL;

EOF

  # 检查数据库容器
  if ! docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
    echo "❌ 数据库容器 gost-mysql 未运行"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
    echo "❌ 数据库结构更新失败，请手动执行 temp_migration.sql"
    echo "📁 迁移文件已保存为 temp_migration.sql"
    return 1
  fi

  # 执行数据库迁移
  if docker exec -i gost-mysql mysql -u "$DB_USER" -p"$DB_PASSWORD" < temp_migration.sql 2>/dev/null; then
    echo "✅ 数据库结构更新完成"
  else
    echo "⚠️ 使用用户密码失败，尝试root密码..."
    if docker exec -i gost-mysql mysql -u root -p"$DB_PASSWORD" < temp_migration.sql 2>/dev/null; then
      echo "✅ 数据库结构更新完成"
    else
      echo "❌ 数据库结构更新失败，请手动执行 temp_migration.sql"
      echo "📁 迁移文件已保存为 temp_migration.sql"
      echo "🔍 数据库容器状态: $(docker inspect -f '{{.State.Status}}' gost-mysql 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
  fi

  # 清理临时文件
  rm -f temp_migration.sql

  echo "✅ 更新完成"
}

# 导出数据库备份
export_migration_sql() {
  echo "📄 开始导出数据库备份..."

  # 获取数据库配置信息
  echo "🔍 获取数据库配置信息..."

  # 先检查后端容器是否在运行
  if ! docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
    echo "❌ 后端容器未运行，尝试从 .env 文件读取配置..."

    # 从 .env 文件读取配置
    if [[ -f ".env" ]]; then
      DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
      DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
      DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

      if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
        echo "✅ 从 .env 文件读取数据库配置成功"
      else
        echo "❌ .env 文件中的数据库配置不完整"
        return 1
      fi
    else
      echo "❌ 未找到 .env 文件"
      return 1
    fi
  else
    # 从容器环境变量获取数据库信息
    DB_INFO=$(docker exec springboot-backend env | grep "^DB_" 2>/dev/null || echo "")

    if [[ -n "$DB_INFO" ]]; then
      DB_NAME=$(echo "$DB_INFO" | grep "^DB_NAME=" | cut -d'=' -f2)
      DB_PASSWORD=$(echo "$DB_INFO" | grep "^DB_PASSWORD=" | cut -d'=' -f2)
      DB_USER=$(echo "$DB_INFO" | grep "^DB_USER=" | cut -d'=' -f2)

      echo "✅ 从容器环境变量读取数据库配置成功"
    else
      echo "❌ 无法从容器获取数据库配置，尝试从 .env 文件读取..."

      if [[ -f ".env" ]]; then
        DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
        DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
        DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

        if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
          echo "✅ 从 .env 文件读取数据库配置成功"
        else
          echo "❌ .env 文件中的数据库配置不完整"
          return 1
        fi
      else
        echo "❌ 未找到 .env 文件"
        return 1
      fi
    fi
  fi

  # 检查必要的数据库配置
  if [[ -z "$DB_PASSWORD" || -z "$DB_USER" || -z "$DB_NAME" ]]; then
    echo "❌ 数据库配置不完整（缺少必要参数）"
    return 1
  fi

  echo "📋 数据库配置："
  echo "   数据库名: $DB_NAME"
  echo "   用户名: $DB_USER"

  # 检查数据库容器是否运行
  if ! docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
    echo "❌ 数据库容器未运行，无法导出数据"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
    return 1
  fi

  # 生成数据库备份文件
  SQL_FILE="database_backup_$(date +%Y%m%d_%H%M%S).sql"
  echo "📝 导出数据库备份: $SQL_FILE"

  # 使用 mysqldump 导出数据库
  echo "⏳ 正在导出数据库..."
  if docker exec gost-mysql mysqldump -u "$DB_USER" -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" > "$SQL_FILE" 2>/dev/null; then
    echo "✅ 数据库导出成功"
  else
    echo "⚠️ 使用用户密码失败，尝试root密码..."
    if docker exec gost-mysql mysqldump -u root -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" > "$SQL_FILE" 2>/dev/null; then
      echo "✅ 数据库导出成功"
    else
      echo "❌ 数据库导出失败"
      rm -f "$SQL_FILE"
      return 1
    fi
  fi

  # 检查文件大小
  if [[ -f "$SQL_FILE" ]] && [[ -s "$SQL_FILE" ]]; then
    FILE_SIZE=$(du -h "$SQL_FILE" | cut -f1)
    echo "📁 文件位置: $(pwd)/$SQL_FILE"
    echo "📊 文件大小: $FILE_SIZE"
  else
    echo "❌ 导出的文件为空或不存在"
    rm -f "$SQL_FILE"
    return 1
  fi
}


# ============================================================
# 域名 + HTTPS(Caddy 自动申请/续期 Let's Encrypt 证书)
#
# 刻意【不写进 docker-compose.yml】,而是独立跑一个 caddy 容器:
#   - 改 compose 里的 YAML 靠 shell 很脆,而且 tms update 会重写它
#   - 独立容器生命周期自己管,面板重启期间 caddy 还在,少一次 502
#   - 它加入 gost-network,直接用容器名 frontend:80 访问前端
# ============================================================

CADDY_CONTAINER="tms-caddy"
CADDY_FILE="/etc/tms/Caddyfile"

# 当前配的域名(没配返回空)。
# 不能直接取第一行 —— 生成的 Caddyfile 第一行是注释,要找第一个「非注释且带 {」的站点块。
current_domain() {
  [ -f "$CADDY_FILE" ] || return 0
  grep -m1 -E '^[^#[:space:]][^{]*\{' "$CADDY_FILE" 2>/dev/null | sed 's/[[:space:]]*{.*//' | tr -d ' '
}

show_domain_status() {
  local d
  d="$(current_domain)"
  if [ -z "$d" ]; then
    echo "ℹ️  当前未配置域名,面板走 http://IP:$(get_frontend_port)"
    echo "   配置方法: tms domain 你的域名.com"
    return 0
  fi
  echo "🌐 当前域名: $d"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$CADDY_CONTAINER"; then
    echo "   Caddy 状态: ✅ 运行中"
    echo "   访问地址:   https://$d"
  else
    echo "   Caddy 状态: ❌ 未运行(试试 tms domain $d 重新配置)"
  fi
}

# 关掉域名,回到 IP:端口访问
domain_off() {
  echo "🧹 关闭域名访问..."
  docker rm -f "$CADDY_CONTAINER" 2>/dev/null || true
  rm -f "$CADDY_FILE" 2>/dev/null || true
  echo "✅ 已关闭。面板回到 http://$(get_server_ip):$(get_frontend_port)"
  echo "ℹ️  证书数据还留在 docker 卷 tms_caddy_data 里,下次开同一域名不用重新申请。"
}

setup_domain() {
  local domain="$1"

  if [ -z "$domain" ]; then
    show_domain_status
    return 0
  fi
  if [ "$domain" = "off" ] || [ "$domain" = "关闭" ]; then
    domain_off
    return 0
  fi

  # 基本格式校验:必须像个域名,别把 http:// 或 IP 填进来
  if ! echo "$domain" | grep -qE '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$'; then
    echo "❌ 域名格式不对: $domain"
    echo "   只填域名本身,别带 http:// 和端口。例: tms domain panel.example.com"
    return 1
  fi
  if echo "$domain" | grep -qE '^[0-9.]+$'; then
    echo "❌ 这是个 IP 不是域名。Let's Encrypt 不给 IP 发证书。"
    return 1
  fi

  if ! command -v docker &>/dev/null; then
    echo "❌ 没装 docker,先装面板"
    return 1
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx "vite-frontend"; then
    echo "❌ 面板没在运行(找不到 vite-frontend 容器),先把面板起来再配域名"
    return 1
  fi

  echo "🌐 开始为面板配置域名: $domain"
  echo ""

  # ---- 1. 解析检查(不阻断,只警告:有人用 CDN 或者刚改完还没生效) ----
  echo "[1/5] 检查域名解析..."
  local server_ip resolved
  server_ip="$(get_server_ip)"
  resolved="$(resolve_a_record "$domain")"
  if [ -z "$resolved" ]; then
    echo "   ⚠️  解析不到 $domain,证书大概率申请不下来。"
    echo "      先去域名后台加一条 A 记录指向 $server_ip,等生效再来。"
    echo "      刚改完 DNS 的话等几分钟很正常;确认已经生效了就选 y 继续。"
    read -p "      仍然继续? (y/N): " go
    [[ "$go" == "y" || "$go" == "Y" ]] || { echo "已取消"; return 1; }
  elif [ "$resolved" != "$server_ip" ]; then
    echo "   ⚠️  $domain 解析到 $resolved,本机是 $server_ip,对不上。"
    echo "      套了 CDN(比如 Cloudflare 橙云)的话这是正常的,但证书要 CDN 那边发。"
    read -p "      仍然继续? (y/N): " go
    [[ "$go" == "y" || "$go" == "Y" ]] || { echo "已取消"; return 1; }
  else
    echo "   ✔ 解析正确 → $resolved"
  fi

  # ---- 2. 端口检查(80/443 被宝塔、nginx 占着的情况很常见) ----
  echo "[2/5] 检查 80 / 443 端口..."
  local occupied=""
  local p line who
  for p in 80 443; do
    line="$(ss -lntp 2>/dev/null | grep -E "[:.]${p}[[:space:]]" | head -n1)"
    [ -z "$line" ] && continue
    # docker-proxy 监听的多半就是 caddy 自己(重配同一域名时它本来就在听),不算冲突
    echo "$line" | grep -q "docker-proxy" && continue
    who="$(echo "$line" | sed -n 's/.*users:(("\([^"]*\)".*/\1/p')"
    [ -z "$who" ] && who="未知进程"
    occupied="${occupied} ${p}(${who})"
  done
  if [ -n "$occupied" ]; then
    echo "   ⚠️  这些端口已被占用:$occupied"
    echo "      Caddy 需要 80(证书验证)和 443(HTTPS)。装了宝塔/nginx 的话先停掉或改端口。"
    read -p "      仍然继续? (y/N): " go
    [[ "$go" == "y" || "$go" == "Y" ]] || { echo "已取消"; return 1; }
  else
    echo "   ✔ 端口可用"
  fi

  # ---- 3. 写 Caddyfile ----
  echo "[3/5] 写入配置..."
  mkdir -p "$(dirname "$CADDY_FILE")"
  cat > "$CADDY_FILE" <<EOF
# TMS 面板 · 由 tms domain 生成,别手改(下次执行会覆盖)
$domain {
    encode gzip
    reverse_proxy frontend:80 {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-Proto {scheme}
    }
}
EOF
  echo "   ✔ $CADDY_FILE"

  # ---- 4. 起 caddy ----
  echo "[4/5] 启动 Caddy..."
  docker rm -f "$CADDY_CONTAINER" 2>/dev/null || true
  if ! docker run -d \
      --name "$CADDY_CONTAINER" \
      --restart unless-stopped \
      --network gost-network \
      -p 80:80 -p 443:443 \
      -v "$CADDY_FILE":/etc/caddy/Caddyfile:ro \
      -v tms_caddy_data:/data \
      -v tms_caddy_config:/config \
      caddy:2-alpine >/dev/null; then
    echo "   ❌ Caddy 启动失败。常见原因:80/443 被占、镜像拉不下来。"
    echo "      看日志: docker logs $CADDY_CONTAINER"
    return 1
  fi
  echo "   ✔ 容器已启动"

  # ---- 5. 等证书 ----
  echo "[5/5] 等 Let's Encrypt 签发证书(最多 60 秒)..."
  local ok=0 i
  for i in $(seq 1 30); do
    sleep 2
    if curl -fsS --max-time 5 -o /dev/null "https://$domain/" 2>/dev/null; then
      ok=1
      break
    fi
    # 容器要是挂了就别干等
    if ! docker ps --format '{{.Names}}' | grep -qx "$CADDY_CONTAINER"; then
      echo "   ❌ Caddy 容器退出了"
      docker logs --tail 30 "$CADDY_CONTAINER" 2>&1 | sed 's/^/      /'
      return 1
    fi
    printf "."
  done
  echo ""

  echo ""
  echo "==============================================="
  if [ "$ok" = "1" ]; then
    echo "  ✅ 域名配置完成"
    echo "==============================================="
    echo "  访问地址: https://$domain"
  else
    echo "  ⚠️  证书还没下来"
    echo "==============================================="
    echo "  Caddy 已在运行,证书可能还在申请(慢的话要几分钟)。"
    echo "  看进度: docker logs -f $CADDY_CONTAINER"
    echo "  常见原因:80 端口不通、域名没解析到本机、被云厂商安全组挡了。"
  fi
  echo "  原来的 http://$server_ip:$(get_frontend_port) 仍然可用(留作备用入口)"
  echo ""
  echo "  ⚠️  订阅链接会跟着变成 https://$domain/...,"
  echo "     已经发出去的旧订阅(IP 版)要让车友重新拉一次。"
  echo "==============================================="
}

# 卸载功能(交互确认后走彻底清理,保证卸干净)
uninstall_panel() {
  echo "🗑️ 开始卸载面板..."
  read -p "确认卸载吗？将停止并删除所有容器、镜像、数据卷和配置 (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 取消卸载"
    return 0
  fi
  purge_panel
  echo "✅ 卸载完成"
}

# 主逻辑：默认一令到底直接安装；传参数才做别的
#   ./panel_install.sh            直接安装（默认，无需选择）
#   ./panel_install.sh update     更新
#   ./panel_install.sh uninstall  卸载
#   ./panel_install.sh menu       交互式菜单
main() {
  case "${1:-install}" in
    install)   install_panel; delete_self ;;
    update)    update_panel; delete_self ;;
    uninstall) uninstall_panel; delete_self ;;
    purge)     purge_panel; delete_self ;;
    export)    export_migration_sql; delete_self ;;
    status)    show_status ;;
    info)      show_access_info ;;
    domain)    setup_domain "$2" ;;
    menu)      menu_loop ;;
    *)
      # 打错命令不能默认去装面板 —— `tms uninstal`(少个 l)、`tms purge2` 这类
      # 手滑会变成一次重装,把正在跑的面板覆盖掉。无参数仍走安装(见上面的 :-install)。
      echo "❌ 未知命令: $1"
      echo "可用命令: install / update / uninstall / purge / export / status / info / domain / menu"
      exit 1
      ;;
  esac
}

# 交互式菜单(tms 命令默认进入这里;也可 ./panel_install.sh menu)
menu_loop() {
  while true; do
    show_menu
    read -p "请输入选项: " choice

    case $choice in
      1) install_panel; delete_self; break ;;
      2) update_panel; break ;;
      3) uninstall_panel; break ;;
      4) purge_panel; break ;;
      5) show_status ;;
      6) show_access_info ;;
      7) export_migration_sql ;;
      8)
        show_domain_status
        echo ""
        read -p "输入域名(直接回车取消,输 off 关闭域名): " d
        [ -n "$d" ] && setup_domain "$d"
        ;;
      0) echo "👋 退出"; break ;;
      *) echo "❌ 无效选项，请重新输入" ;;
    esac
    echo ""
  done
}

# 执行主函数
main "$@"
