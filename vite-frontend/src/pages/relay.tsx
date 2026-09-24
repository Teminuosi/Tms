import { useState, useEffect } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input, Textarea } from "@heroui/input";
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
  oneClickRelay,
  testLanding,
  deleteInboundsByNode,
  assignAllToUser,
  assignSelf,
  getNodeList,
  getAllUsers,
  getSpeedLimitList,
  getLandingList,
} from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";
import { SNI_PRESETS, DEFAULT_SNI, cleanSni } from "@/config/sni";
import { SubQr } from "@/components/sub-qr";

/**
 * 中转(前置机协议 + 落地出口)· 机器卡模式。
 * 搭中转时当场填落地(粘贴分享链接/住宅socks)→ 经前置机测试通 → 保存搭建。
 * 车友连的还是前置机的订阅,只是出口 IP 在落地那台。
 */
export default function RelayPage() {
  const [inbounds, setInbounds] = useState<any[]>([]);
  const [nodes, setNodes] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [speedRules, setSpeedRules] = useState<any[]>([]);
  const [landings, setLandings] = useState<any[]>([]);

  const [buildOpen, setBuildOpen] = useState(false);
  const [buildForm, setBuildForm] = useState<any>({ nodeId: null, name: "", link: "", sni: DEFAULT_SNI });
  const [buildLoading, setBuildLoading] = useState(false);
  const [testLoading, setTestLoading] = useState(false);
  const [testResult, setTestResult] = useState<any>(null); // {ok, exitIp, latencyMs, skipped, msg}

  const [assignOpen, setAssignOpen] = useState(false);
  const [assignForm, setAssignForm] = useState<any>({ nodeId: null, nodeName: "", protocolCount: 0, userId: null, speedId: null, expDate: null, flowGb: null });
  const [assignLoading, setAssignLoading] = useState(false);

  // 「我自己用」:一键把这条中转开给当前管理员自己,完事直接弹订阅链接
  const [selfLoading, setSelfLoading] = useState<string | null>(null);
  const [selfSubUrl, setSelfSubUrl] = useState<string>("");
  // 中转这边更容易混:同一台前置机可能有好几条落地,弹出来的链接域名部分还都一样
  const [selfLineName, setSelfLineName] = useState<string>("");
  const [selfOpen, setSelfOpen] = useState(false);

  const handleAssignSelf = async (nodeId: number, landingId: any, lineName?: string) => {
    const key = `${nodeId}-${landingId}`;
    setSelfLoading(key);
    setSelfLineName(lineName || "");
    try {
      const res = await assignSelf({ nodeId, relay: true, landingId });
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
      const [ib, nd, us, sp, ld] = await Promise.all([
        getInboundList(),
        getNodeList(),
        getAllUsers(),
        getSpeedLimitList(),
        getLandingList(),
      ]);
      if (ib.code === 0) setInbounds(ib.data || []);
      if (nd.code === 0) setNodes(nd.data || []);
      if (us.code === 0) {
        const d: any = us.data;
        setUsers(Array.isArray(d) ? d : (d && d.records ? d.records : []));
      }
      if (sp.code === 0) setSpeedRules(sp.data || []);
      if (ld.code === 0) setLandings(ld.data || []);
    } catch (e) {
      toast.error("加载失败");
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const protoLabel = (p: string) =>
    (({ vless: "VLESS-Reality", trojan: "Trojan-Reality", vmess: "VMess", shadowsocks: "Shadowsocks-2022", hysteria2: "Hysteria2", tuic: "TUIC", anytls: "AnyTLS" } as any)[p] || p);
  const landingById = (id: any) => landings.find((l) => l.id === id);

  const handleTest = async () => {
    if (!buildForm.nodeId) return toast.error("先选前置机(要经它测落地)");
    if (!buildForm.link) return toast.error("先粘贴落地链接");
    setTestLoading(true);
    setTestResult(null);
    try {
      const res = await testLanding(buildForm.nodeId, buildForm.link);
      if (res.code === 0) {
        setTestResult(res.data);
        if (res.data?.skipped) toast.success(res.data?.msg || "格式已校验");
        else if (res.data?.ok) toast.success(`通了,出口 IP ${res.data?.exitIp}`);
      } else {
        setTestResult({ ok: false, msg: res.msg });
        toast.error(res.msg || "测试失败");
      }
    } catch (e) {
      toast.error("测试失败");
    }
    setTestLoading(false);
  };

  const handleBuild = async () => {
    if (!buildForm.nodeId) return toast.error("请选择前置机");
    if (!buildForm.link) return toast.error("请粘贴落地链接");
    setBuildLoading(true);
    try {
      const res = await oneClickRelay(buildForm.nodeId, buildForm.link, buildForm.name, cleanSni(buildForm.sni));
      // 同 inbound 页:半成功(「中转已入库,但下发配置失败」「中断…已成功 N 个」)
      // 协议是真建出来了,不该报红条、不该把弹窗晾着让人再点一次。
      if (toastResult(res, "一键搭中转完成:整机协议已建好,出口走落地", "搭建失败", toast)) {
        setBuildOpen(false);
      }
      loadAll(); // 真失败也刷,列表要回到面板真实的样子
    } catch (e) {
      toast.error("搭建失败");
    }
    setBuildLoading(false);
  };

  const openNodeAssign = (n: any, landingId: any, landingName: string, count: number) => {
    setAssignForm({ nodeId: n.id, nodeName: n.name, landingId, landingName, protocolCount: count, userId: null, speedId: null, expDate: null, flowGb: null });
    setAssignOpen(true);
  };

  const handleNodeAssign = async () => {
    if (!assignForm.userId) return toast.error("请选择车友");
    setAssignLoading(true);
    try {
      const payload: any = { userId: assignForm.userId, nodeId: assignForm.nodeId, relay: true, landingId: assignForm.landingId };
      if (assignForm.speedId) payload.speedId = assignForm.speedId;
      // 到期直接选日期(当天 23:59:59 截止),续费就是把日期往后改
      if (assignForm.expDate) payload.expTime = new Date(`${assignForm.expDate}T23:59:59`).getTime();
      if (assignForm.flowGb) payload.flow = Math.round(assignForm.flowGb); // 单位 GB(线路配额按 GB 存)
      const res = await assignAllToUser(payload);
      if (res.code === 0) {
        {
          const a = res.data?.assigned ?? 0, u = res.data?.updated ?? 0;
          toast.success(
            a > 0
              ? `已分配 ${a} 个协议` + (u ? `,更新 ${u} 个` : "") + " · 订阅去「用户管理」拿"
              : u > 0
              ? `已更新这条中转的限速/到期/流量(${u} 个协议)`
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

  const handleClearNode = async (nodeId: number, nodeName: string, landingId: any, landingName: string) => {
    if (!window.confirm(`确定清空「${nodeName} → ${landingName}」这条中转?(连带其转发/用户;直连和其它落地不受影响)`)) return;
    const res = await deleteInboundsByNode(nodeId, true, landingId);
    if (res.code === 0) {
      toast.success("已清空该条中转");
      loadAll();
    } else {
      toast.error(res.msg || "清空失败");
    }
  };

  // 中转线路 = 每(前置机 × 落地)一条卡
  const relayLines: any[] = [];
  nodes.forEach((n) => {
    const relayIbs = inbounds.filter((ib) => ib.nodeId === n.id && ib.landingId);
    const lids = Array.from(new Set(relayIbs.map((ib) => ib.landingId)));
    lids.forEach((lid) => {
      relayLines.push({ node: n, landingId: lid, inbounds: relayIbs.filter((ib) => ib.landingId === lid) });
    });
  });

  return (
    <div className="p-4 space-y-4">
      <div className="flex justify-between items-center">
        <h1 className="text-xl font-bold">中转</h1>
        <Button
          color="secondary"
          onPress={() => {
            setBuildForm({ nodeId: null, name: "", link: "", sni: DEFAULT_SNI });
            setTestResult(null);
            setBuildOpen(true);
          }}
        >
          ⚡ 搭中转
        </Button>
      </div>

      <div className="text-xs text-default-500">
        中转 = 前置机搭协议(抗封锁),流量经「落地」出网。车友连的还是前置机的订阅,只是出口 IP 换成落地那台的。分配/限速/订阅与协议管理完全一致。
      </div>

      {/* 每(前置机 × 落地)一张卡 = 一条中转线路 */}
      <div className="grid gap-3 md:grid-cols-2">
        {relayLines.map((ln) => {
          const n = ln.node;
          const l = landingById(ln.landingId);
          const landingName = l ? `${l.name}(${l.type})` : `落地#${ln.landingId}`;
          const online = n.status === 1;
          const firstIp = n.ip ? String(n.ip).split(",")[0].trim() : (n.serverIp || "");
          return (
            <Card key={`${n.id}-${ln.landingId}`}>
              <CardBody className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="text-lg font-semibold truncate">🖥️ {n.name}</span>
                  <Chip size="sm" variant="flat" color={online ? "success" : "default"}>{online ? "在线" : "离线"}</Chip>
                  <Chip size="sm" variant="flat" color="primary" className="ml-auto">{ln.inbounds.length} 协议</Chip>
                </div>
                {firstIp && <div className="text-xs text-default-500 font-mono">前置机 {firstIp}</div>}
                <div className="flex flex-wrap items-center gap-1 text-xs">
                  <span className="text-default-500">落地 →</span>
                  <Chip size="sm" variant="flat" color="warning">{landingName}</Chip>
                </div>
                <div className="flex flex-wrap gap-1">
                  {ln.inbounds.map((ib: any) => (
                    <Chip key={ib.id} size="sm" variant="flat" color="secondary">{protoLabel(ib.protocol)}</Chip>
                  ))}
                </div>
                <div className="flex gap-2">
                  <Button size="sm" color="primary" className="flex-1" onPress={() => openNodeAssign(n, ln.landingId, landingName, ln.inbounds.length)}>
                    👤 分配用户
                  </Button>
                  {/* 自己用不必先建车友:一键开给当前管理员,不限速不限量不到期 */}
                  <Button
                    size="sm"
                    color="success"
                    variant="flat"
                    isLoading={selfLoading === `${n.id}-${ln.landingId}`}
                    onPress={() => handleAssignSelf(n.id, ln.landingId, `${n.name} → ${landingName}`)}
                  >
                    🔑 我自己用
                  </Button>
                  <Button size="sm" color="danger" variant="flat" onPress={() => handleClearNode(n.id, n.name, ln.landingId, landingName)}>
                    清空该条
                  </Button>
                </div>
              </CardBody>
            </Card>
          );
        })}
      </div>
      {relayLines.length === 0 && (
        <div className="text-center text-default-400 py-8">
          还没有中转。点右上角「⚡ 搭中转」→ 选前置机 + 粘贴落地(住宅 socks 或协议链接)→ 测试通 → 搭建。
        </div>
      )}

      {/* 「我自己用」结果:直接把订阅链接给出来 */}
      <Modal isOpen={selfOpen} onClose={() => setSelfOpen(false)} size="2xl">
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            <span>🔑 已开给你自己(不限速 · 不限流量 · 不限到期)</span>
            {selfLineName && (
              <span className="text-sm font-normal text-default-500">
                线路:<b className="text-foreground">{selfLineName}</b>
              </span>
            )}
          </ModalHeader>
          <ModalBody className="space-y-2">
            <div className="text-sm text-default-500">
              这条中转订阅是给你自己用的,复制到客户端就能用,出口走落地。以后在「我的订阅」页也能找到。
            </div>
            <div className="text-xs text-default-400 bg-default-100 rounded-lg px-3 py-2">
              💡 链接前半段是<b>面板地址</b>,所以每条线路点出来都一样 —— 真正区分线路的是末尾的
              <b> token</b>。拉下来的节点才是这条线路的。
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

      {/* 分配用户(复用协议管理的整机分配) */}
      <Modal isOpen={assignOpen} onClose={() => setAssignOpen(false)}>
        <ModalContent>
          <ModalHeader>👤 中转分配「{assignForm.nodeName} → {assignForm.landingName}」</ModalHeader>
          <ModalBody className="space-y-3">
            <div className="text-sm text-default-500">
              把这条中转的 <b>{assignForm.protocolCount} 个协议</b> 一次分给车友,出口走 {assignForm.landingName}。分配完到「用户管理」拿这条中转订阅链接。
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
              label="这条中转的流量配额(GB,留空=不单独限)"
              value={assignForm.flowGb ?? ""}
              onChange={(e) => setAssignForm({ ...assignForm, flowGb: e.target.value ? Number(e.target.value) : null })}
              description="中转走落地流量成本高,建议单独设:超了只停这条中转,车友的直连线路照用"
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setAssignOpen(false)}>关闭</Button>
            <Button color="primary" isLoading={assignLoading} onPress={handleNodeAssign}>分配</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 搭中转:选前置机 + 内联填落地 + 测试 + 搭建 */}
      <Modal isOpen={buildOpen} onClose={() => setBuildOpen(false)} size="2xl">
        <ModalContent>
          <ModalHeader>⚡ 搭中转</ModalHeader>
          <ModalBody className="space-y-3">
            <div className="text-sm text-default-500">
              选前置机 + 填落地出口 → 测试通了 → 搭建。前置机上建全套协议,流量经落地出网。
            </div>
            <Select
              label="前置机(客户端连的那台)"
              placeholder="选一台前置机(需在线)"
              selectedKeys={buildForm.nodeId ? [String(buildForm.nodeId)] : []}
              onSelectionChange={(k) => { setBuildForm({ ...buildForm, nodeId: Number(Array.from(k)[0]) }); setTestResult(null); }}
            >
              {nodes.map((n) => (<SelectItem key={n.id}>{n.name}</SelectItem>))}
            </Select>
            <Input
              label="落地名称(自己起)"
              placeholder="如 泰国住宅"
              value={buildForm.name}
              onChange={(e) => setBuildForm({ ...buildForm, name: e.target.value })}
            />
            <Textarea
              label="落地出口(粘贴)"
              placeholder="住宅socks: IP:端口:账号:密码    协议节点: ss:// / vmess:// / vless:// / trojan:// / hysteria2://"
              minRows={2}
              value={buildForm.link}
              onChange={(e) => { setBuildForm({ ...buildForm, link: e.target.value }); setTestResult(null); }}
              description="住宅 socks 直接填 IP:端口:账号:密码;机场/别人节点整条分享链接粘进来。测试会经前置机试连、显示出口 IP"
            />
            <div className="flex items-center gap-2">
              <Button size="sm" variant="flat" color="secondary" isLoading={testLoading} onPress={handleTest}>🔌 测试落地</Button>
              {testResult && (
                testResult.skipped ? (
                  <span className="text-xs text-default-500">{testResult.msg}</span>
                ) : testResult.ok ? (
                  <span className="text-xs text-success">✅ 通了 · 出口 IP <b className="font-mono">{testResult.exitIp}</b> · {testResult.latencyMs}ms</span>
                ) : (
                  <span className="text-xs text-danger">❌ {testResult.msg || "不通"}</span>
                )
              )}
            </div>
            {/* Reality 借壳域名:建在前置机上的协议用,给个常用列表也允许自己输 */}
            <Autocomplete
              label="伪装域名(Reality 借壳)"
              allowsCustomValue
              defaultItems={SNI_PRESETS}
              inputValue={buildForm.sni}
              onInputChange={(v) => setBuildForm({ ...buildForm, sni: v })}
              onSelectionChange={(k) => { if (k) setBuildForm({ ...buildForm, sni: String(k) }); }}
              description="只影响前置机上 VLESS / Trojan 这两个 Reality 协议。可以直接输入别的域名;别用 www.microsoft.com(它上了后量子,握不上手)"
            >
              {(item: any) => <AutocompleteItem key={item.value} description={item.desc || undefined}>{item.label}</AutocompleteItem>}
            </Autocomplete>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setBuildOpen(false)}>取消</Button>
            <Button color="secondary" isLoading={buildLoading} onPress={handleBuild}>保存并搭建</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
