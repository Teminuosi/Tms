import { useEffect, useState, useCallback } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Chip } from "@heroui/chip";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Progress } from "@heroui/progress";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import toast from "react-hot-toast";
import NodeMonitorModal from "@/components/node-monitor";
import { copyTextToClipboard } from "@/utils/clipboard";
import {
  getMonitorOverview, getMonitorSettings, saveMonitorSettings, testMonitorTelegram,
  saveNodeMeta, getProbeTargets, saveProbeTarget, deleteProbeTarget,
} from "@/api";

// 监控:总览 / 告警 / 延迟目标 / 公开状态页
type Tab = "overview" | "alert" | "targets" | "status";
const TABS: { key: Tab; label: string }[] = [
  { key: "overview", label: "总览" },
  { key: "alert", label: "告警" },
  { key: "targets", label: "延迟目标" },
  { key: "status", label: "状态页" },
];

export const fmtBytes = (v?: number | null) => {
  if (v == null) return "-";
  if (!v) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let n = v;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n < 10 && i > 0 ? 2 : n < 100 && i > 0 ? 1 : 0)} ${u[i]}`;
};

export const latencyColor = (avg: number | null, loss: number) =>
  avg == null || loss >= 50 ? "danger" : loss > 0 || avg > 200 ? "warning" : "success";

const daysLeft = (date?: string | null) => {
  if (!date) return null;
  const d = new Date(date + "T00:00:00");
  const t = new Date(); t.setHours(0, 0, 0, 0);
  return Math.round((d.getTime() - t.getTime()) / 86400000);
};

function Overview({ ver, onChart, onMeta }: { ver: number; onChart: (n: any) => void; onMeta: (n: any) => void }) {
  const [nodes, setNodes] = useState<any[] | null>(null);
  const [failed, setFailed] = useState(false);
  const load = useCallback(() => {
    getMonitorOverview()
      .then((r: any) => { if (r.code === 0) { setNodes(r.data || []); setFailed(false); } else setFailed(true); })
      .catch(() => setFailed(true));
  }, []);
  useEffect(() => {
    load();
    const t = setInterval(load, 30000);
    return () => clearInterval(t);
  }, [load, ver]);

  if (nodes === null) return failed
    ? <div className="text-center text-danger py-16 text-sm">获取失败,稍后自动重试</div>
    : <div className="flex justify-center py-16"><Spinner /></div>;
  if (nodes.length === 0) return <div className="text-center text-default-500 py-16 text-sm">还没有转发机</div>;

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
      {nodes.map((n) => {
        const left = daysLeft(n.expireDate);
        const quota = n.quotaGb ? n.quotaGb * 1024 ** 3 : 0;
        const pct = quota ? Math.min(100, (n.cycleUsed / quota) * 100) : 0;
        return (
          <Card key={n.id} className="border border-divider">
            <CardHeader className="pb-1 flex justify-between items-start gap-2">
              <div className="min-w-0">
                <div className="font-semibold text-sm truncate">{n.name}</div>
                <div className="text-xs text-default-500 truncate">{n.serverIp}</div>
              </div>
              <div className="flex gap-1 flex-shrink-0">
                {left != null && (
                  <Chip size="sm" variant="flat" color={left < 0 ? "danger" : left <= 3 ? "warning" : "default"}>
                    {left < 0 ? `已过期 ${-left} 天` : left === 0 ? "今天到期" : `${left} 天后到期`}
                  </Chip>
                )}
                <Chip size="sm" variant="flat" color={n.online ? "success" : "danger"}>{n.online ? "在线" : "离线"}</Chip>
              </div>
            </CardHeader>
            <CardBody className="pt-1 space-y-2.5 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <div className="flex justify-between mb-1"><span className="text-default-500">CPU</span><span>{n.cpu == null ? "-" : `${n.cpu.toFixed(1)}%`}</span></div>
                  <Progress size="sm" value={n.cpu || 0} color={(n.cpu || 0) > 85 ? "danger" : "primary"} aria-label="CPU" />
                </div>
                <div>
                  <div className="flex justify-between mb-1"><span className="text-default-500">内存</span><span>{n.mem == null ? "-" : `${n.mem.toFixed(1)}%`}</span></div>
                  <Progress size="sm" value={n.mem || 0} color={(n.mem || 0) > 85 ? "danger" : "success"} aria-label="内存" />
                </div>
              </div>
              <div className="flex justify-between">
                <span className="text-default-500">网速(近 1 分钟)</span>
                <span>↓ {fmtBytes(n.rxRate)}/s · ↑ {fmtBytes(n.txRate)}/s</span>
              </div>
              <div>
                <div className="flex justify-between mb-1">
                  <span className="text-default-500">本月流量{n.resetDay > 1 ? `(每月 ${n.resetDay} 号重置)` : ""}</span>
                  <span>{fmtBytes(n.cycleUsed)}{quota ? ` / ${n.quotaGb} GB` : ""}</span>
                </div>
                {quota > 0 && <Progress size="sm" value={pct} color={pct >= 100 ? "danger" : pct >= 80 ? "warning" : "secondary"} aria-label="流量" />}
              </div>
              <div className="flex flex-wrap gap-1 min-h-6">
                {!n.online ? (
                  <span className="text-default-400">离线中,延迟暂停监测</span>
                ) : !n.probeSupported ? (
                  <span className="text-default-400">延迟监测:节点需升级到 1.2.5+</span>
                ) : (n.probes || []).length === 0 ? (
                  <span className="text-default-400">延迟:等待数据</span>
                ) : (
                  n.probes.map((p: any) => (
                    <Chip key={p.targetId} size="sm" variant="flat" color={latencyColor(p.avg, p.loss)}>
                      {p.name} {p.avg == null ? "不通" : `${Math.round(p.avg)}ms`}{p.loss > 0 && p.avg != null ? ` 丢${Math.round(p.loss)}%` : ""}
                    </Chip>
                  ))
                )}
              </div>
              <div className="flex gap-1.5 pt-1">
                <Button size="sm" variant="flat" color="primary" className="flex-1" onPress={() => onChart(n)}>曲线</Button>
                <Button size="sm" variant="flat" className="flex-1" onPress={() => onMeta(n)}>到期 / 额度</Button>
              </div>
            </CardBody>
          </Card>
        );
      })}
    </div>
  );
}

function MetaModal({ node, onClose, onSaved }: { node: any | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<any>({});
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (node) setF({
      expireDate: node.expireDate || "",
      quotaGb: node.quotaGb ?? "",
      resetDay: String(node.resetDay || 1),
      trafficMode: node.trafficMode || "both",
      publicShow: node.publicShow !== false,
      publicName: node.publicName || "",
    });
  }, [node]);
  const save = async () => {
    setSaving(true);
    try {
      const r: any = await saveNodeMeta({ nodeId: node.id, ...f });
      if (r.code === 0) { toast.success("已保存"); onSaved(); onClose(); } else toast.error(r.msg || "保存失败");
    } finally { setSaving(false); }
  };
  return (
    <Modal isOpen={!!node} onClose={onClose} placement="center">
      <ModalContent>
        <ModalHeader>{node?.name} · 到期 / 额度</ModalHeader>
        <ModalBody className="space-y-1">
          <Input type="date" label="VPS 到期日" description="填了才会提醒;留空 = 不提醒" value={f.expireDate || ""}
            onValueChange={(v) => setF({ ...f, expireDate: v })} />
          <Input type="number" label="月流量额度(GB)" description="留空 = 不限,不提醒" value={String(f.quotaGb ?? "")}
            onValueChange={(v) => setF({ ...f, quotaGb: v })} />
          <div className="grid grid-cols-2 gap-2">
            <Input type="number" label="每月几号重置" value={f.resetDay || "1"} min={1} max={31}
              onValueChange={(v) => setF({ ...f, resetDay: v })} />
            <Select label="怎么算" selectedKeys={[f.trafficMode || "both"]}
              onSelectionChange={(k) => setF({ ...f, trafficMode: Array.from(k)[0] || "both" })}>
              <SelectItem key="both">上下行相加</SelectItem>
              <SelectItem key="out">只算上行</SelectItem>
              <SelectItem key="max">取较大的一边</SelectItem>
            </Select>
          </div>
          <p className="text-xs text-default-500 leading-relaxed">
            流量从面板升级后开始记,跟商家后台的数字会有出入,当个参考。不同商家算法不同,按你的商家选「怎么算」。
          </p>
          <div className="flex items-center justify-between pt-2">
            <span className="text-sm">在公开状态页显示</span>
            <Switch size="sm" isSelected={!!f.publicShow} onValueChange={(v) => setF({ ...f, publicShow: v })} />
          </div>
          <Input label="状态页上的名字" description="留空用转发机名。不想让人看出机房,可以改个名" value={f.publicName || ""}
            onValueChange={(v) => setF({ ...f, publicName: v })} />
        </ModalBody>
        <ModalFooter>
          <Button variant="light" onPress={onClose}>取消</Button>
          <Button color="primary" isLoading={saving} onPress={save}>保存</Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function AlertSettings() {
  const [s, setS] = useState<any>(null);
  const [token, setToken] = useState("");
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const load = () => getMonitorSettings().then((r: any) => r.code === 0 && setS(r.data));
  useEffect(() => { load(); }, []);
  if (!s) return <div className="flex justify-center py-16"><Spinner /></div>;

  const save = async () => {
    setSaving(true);
    try {
      const r: any = await saveMonitorSettings({
        tg_token: token, tg_chat: s.tg_chat, alert_offline_min: s.alert_offline_min,
        alert_expire_days: s.alert_expire_days, alert_traffic: s.alert_traffic,
      });
      if (r.code === 0) { toast.success("已保存"); setToken(""); load(); } else toast.error(r.msg || "保存失败");
    } finally { setSaving(false); }
  };
  const test = async () => {
    setTesting(true);
    try {
      const r: any = await testMonitorTelegram();
      r.code === 0 ? toast.success("已发送,去 Telegram 看看") : toast.error(r.msg || "发送失败");
    } finally { setTesting(false); }
  };
  const ready = s.tg_token_set === "1" && !!(s.tg_chat || "").trim();

  return (
    <Card className="max-w-2xl border border-divider">
      <CardBody className="space-y-3">
        <div className="text-sm">
          推送到 Telegram。<span className={ready ? "text-success" : "text-warning"}>{ready ? "已开启" : "未开启(两项都填了才会发)"}</span>
        </div>
        <Input type="password" label="Bot Token" value={token} onValueChange={setToken}
          placeholder={s.tg_token_set === "1" ? "已填写,留空不改" : "123456:ABC-DEF..."}
          description="找 @BotFather 发 /newbot 创建机器人,它会给你 Token" />
        <Input label="Chat ID" value={s.tg_chat || ""} onValueChange={(v) => setS({ ...s, tg_chat: v })}
          description="先给你的机器人发一句话,再找 @userinfobot 拿自己的 ID;发到群就填群 ID(负数)" />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Input type="number" label="掉线多少分钟报警" value={s.alert_offline_min ?? "3"}
            onValueChange={(v) => setS({ ...s, alert_offline_min: v })} description="0 = 不报。恢复在线也会报一声" />
          <Input type="number" label="到期前几天开始提醒" value={s.alert_expire_days ?? "3"}
            onValueChange={(v) => setS({ ...s, alert_expire_days: v })} description="0 = 不报。每天上午 10 点提醒" />
        </div>
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm">流量提醒</div>
            <div className="text-xs text-default-500">设了额度的转发机,用到 80% 和用完时各提醒一次</div>
          </div>
          <Switch size="sm" isSelected={s.alert_traffic === "1"} onValueChange={(v) => setS({ ...s, alert_traffic: v ? "1" : "0" })} />
        </div>
        <p className="text-xs text-default-500">到期日和流量额度在「总览」每台转发机的「到期 / 额度」里填。</p>
        <div className="flex gap-2 pt-1">
          <Button color="primary" isLoading={saving} onPress={save}>保存</Button>
          <Button variant="flat" isLoading={testing} isDisabled={!ready} onPress={test}>发一条测试</Button>
          {s.tg_token_set === "1" && (
            <Button variant="light" color="danger" onPress={async () => {
              const r: any = await saveMonitorSettings({ clear_tg_token: true });
              if (r.code === 0) { toast.success("已清除,告警关闭"); load(); }
            }}>清除 Token</Button>
          )}
        </div>
      </CardBody>
    </Card>
  );
}

function Targets() {
  const [list, setList] = useState<any[] | null>(null);
  const [edit, setEdit] = useState<any | null>(null);
  const load = () => getProbeTargets().then((r: any) => r.code === 0 && setList(r.data || []));
  useEffect(() => { load(); }, []);
  const save = async () => {
    const r: any = await saveProbeTarget(edit);
    if (r.code === 0) { toast.success("已保存,在线的转发机会自动更新"); setEdit(null); load(); } else toast.error(r.msg || "保存失败");
  };
  if (!list) return <div className="flex justify-center py-16"><Spinner /></div>;
  return (
    <div className="max-w-2xl space-y-3">
      <p className="text-xs text-default-500 leading-relaxed">
        每台转发机每 5 分钟对这些目标各测 4 次 TCP 建连。测的是建连耗时,数值通常比 ping 略高,看趋势和丢包更有用。
        需要转发机节点程序 1.2.5 及以上。
      </p>
      {list.map((t) => (
        <Card key={t.id} className="border border-divider">
          <CardBody className="flex flex-row items-center justify-between gap-2 py-2.5">
            <div className="min-w-0">
              <div className="text-sm font-medium">{t.name}</div>
              <div className="text-xs text-default-500 font-mono truncate">{t.host}:{t.port}</div>
            </div>
            <div className="flex items-center gap-1.5 flex-shrink-0">
              <Switch size="sm" isSelected={t.enabled === 1}
                onValueChange={async (v) => { await saveProbeTarget({ ...t, enabled: v }); load(); }} />
              <Button size="sm" variant="flat" onPress={() => setEdit({ ...t })}>编辑</Button>
              <Button size="sm" variant="flat" color="danger"
                onPress={async () => { await deleteProbeTarget(t.id); load(); }}>删除</Button>
            </div>
          </CardBody>
        </Card>
      ))}
      <Button color="primary" variant="flat" onPress={() => setEdit({ name: "", host: "", port: 80, enabled: true })}>添加目标</Button>
      <Modal isOpen={!!edit} onClose={() => setEdit(null)} placement="center">
        <ModalContent>
          <ModalHeader>{edit?.id ? "编辑目标" : "添加目标"}</ModalHeader>
          <ModalBody>
            <Input label="名称" value={edit?.name || ""} onValueChange={(v) => setEdit({ ...edit, name: v })} placeholder="如:上海电信" />
            <Input label="地址" value={edit?.host || ""} onValueChange={(v) => setEdit({ ...edit, host: v })} placeholder="IP 或域名" />
            <Input type="number" label="端口" value={String(edit?.port ?? "")} onValueChange={(v) => setEdit({ ...edit, port: v })} />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setEdit(null)}>取消</Button>
            <Button color="primary" onPress={save}>保存</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function StatusSettings() {
  const [s, setS] = useState<any>(null);
  useEffect(() => { getMonitorSettings().then((r: any) => r.code === 0 && setS(r.data)); }, []);
  if (!s) return <div className="flex justify-center py-16"><Spinner /></div>;
  const url = `${window.location.origin}/status`;
  const save = async (patch: any) => {
    const next = { ...s, ...patch };
    setS(next);
    const r: any = await saveMonitorSettings({ status_enabled: next.status_enabled, status_title: next.status_title });
    r.code === 0 ? toast.success("已保存") : toast.error(r.msg || "保存失败");
  };
  return (
    <Card className="max-w-2xl border border-divider">
      <CardBody className="space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm">公开状态页</div>
            <div className="text-xs text-default-500">不用登录就能看。只显示名字、在线、CPU、内存、网速、延迟;不显示 IP、到期和额度</div>
          </div>
          <Switch isSelected={s.status_enabled === "1"} onValueChange={(v) => save({ status_enabled: v ? "1" : "0" })} />
        </div>
        <Input label="页面标题" value={s.status_title || ""} onValueChange={(v) => setS({ ...s, status_title: v })}
          onBlur={() => save({})} />
        <div className="flex items-center gap-2">
          <code className="flex-1 text-xs font-mono bg-default-100 rounded px-2 py-2 break-all">{url}</code>
          <Button size="sm" variant="flat" onPress={() => copyTextToClipboard(url).then((ok) => ok ? toast.success("已复制") : toast.error("复制失败,手动选中复制"))}>复制</Button>
          <Button size="sm" variant="flat" onPress={() => window.open(url, "_blank")}>打开</Button>
        </div>
        <p className="text-xs text-default-500">哪些转发机显示、显示什么名字,在「总览」每台的「到期 / 额度」里设。</p>
      </CardBody>
    </Card>
  );
}

export default function MonitorPage() {
  const [tab, setTab] = useState<Tab>("overview");
  const [chartNode, setChartNode] = useState<any | null>(null);
  const [metaNode, setMetaNode] = useState<any | null>(null);
  const [ver, setVer] = useState(0);
  return (
    <div className="px-3 lg:px-6 py-6 space-y-4">
      <div className="flex gap-1 flex-wrap">
        {TABS.map((t) => (
          <Button key={t.key} size="sm" variant={tab === t.key ? "solid" : "flat"}
            color={tab === t.key ? "primary" : "default"} onPress={() => setTab(t.key)}>{t.label}</Button>
        ))}
      </div>
      {tab === "overview" && <Overview ver={ver} onChart={setChartNode} onMeta={setMetaNode} />}
      {tab === "alert" && <AlertSettings />}
      {tab === "targets" && <Targets />}
      {tab === "status" && <StatusSettings />}
      <NodeMonitorModal node={chartNode} onClose={() => setChartNode(null)} />
      <MetaModal node={metaNode} onClose={() => setMetaNode(null)} onSaved={() => setVer((v) => v + 1)} />
    </div>
  );
}
