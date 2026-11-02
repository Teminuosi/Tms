import { useState, useEffect } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Chip } from "@heroui/chip";
import toast from "react-hot-toast";
import { getMyLines, getUserPackageInfo, deleteLine } from "@/api";
import { isAdmin } from "@/utils/auth";
import { JwtUtil } from "@/utils/jwt";
import { copyTextToClipboard } from "@/utils/clipboard";
import { SubQrToggle } from "@/components/sub-qr";
import { getSubBase } from "@/utils/sub-base";

/**
 * 我的订阅(车友视角)· 一条订阅 = 一个套餐。
 * 每条线路各自带流量配额、到期、状态——不存在"账号总流量"这种混淆概念。
 * 车友只管复制链接导客户端,内部的机器/端口/转发对他隐藏。
 */
export default function MySubPage() {
  const [lines, setLines] = useState<any[]>([]);
  // 「全部线路」聚合订阅:一条链接包含他所有线路,以后新开线路也不用重发
  const [allSubToken, setAllSubToken] = useState<string>("");
  const [account, setAccount] = useState<any>(null); // 只用来判断账号是否被停用/到期
  const [loading, setLoading] = useState(true);

  // 订阅链接开头:管理员设了「订阅地址(给车友的)」就用它,没设 = 网页地址(和以前一样)。
  // 和线路一起在 load() 里取,线路出来时地址已经是对的
  const [subBase, setSubBase] = useState<string>(window.location.origin);
  const subUrl = (token: string) => `${subBase}/api/v1/open_api/sub?token=${token}`;
  // Clash / Mihomo 是另一套格式(YAML),和上面那条 base64 链接列表不通用。
  // 用 Clash Verge、ClashMeta 的人贴上面那条会得到一个空订阅。
  const clashUrl = (token: string) => `${subBase}/api/v1/open_api/clash?token=${token}`;

  const load = async () => {
    try {
      const [ln, pkg, sb] = await Promise.all([getMyLines(), getUserPackageInfo(), getSubBase()]);
      setSubBase(sb);
      if (ln.code === 0) {
        // 后端返回结构从数组改成了 {lines, allSubToken},这里两种都认,
        // 万一前后端镜像版本不同步也不会白屏
        const d: any = ln.data;
        setLines(Array.isArray(d) ? d : (d?.lines || []));
        if (!Array.isArray(d) && d?.allSubToken) setAllSubToken(d.allSubToken);
      }
      if (pkg.code === 0) setAccount(pkg.data?.userInfo || null);
    } catch (e) {
      toast.error("加载失败");
    }
    setLoading(false);
  };

  useEffect(() => {
    load();
  }, []);

  const GB = 1024 * 1024 * 1024;
  const fmtGB = (bytes: number) => ((bytes || 0) / GB).toFixed(2) + " GB";
  const fmtDate = (ms: number) => new Date(ms).toLocaleDateString();

  // 账号级异常(被管理员停用 / 账号到期)才提示,平时不打扰
  // 必须转成真正的布尔值:exp_time = 0 表示「永久」,而 `0 && ...` 返回的是 0 不是 false,
  // React 会把这个 0 原样渲染到页面上(标题下面凭空多出一个 "0")
  const accountDisabled = !!account && account.status !== undefined && account.status !== 1;
  const accountExpired = !!account?.expTime && account.expTime > 0 && account.expTime <= Date.now();

  return (
    <div className="p-4 space-y-4 max-w-4xl">
      <div className="flex items-baseline gap-3">
        <h1 className="text-xl font-bold">我的订阅</h1>
        <span className="text-sm text-default-500">共 {lines.length} 条线路,每条各自独立</span>
      </div>

      {(accountDisabled || accountExpired) && (
        <Card className="border border-danger/40 bg-danger/5">
          <CardBody className="text-sm text-danger">
            ⚠️ 你的账号{accountExpired ? "已到期" : "已被停用"},所有线路暂时不可用,请联系管理员。
          </CardBody>
        </Card>
      )}

      {!loading && allSubToken && lines.length > 1 && (
        <Card className="border border-primary/40 bg-primary/5">
          <CardBody className="space-y-3">
            <div className="flex items-center gap-2 flex-wrap">
              <Chip size="sm" color="primary" variant="flat">⭐ 全部线路</Chip>
              <span className="text-sm text-default-600">一条链接包含下面所有线路,推荐用这条</span>
              <Chip size="sm" variant="flat" className="ml-auto">
                {lines.reduce((n: number, l: any) => n + (l.protocolCount || 0), 0)} 协议
              </Chip>
            </div>
            <Input
              readOnly
              size="sm"
              value={subUrl(allSubToken)}
              onClick={(e: any) => { if (e.target?.select) e.target.select(); }}
            />
            <div className="flex gap-2 items-start">
              <Button
                size="sm"
                color="primary"
                onPress={async () => {
                  (await copyTextToClipboard(subUrl(allSubToken)))
                    ? toast.success("已复制,去客户端粘贴")
                    : toast.error("复制失败,点框内已全选,按 Ctrl+C");
                }}
              >
                复制订阅链接
              </Button>
              <SubQrToggle url={subUrl(allSubToken)} />
              <Button
                size="sm"
                variant="flat"
                onPress={async () => {
                  (await copyTextToClipboard(clashUrl(allSubToken)))
                    ? toast.success("已复制 Clash / Mihomo 版")
                    : toast.error("复制失败,请手动选中");
                }}
              >
                Clash / Mihomo 版
              </Button>
            </div>
            <div className="text-xs text-default-400">
              节点名前面带线路标识(如「香港机器 VLESS」),方便区分从哪出口。
              以后管理员给你新开线路,更新一下订阅就自动出现,不用再要新链接。
            </div>
            <div className="text-xs text-default-400">
              用 <span className="text-default-500">v2rayN / 小火箭 / v2rayNG</span> 复制上面那条;
              用 <span className="text-default-500">Clash Verge / ClashMeta / Mihomo</span> 复制「Clash / Mihomo 版」——
              两种格式不通用,贴错了客户端里会是空的。
            </div>
          </CardBody>
        </Card>
      )}

      {loading ? (
        <div className="text-center text-default-400 py-8">加载中...</div>
      ) : lines.length === 0 ? (
        <Card>
          <CardBody className="text-center text-default-400 py-8">
            还没有线路。管理员在「协议管理」或「中转」的机器卡上点「分配用户」给你开通;
            如果你就是管理员、想自己用,点那张卡上的「🔑 我自己用」即可。
          </CardBody>
        </Card>
      ) : (
        <div className="space-y-3">
          {lines.map((ln: any, idx: number) => {
            const url = subUrl(ln.subToken);
            const isRelay = ln.type === "relay";
            const used = ln.flow || 0;
            const quota = ln.quotaGb > 0 ? ln.quotaGb * GB : 0;
            const pct = quota > 0 ? Math.min(100, (used / quota) * 100) : 0;
            const stopped = ln.lineStatus === 0;
            return (
              <Card key={idx} className={stopped ? "opacity-70" : ""}>
                <CardBody className="space-y-3">
                  {/* 标题行:类型 + 机器 + 协议数 */}
                  <div className="flex items-center gap-2 flex-wrap">
                    <Chip size="sm" variant="flat" color={isRelay ? "warning" : "primary"}>
                      {isRelay ? `🔀 中转${ln.landingName ? "→" + ln.landingName : ""}` : "🖥️ 直连"}
                    </Chip>
                    <span className="font-medium truncate">{ln.nodeName}</span>
                    {stopped && <Chip size="sm" color="danger" variant="flat">已停用</Chip>}
                    <Chip size="sm" variant="flat" className="ml-auto">{ln.protocolCount} 协议</Chip>
                  </div>

                  {/* 这条订阅自己的套餐:流量 + 到期 */}
                  <div className="flex items-center gap-6 text-sm">
                    <div>
                      <span className="text-default-500 text-xs">流量 </span>
                      <span className="font-semibold">{fmtGB(used)}</span>
                      <span className="text-default-400">
                        {quota > 0 ? ` / ${ln.quotaGb} GB` : " / 不限"}
                      </span>
                    </div>
                    <div>
                      <span className="text-default-500 text-xs">到期 </span>
                      <span className="font-semibold">
                        {ln.lineExpTime ? fmtDate(ln.lineExpTime) : "永久"}
                      </span>
                    </div>
                  </div>
                  {quota > 0 && (
                    <div className="w-full h-1.5 bg-default-200 rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full ${pct > 90 ? "bg-danger" : pct > 70 ? "bg-warning" : "bg-primary"}`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                  )}

                  {/* 订阅链接 */}
                  <Input
                    readOnly
                    size="sm"
                    value={url}
                    onClick={(e: any) => { if (e.target?.select) e.target.select(); }}
                  />
                  <div className="flex gap-2 items-start">
                    <Button
                      size="sm"
                      color="primary"
                      onPress={async () => {
                        (await copyTextToClipboard(url))
                          ? toast.success("已复制,去客户端粘贴")
                          : toast.error("复制失败,点框内已全选,按 Ctrl+C");
                      }}
                    >
                      复制订阅链接
                    </Button>
                    <SubQrToggle url={url} />
                    {/* 删除只给管理员看。这一页车友也在用,而删线路是不可逆的 ——
                        端口会释放,以后要再用得管理员重新分配、重新发一遍链接。
                        车友手滑点一下就得来找人重开,这个成本不该由界面制造。
                        要收回别人的线路仍然走「用户管理」,那边还多一个可逆的「停用」。 */}
                    {isAdmin() && (
                      <>
                        <div className="flex-1" />
                        <Button
                          size="sm"
                          variant="light"
                          color="danger"
                          onPress={async () => {
                            const myId = JwtUtil.getUserIdFromToken();
                            if (myId == null) return toast.error("登录状态异常,刷新一下再试");
                            if (!confirm(`彻底删除「${ln.nodeName}」这条线路?
该线路下 ${ln.protocolCount} 个协议的分配和转发会一并删掉,端口释放。
这一步不可逆,以后要再用得重新分配。`)) return;
                            const res = await deleteLine(myId, ln.nodeId, ln.landingId ?? null);
                            if (res.code === 0) {
                              toast.success("已删掉这条线路");
                              await load();
                            } else {
                              toast.error(res.msg || "删除失败");
                            }
                          }}
                        >
                          删除
                        </Button>
                      </>
                    )}
                  </div>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}

      {/* 用法 */}
      <Card>
        <CardBody className="space-y-2 text-sm text-default-600">
          <div className="font-semibold">怎么用</div>
          <div>复制上面任意一条订阅链接,在客户端里添加订阅:</div>
          <ul className="list-disc pl-5 space-y-1 text-default-500">
            <li><b>v2rayN(Windows)</b>:订阅 → 订阅分组设置 → 添加 → 粘贴地址 → 确定 → 更新订阅</li>
            <li><b>小火箭 / Shadowrocket(iOS)</b>:右上角 + → 类型选「Subscribe」→ 粘贴地址</li>
            <li><b>v2rayNG(安卓)</b>:左侧菜单 → 订阅分组设置 → + → 粘贴地址 → 更新订阅</li>
          </ul>
          <div className="text-xs text-default-400">
            每条线路是独立的套餐:流量、到期各算各的,一条用完不影响另一条。管理员在某条线路上加了新协议,你更新订阅就自动出现。
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
