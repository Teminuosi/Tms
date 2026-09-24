import { useState, useEffect } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Autocomplete, AutocompleteItem } from "@heroui/autocomplete";
import { DatePicker } from "@heroui/date-picker";
import { parseDate } from "@internationalized/date";
import toast from "react-hot-toast";
import { toastResult } from "@/utils/partial-success";
import {
  getInboundList,
  createInbound,
  oneClickInbound,
  deleteInboundsByNode,
  assignAllToUser,
  assignSelf,
  getNodeList,
  getAllUsers,
  getSpeedLimitList,
  pushNodeConfig,
} from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";
import { SNI_PRESETS, DEFAULT_SNI, cleanSni } from "@/config/sni";
import { SubQr } from "@/components/sub-qr";

/**
 * 协议管理(合体面板)· 机器卡模式。
 * 一台机器 = 一张卡(卡上折叠着这台机器的全套协议)。
 * 卡上「分配用户」→ 把这台机器所有协议一次分给车友 → 出一条订阅链接。
 * 车友加这一条订阅,机器上全部协议自动到手,以后加新协议自动更新。
 */
export default function InboundPage() {
  const [inbounds, setInbounds] = useState<any[]>([]);
  const [nodes, setNodes] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [speedRules, setSpeedRules] = useState<any[]>([]);

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<any>({ nodeId: null, protocol: "vless", sni: DEFAULT_SNI, dest: "", remark: "" });
  const [createLoading, setCreateLoading] = useState(false);

  const [oneClickOpen, setOneClickOpen] = useState(false);
  const [oneClickNodeId, setOneClickNodeId] = useState<number | null>(null);
  const [oneClickSni, setOneClickSni] = useState<string>(DEFAULT_SNI);
  const [oneClickLoading, setOneClickLoading] = useState(false);

  // 机器卡「分配用户」:把整台机器的协议分给车友(只分配,链接去「用户管理」拿)
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignForm, setAssignForm] = useState<any>({ nodeId: null, nodeName: "", protocolCount: 0, userId: null, speedId: null, expDate: null, flowGb: null });
  const [assignLoading, setAssignLoading] = useState(false);

  // 「我自己用」:一键开给当前管理员自己,完事直接把订阅链接弹出来
  const [selfLoading, setSelfLoading] = useState<number | null>(null);
  const [pushing, setPushing] = useState<number | null>(null);
  const [selfSubUrl, setSelfSubUrl] = useState<string>("");
  const [selfOpen, setSelfOpen] = useState(false);
  // 订阅链接的域名部分永远是【面板地址】,几台机器点出来长得几乎一样,
  // 只有末尾 token 不同 —— 不写清楚是哪台机器,很容易以为"点第二台弹的还是第一台"
  const [selfNodeName, setSelfNodeName] = useState<string>("");

  const handleAssignSelf = async (nodeId: number, nodeName?: string) => {
    setSelfLoading(nodeId);
    setSelfNodeName(nodeName || "");
    try {
      const res = await assignSelf({ nodeId });
      if (res.code === 0 && res.data?.subToken) {
        setSelfSubUrl(`${window.location.origin}/api/v1/open_api/sub?token=${res.data.subToken}`);
        setSelfOpen(true);
        loadAll();
      } else {
        toast.error(res.msg || "开通失败");
      }
    } catch (e) {
      toast.error("开通失败");
    }
    setSelfLoading(null);
  };

  const loadAll = async () => {
    try {
      const [ib, nd, us, sp] = await Promise.all([
        getInboundList(),
        getNodeList(),
        getAllUsers(),
        getSpeedLimitList(),
      ]);
      if (ib.code === 0) setInbounds(ib.data || []);
      if (nd.code === 0) setNodes(nd.data || []);
      if (us.code === 0) {
        const d: any = us.data;
        setUsers(Array.isArray(d) ? d : (d && d.records ? d.records : []));
      }
      if (sp.code === 0) setSpeedRules(sp.data || []);
    } catch (e) {
      toast.error("加载失败");
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const protoLabel = (p: string) =>
    (({ vless: "VLESS-Reality", trojan: "Trojan-Reality", vmess: "VMess", shadowsocks: "Shadowsocks-2022", hysteria2: "Hysteria2", tuic: "TUIC", anytls: "AnyTLS" } as any)[p] || p);
  const isReality = (p: string) => p === "vless" || p === "trojan";

  const handleCreate = async () => {
    if (!createForm.nodeId) return toast.error("请选择节点");
    if (isReality(createForm.protocol) && !createForm.sni) return toast.error("Reality 协议需要填 SNI");
    setCreateLoading(true);
    try {
      // 界面上「VMess + WebSocket」是一个独立选项,但后端没有 vmess-ws 这个协议 ——
      // 传输层是 vmess 的修饰,不是另一种协议。所以在这儿拆开:protocol=vmess + transport=ws。
      const isWs = createForm.protocol === "vmess-ws";
      const payload: any = {
        nodeId: createForm.nodeId,
        protocol: isWs ? "vmess" : createForm.protocol,
        remark: createForm.remark,
      };
      if (isWs) {
        payload.transport = "ws";
        payload.wsPath = createForm.wsPath || "";   // 留空由后端随机生成
        payload.wsHost = createForm.wsHost || "";
      }
      if (isReality(createForm.protocol)) {
        payload.sni = cleanSni(createForm.sni);
        payload.dest = createForm.dest;
      }
      const res = await createInbound(payload);
      if (res.code === 0) {
        toast.success("入站已创建");
        setCreateOpen(false);
        loadAll();
      } else {
        toast.error(res.msg || "创建失败");
      }
    } catch (e) {
      toast.error("创建失败");
    }
    setCreateLoading(false);
  };

  const handleOneClick = async () => {
    if (!oneClickNodeId) return toast.error("请选择节点");
    setOneClickLoading(true);
    try {
      const res = await oneClickInbound(oneClickNodeId, cleanSni(oneClickSni));
      // 半成功(「已入库,但下发配置失败」「中断…已成功 3 个」)也要关弹窗:
      // 协议是真建出来了。以前它走 else 分支报红条、列表不刷新、弹窗还开着,
      // 用户十有八九再点一次 —— 那会重复建、撞端口。
      if (toastResult(res, "一键添加完成:整机全套协议已建好", "一键添加失败", toast)) {
        setOneClickOpen(false);
      }
      // 真失败也刷:失败常常是建到一半撞的,列表得回到面板真实的样子,
      // 否则用户是在对着幻影操作。
      loadAll();
    } catch (e) {
      toast.error("一键添加失败");
    }
    setOneClickLoading(false);
  };

  const openNodeAssign = (n: any, count: number) => {
    setAssignForm({ nodeId: n.id, nodeName: n.name, protocolCount: count, userId: null, speedId: null, expDate: null, flowGb: null });
    setAssignOpen(true);
  };

  const handleNodeAssign = async () => {
    if (!assignForm.userId) return toast.error("请选择车友");
    setAssignLoading(true);
    try {
      const payload: any = { userId: assignForm.userId, nodeId: assignForm.nodeId };
      if (assignForm.speedId) payload.speedId = assignForm.speedId;
      // 到期直接选日期(当天 23:59:59 截止),比填"多少天"直观,续费也只是把日期往后改
      if (assignForm.expDate) payload.expTime = new Date(`${assignForm.expDate}T23:59:59`).getTime();
      if (assignForm.flowGb) payload.flow = Math.round(assignForm.flowGb); // 单位 GB(线路配额按 GB 存)
      const res = await assignAllToUser(payload);
      if (res.code === 0) {
        {
          const a = res.data?.assigned ?? 0, u = res.data?.updated ?? 0;
          toast.success(
            a > 0
              ? `已分配 ${a} 个协议` + (u ? `,更新 ${u} 个` : "") + " · 订阅链接去「用户管理」拿"
              : u > 0
              ? `已更新这条线路的限速/到期/流量(${u} 个协议)`
              : "配额和到期已更新"
          );
        }
        setAssignOpen(false);
        loadAll();
      } else {
        toast.error(res.msg || "分配失败");
      }
    } catch (e) {
      toast.error("分配失败");
    }
    setAssignLoading(false);
  };

  // 机器上的 sing-box 掉了(转发诊断报「所有TCP连接尝试都失败」)时用这个。
  // 下发的是库里的全量,点几次都一样,不会把已有协议弄乱。
  const handlePushConfig = async (nodeId: number, nodeName: string) => {
    setPushing(nodeId);
    const res = await pushNodeConfig(nodeId);
    setPushing(null);
    if (res.code === 0) {
      toast.success(`已把「${nodeName}」的协议配置重新下发一遍`);
    } else {
      // 这里的失败几乎都是节点掉线/超时,原样把后端的话给出来最有用
      toast.error(res.msg || "下发失败");
    }
  };

  const handleClearNode = async (nodeId: number, nodeName: string) => {
    if (!window.confirm(`确定清空「${nodeName}」上的直连协议?(连带其转发/用户;中转协议不受影响)`)) return;
    const res = await deleteInboundsByNode(nodeId, false);
    if (res.code === 0) {
      toast.success("已清空该机协议");
      loadAll();
    } else {
      toast.error(res.msg || "清空失败");
    }
  };

  // 协议管理只管【直连】协议(landingId 为空);中转的协议在「中转」页管
  const machineNodes = nodes.filter((n) => inbounds.some((ib) => ib.nodeId === n.id && !ib.landingId));

  return (
    <div className="p-4 space-y-4">
      <div className="flex justify-between items-center">
        <h1 className="text-xl font-bold">协议管理</h1>
        <div className="flex gap-2">
          <Button
            color="secondary"
            onPress={() => {
              setOneClickNodeId(null);
              setOneClickOpen(true);
            }}
          >
            ⚡ 一键搭建整机协议
          </Button>
          <Button
            color="primary"
            variant="flat"
            onPress={() => {
              setCreateForm({ nodeId: null, protocol: "vless", sni: DEFAULT_SNI, dest: "", remark: "" });
              setCreateOpen(true);
            }}
          >
            单独加一个协议
          </Button>
        </div>
      </div>

      {/* 一机一卡:每台机器的全套协议折叠成一条记录,卡上直接分配用户 */}
      <div className="grid gap-3 md:grid-cols-2">
        {machineNodes.map((n) => {
          const nodeInbounds = inbounds.filter((ib) => ib.nodeId === n.id && !ib.landingId);
          const online = n.status === 1;
          const firstIp = n.ip ? String(n.ip).split(",")[0].trim() : (n.serverIp || "");
          return (
            <Card key={n.id}>
              <CardBody className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="text-lg font-semibold truncate">🖥️ {n.name}</span>
                  <Chip size="sm" variant="flat" color={online ? "success" : "default"}>{online ? "在线" : "离线"}</Chip>
                  <Chip size="sm" variant="flat" color="primary" className="ml-auto">{nodeInbounds.length} 协议</Chip>
                </div>
                {firstIp && <div className="text-xs text-default-500 font-mono">{firstIp}</div>}

                {/* 节点在线 ≠ 协议可用:gost 和 sing-box 是两个服务,sing-box 挂了
                    这里照样显示「在线」,但这台机上所有协议全都连不上。必须单独标出来 —— 
                    不然只会以为是协议参数配错了,往那个方向查很久都查不出来 */}
                {online && n.singboxRunning === false && nodeInbounds.length > 0 && (
                  n.singboxInstalling ? (
                    <div className="rounded-lg border border-default-300 bg-default-100 px-3 py-2 space-y-1">
                      <div className="text-sm font-medium text-default-600">⏳ sing-box 正在安装,请稍候…</div>
                      <div className="text-xs text-default-500">
                        首次建协议时会现下 sing-box(约 57MB),一般 1-2 分钟。装好后这里自动恢复正常,不用管。
                      </div>
                    </div>
                  ) : n.singboxInstallErr ? (
                    <div className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 space-y-1">
                      <div className="text-sm font-semibold text-danger">⚠️ sing-box 安装失败,这台机的协议全部不可用</div>
                      <div className="text-xs text-default-500 break-all">
                        节点报的原因:<code className="font-mono">{n.singboxInstallErr}</code>
                      </div>
                      <div className="text-xs text-default-500">
                        多半是这台机下载 GitHub 失败。国内机器改用镜像版命令重跑节点安装脚本(见 README)。
                      </div>
                    </div>
                  ) : (
                    <div className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 space-y-1">
                      <div className="text-sm font-semibold text-danger">⚠️ sing-box 未运行,这台机的协议全部不可用</div>
                      {n.singboxInstalled === false ? (
                        <div className="text-xs text-default-500">
                          这台机上<span className="text-danger font-medium">根本没装 sing-box</span> —— 装节点时从 GitHub
                          下载失败了(国内机常见)。到这台机上重跑一次节点安装脚本即可,装好后面板会自动把协议配置推下去,
                          不用重新分配。
                        </div>
                      ) : (
                        <div className="text-xs text-default-500">
                          节点本身在线(gost 正常),但跑协议的 sing-box 没起来。到这台机上执行:
                          <code className="font-mono bg-default-200 px-1 rounded ml-1">systemctl enable --now sing-box</code>
                          <div className="mt-1">
                            若报 <code className="font-mono">Unit file sing-box.service does not exist</code>,说明根本没装上
                            (下载 GitHub 失败),重跑一次节点安装脚本即可。
                          </div>
                        </div>
                      )}
                    </div>
                  )
                )}
                <div className="flex flex-wrap gap-1">
                  {nodeInbounds.map((ib) => (
                    <Chip key={ib.id} size="sm" variant="flat" color="secondary">{protoLabel(ib.protocol)}</Chip>
                  ))}
                </div>
                <div className="text-xs text-default-400">
                  整机一条订阅:分配给车友后,一条订阅链接导入客户端即拿到上面全部协议,以后加新协议自动更新。
                </div>
                <div className="flex gap-2">
                  <Button size="sm" color="primary" className="flex-1" onPress={() => openNodeAssign(n, nodeInbounds.length)}>
                    👤 分配用户
                  </Button>
                  {/* 自己用不必先建车友再分配:一键开给当前管理员,不限速不限量不到期 */}
                  <Button
                    size="sm"
                    color="success"
                    variant="flat"
                    isLoading={selfLoading === n.id}
                    onPress={() => handleAssignSelf(n.id, n.name)}
                  >
                    🔑 我自己用
                  </Button>
                  <Button
                    size="sm"
                    variant="flat"
                    isLoading={pushing === n.id}
                    onPress={() => handlePushConfig(n.id, n.name)}
                  >
                    🔄 重推配置
                  </Button>
                  <Button size="sm" color="danger" variant="flat" onPress={() => handleClearNode(n.id, n.name)}>
                    清空该机
                  </Button>
                </div>
              </CardBody>
            </Card>
          );
        })}
      </div>
      {machineNodes.length === 0 && (
        <div className="text-center text-default-400 py-8">还没有协议,点右上角「⚡ 一键搭建整机协议」在某台机器上把全套协议建出来</div>
      )}

      {/* 「我自己用」结果:直接把订阅链接给出来,不用再去用户管理找 */}
      <Modal isOpen={selfOpen} onClose={() => setSelfOpen(false)} size="2xl">
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            <span>🔑 已开给你自己(不限速 · 不限流量 · 不限到期)</span>
            {selfNodeName && (
              <span className="text-sm font-normal text-default-500">
                机器:<b className="text-foreground">{selfNodeName}</b>
              </span>
            )}
          </ModalHeader>
          <ModalBody className="space-y-2">
            <div className="text-sm text-default-500">
              这条订阅是给你自己用的,复制到 v2rayN / 小火箭 里就能用。以后随时在「我的订阅」页也能找到。
            </div>
            <div className="text-xs text-default-400 bg-default-100 rounded-lg px-3 py-2">
              💡 链接前半段是<b>面板地址</b>,所以每台机器点出来都一样 —— 真正区分线路的是末尾的
              <b> token</b>。拉下来的节点才是这台机器的。
            </div>
            <Input
              readOnly
              value={selfSubUrl}
              onClick={(e: any) => { if (e.target?.select) e.target.select(); }}
            />
            <SubQr url={selfSubUrl} />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setSelfOpen(false)}>关闭</Button>
            <Button
              color="primary"
              onPress={async () => {
                (await copyTextToClipboard(selfSubUrl))
                  ? toast.success("已复制订阅链接")
                  : toast.error("复制失败,点框内已全选,按 Ctrl+C");
              }}
            >
              复制订阅链接
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 机器卡「分配用户」:整机协议一次分给车友,出一条订阅链接 */}
      <Modal isOpen={assignOpen} onClose={() => setAssignOpen(false)}>
        <ModalContent>
          <ModalHeader>👤 给车友分配「{assignForm.nodeName}」</ModalHeader>
          <ModalBody className="space-y-3">
            <div className="text-sm text-default-500">
              把这台机器上的 <b>{assignForm.protocolCount} 个协议</b> 一次分给车友。分配完到「用户管理」页,点该车友的「🔗 订阅链接」拿链接发给他。
            </div>
            <Select
              label="子账号(车友)"
              placeholder="选一个车友"
              selectedKeys={assignForm.userId ? [String(assignForm.userId)] : []}
              onSelectionChange={(k) => setAssignForm({ ...assignForm, userId: Number(Array.from(k)[0]) })}
            >
              {users.map((u) => (<SelectItem key={u.id}>{u.user}</SelectItem>))}
            </Select>
            <Select
              label="限速规则(可空)"
              placeholder="不限速"
              selectedKeys={assignForm.speedId ? [String(assignForm.speedId)] : []}
              onSelectionChange={(k) => setAssignForm({ ...assignForm, speedId: Number(Array.from(k)[0]) })}
            >
              {speedRules.map((s) => (<SelectItem key={s.id}>{s.name}</SelectItem>))}
            </Select>
            <DatePicker
              label="到期日期(留空=永久)"
              value={assignForm.expDate ? parseDate(assignForm.expDate) as any : null}
              onChange={(d: any) => setAssignForm({
                ...assignForm,
                expDate: d ? `${d.year}-${String(d.month).padStart(2, "0")}-${String(d.day).padStart(2, "0")}` : null,
              })}
              showMonthAndYearPickers
              className="cursor-pointer"
              description="到这天 23:59 自动停;续费直接把日期往后改再点一次分配"
            />
            <Input
              type="number"
              label="这条线路的流量配额(GB,留空=不单独限)"
              value={assignForm.flowGb ?? ""}
              onChange={(e) => setAssignForm({ ...assignForm, flowGb: e.target.value ? Number(e.target.value) : null })}
              description="只算这条线路的用量,超了只停这条,车友其它线路照用;留空则只受账号总流量约束"
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setAssignOpen(false)}>关闭</Button>
            <Button color="primary" isLoading={assignLoading} onPress={handleNodeAssign}>分配</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 一键搭建整机协议:选机器,把所有支持的协议一键全建出来 */}
      <Modal isOpen={oneClickOpen} onClose={() => setOneClickOpen(false)}>
        <ModalContent>
          <ModalHeader>⚡ 一键搭建整机协议</ModalHeader>
          <ModalBody className="space-y-3">
            <div className="text-sm text-default-500">
              在选中的机器上一键建好全部协议:<b>VLESS-Reality、Trojan-Reality、VMess、Hysteria2、TUIC、AnyTLS</b>(端口、密钥、自签证书全自动;端口被占自动上移)。建好后就是一张机器卡,点「分配用户」出订阅即可。
            </div>
            <Select
              label="机器"
              placeholder="选一台机器(需在线)"
              selectedKeys={oneClickNodeId ? [String(oneClickNodeId)] : []}
              onSelectionChange={(k) => setOneClickNodeId(Number(Array.from(k)[0]))}
            >
              {nodes.map((n) => (
                <SelectItem key={n.id}>{n.name}</SelectItem>
              ))}
            </Select>
            {/* Reality 借壳域名:给个常用列表,也允许自己输 */}
            <Autocomplete
              label="伪装域名(Reality 借壳)"
              allowsCustomValue
              defaultItems={SNI_PRESETS}
              inputValue={oneClickSni}
              onInputChange={(v) => setOneClickSni(v)}
              onSelectionChange={(k) => { if (k) setOneClickSni(String(k)); }}
              description="只影响 VLESS / Trojan 这两个 Reality 协议。可以直接输入别的域名;别用 www.microsoft.com(它上了后量子,握不上手)"
            >
              {(item: any) => <AutocompleteItem key={item.value} description={item.desc || undefined}>{item.label}</AutocompleteItem>}
            </Autocomplete>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setOneClickOpen(false)}>取消</Button>
            <Button color="secondary" isLoading={oneClickLoading} onPress={handleOneClick}>一键全建</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 单独加一个协议(补充用) */}
      <Modal isOpen={createOpen} onClose={() => setCreateOpen(false)}>
        <ModalContent>
          <ModalHeader>单独加一个协议</ModalHeader>
          <ModalBody className="space-y-3">
            <Select
              label="协议"
              selectedKeys={[createForm.protocol]}
              onSelectionChange={(k) => setCreateForm({ ...createForm, protocol: String(Array.from(k)[0]) })}
              description={
                isReality(createForm.protocol)
                  ? "无域名借 Reality(SNI 借壳),抗封锁强(推荐)"
                  : createForm.protocol === "vmess"
                  ? "VMess:TCP 无 TLS,无域名,兼容各种老客户端"
                  : createForm.protocol === "vmess-ws"
                  ? "VMess over WebSocket。裸 ws(不带 TLS),给需要挂 Nginx/Caddy 反代或 CDN 的人用;直连场景没必要选它,不如用 Reality"
                  : ["hysteria2", "tuic", "anytls"].includes(createForm.protocol)
                  ? "自签证书(无域名);客户端需勾选\"允许不安全/insecure\"。Hy2/TUIC 是 QUIC,快"
                  : "Shadowsocks-2022:无 TLS、任何客户端都通,简单稳"
              }
            >
              <SelectItem key="vless">VLESS-Reality(无域名,推荐)</SelectItem>
              <SelectItem key="trojan">Trojan-Reality(无域名)</SelectItem>
              <SelectItem key="vmess">VMess(无域名,兼容老客户端)</SelectItem>
              <SelectItem key="vmess-ws">VMess + WebSocket(可挂 CDN/反代)</SelectItem>
              <SelectItem key="hysteria2">Hysteria2(QUIC,快,自签证书)</SelectItem>
              <SelectItem key="tuic">TUIC(QUIC,自签证书)</SelectItem>
              <SelectItem key="anytls">AnyTLS(自签证书)</SelectItem>
            </Select>
            <Select
              label="机器"
              placeholder="选一台机器"
              selectedKeys={createForm.nodeId ? [String(createForm.nodeId)] : []}
              onSelectionChange={(k) => setCreateForm({ ...createForm, nodeId: Number(Array.from(k)[0]) })}
            >
              {nodes.map((n) => (
                <SelectItem key={n.id}>{n.name}</SelectItem>
              ))}
            </Select>
            {createForm.protocol === "vmess-ws" && (
              <>
                <Input
                  label="WebSocket 路径"
                  placeholder="留空自动随机,如 /a1b2c3d4"
                  value={createForm.wsPath || ""}
                  onChange={(e) => setCreateForm({ ...createForm, wsPath: e.target.value })}
                  description="留空会自动生成一个随机路径。固定用 / 是被主动探测扫出来的头号特征,别图省事"
                />
                <Input
                  label="Host 头(可选)"
                  placeholder="套 CDN 时填你的域名,直连留空"
                  value={createForm.wsHost || ""}
                  onChange={(e) => setCreateForm({ ...createForm, wsHost: e.target.value })}
                  description="只有前面挂了 CDN 或反代才需要;直连用不上"
                />
              </>
            )}
            {isReality(createForm.protocol) && (
              <>
                <Autocomplete
                  label="伪装域名(Reality 借壳)"
                  allowsCustomValue
                  defaultItems={SNI_PRESETS}
                  inputValue={createForm.sni}
                  onInputChange={(v) => setCreateForm({ ...createForm, sni: v })}
                  onSelectionChange={(k) => { if (k) setCreateForm({ ...createForm, sni: String(k) }); }}
                  description="可以直接输入别的域名;别用 www.microsoft.com(它上了后量子,Reality 握不上手)"
                >
                  {(item: any) => <AutocompleteItem key={item.value} description={item.desc || undefined}>{item.label}</AutocompleteItem>}
                </Autocomplete>
                <Input
                  label="Reality 目标(留空=同 SNI)"
                  value={createForm.dest}
                  onChange={(e) => setCreateForm({ ...createForm, dest: e.target.value })}
                />
              </>
            )}
            <Input
              label="备注"
              value={createForm.remark}
              onChange={(e) => setCreateForm({ ...createForm, remark: e.target.value })}
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setCreateOpen(false)}>取消</Button>
            <Button color="primary" isLoading={createLoading} onPress={handleCreate}>创建</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
