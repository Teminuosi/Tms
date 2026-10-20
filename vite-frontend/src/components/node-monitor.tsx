import { useEffect, useState } from "react";
import { Modal, ModalContent, ModalHeader, ModalBody } from "@heroui/modal";
import { Button } from "@heroui/button";
import { Spinner } from "@heroui/spinner";
import {
  ResponsiveContainer, AreaChart, Area, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, LineChart, Line,
} from "recharts";
import { getNodeMetrics, getNodeProbes } from "@/api";

// 转发机历史曲线(面板每分钟存一条,7 天后汇总成每小时一条)
type Range = "1h" | "24h" | "7d" | "30d";
const RANGES: { key: Range; label: string }[] = [
  { key: "1h", label: "1 小时" },
  { key: "24h", label: "24 小时" },
  { key: "7d", label: "7 天" },
  { key: "30d", label: "30 天" },
];

const fmtBytes = (v: number) => {
  if (!v) return "0";
  const u = ["B", "K", "M", "G", "T"];
  let i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v < 10 && i > 0 ? 1 : 0)}${u[i]}`;
};

const fmtTime = (ts: number, range: Range) => {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`;
  return range === "1h" || range === "24h" ? hm : `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}时`;
};

const AXIS = { fontSize: 11, fill: "currentColor" };

// recharts 把颜色写成 SVG 属性,属性里不认 var(),只能先从当前皮肤读出实际颜色
const themeColor = (name: string, fallback: string) => {
  const v = getComputedStyle(document.documentElement).getPropertyValue(`--heroui-${name}`).trim();
  return v ? `hsl(${v.split(/\s+/).join(", ")})` : fallback;
};

function ChartBox({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-divider bg-content1/60 p-3">
      <div className="text-xs font-medium text-default-600 mb-2">{title}</div>
      <div className="h-44 w-full text-default-500">
        <ResponsiveContainer width="100%" height="100%">{children as any}</ResponsiveContainer>
      </div>
    </div>
  );
}

export default function NodeMonitorModal({ node, onClose }: { node: any | null; onClose: () => void }) {
  const [range, setRange] = useState<Range>("24h");
  const [loading, setLoading] = useState(false);
  const [points, setPoints] = useState<any[]>([]);
  const [error, setError] = useState("");
  const [probe, setProbe] = useState<any>(null);
  const [step, setStep] = useState(60);

  useEffect(() => {
    if (!node) return;
    let alive = true;
    setLoading(true);
    setError("");
    getNodeMetrics(node.id, range)
      .then((res: any) => {
        if (!alive) return;
        if (res.code === 0) {
          setPoints((res.data?.points || []).map((p: any) => ({ ...p, t: fmtTime(p.ts, range) })));
          setStep(res.data?.stepSeconds || 60);
        } else {
          setError(res.msg || "获取失败");
        }
      })
      .catch(() => alive && setError("获取失败"))
      .finally(() => alive && setLoading(false));
    getNodeProbes(node.id, range)
      .then((res: any) => alive && res.code === 0 && setProbe(res.data))
      .catch(() => {});
    return () => { alive = false; };
  }, [node?.id, range]);

  const C = {
    primary: themeColor("primary", "#7b5cff"),
    secondary: themeColor("secondary", "#4f8bff"),
    success: themeColor("success", "#22c55e"),
    warning: themeColor("warning", "#f59e0b"),
  };
  const tip = { background: themeColor("content1", "#111"), border: "none", borderRadius: 8 };
  // 延迟:每个目标一条线。key = "a<目标id>" / "l<目标id>"
  const probeTargets: any[] = probe?.targets || [];
  const probeRows: any[] = (() => {
    const m = new Map<number, any>();
    for (const p of probe?.points || []) {
      const row = m.get(p.ts) || { ts: p.ts, t: fmtTime(p.ts, range) };
      row["a" + p.targetId] = p.avg;
      row["l" + p.targetId] = p.loss;
      m.set(p.ts, row);
    }
    return [...m.values()].sort((a, b) => a.ts - b.ts);
  })();
  const LINE_COLORS = [C.primary, C.secondary, C.success, C.warning, "#ec4899", "#14b8a6", "#f97316", "#a3e635"];
  const totalRx = points.reduce((s, p) => s + (p.rx || 0), 0);
  const totalTx = points.reduce((s, p) => s + (p.tx || 0), 0);

  return (
    <Modal isOpen={!!node} onClose={onClose} size="4xl" scrollBehavior="inside" placement="center">
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          <div className="flex items-center justify-between gap-3 flex-wrap pr-6">
            <span>{node?.name} · 监控</span>
            <div className="flex gap-1">
              {RANGES.map((r) => (
                <Button key={r.key} size="sm" variant={range === r.key ? "solid" : "flat"}
                  color={range === r.key ? "primary" : "default"} onPress={() => setRange(r.key)}>
                  {r.label}
                </Button>
              ))}
            </div>
          </div>
        </ModalHeader>
        <ModalBody className="pb-6">
          {loading ? (
            <div className="flex justify-center py-16"><Spinner /></div>
          ) : error ? (
            <div className="text-center text-danger py-12 text-sm">{error}</div>
          ) : points.length === 0 ? (
            <div className="text-center text-default-500 py-12 text-sm leading-relaxed">
              还没有数据。面板升级后开始记录,转发机在线时每分钟存一条,<br />过几分钟再来看。
            </div>
          ) : (
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div className="rounded-xl bg-primary/10 px-3 py-2">
                  <div className="text-xs text-default-500">这段时间 下行</div>
                  <div className="font-semibold">{fmtBytes(totalRx)}</div>
                </div>
                <div className="rounded-xl bg-secondary/10 px-3 py-2">
                  <div className="text-xs text-default-500">这段时间 上行</div>
                  <div className="font-semibold">{fmtBytes(totalTx)}</div>
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <ChartBox title="CPU (%)">
                  <AreaChart data={points}>
                    <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.15} />
                    <XAxis dataKey="t" tick={AXIS} minTickGap={40} />
                    <YAxis tick={AXIS} domain={[0, 100]} width={32} />
                    <Tooltip contentStyle={tip} />
                    <Area type="monotone" dataKey="cpu" name="平均" stroke={C.primary} fill={C.primary} fillOpacity={0.2} dot={false} isAnimationActive={false} />
                    <Area type="monotone" dataKey="cpuMax" name="峰值" stroke={C.warning} fill="none" strokeDasharray="3 3" dot={false} isAnimationActive={false} />
                  </AreaChart>
                </ChartBox>
                <ChartBox title="内存 (%)">
                  <AreaChart data={points}>
                    <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.15} />
                    <XAxis dataKey="t" tick={AXIS} minTickGap={40} />
                    <YAxis tick={AXIS} domain={[0, 100]} width={32} />
                    <Tooltip contentStyle={tip} />
                    <Area type="monotone" dataKey="mem" name="内存" stroke={C.success} fill={C.success} fillOpacity={0.2} dot={false} isAnimationActive={false} />
                  </AreaChart>
                </ChartBox>
                <ChartBox title="网速(平均,每秒)">
                  <AreaChart data={points}>
                    <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.15} />
                    <XAxis dataKey="t" tick={AXIS} minTickGap={40} />
                    <YAxis tick={AXIS} tickFormatter={fmtBytes} width={44} />
                    <Tooltip formatter={(v: any) => fmtBytes(Number(v)) + "/s"} contentStyle={tip} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    <Area type="monotone" dataKey="rxRate" name="下行" stroke={C.primary} fill={C.primary} fillOpacity={0.15} dot={false} isAnimationActive={false} />
                    <Area type="monotone" dataKey="txRate" name="上行" stroke={C.secondary} fill={C.secondary} fillOpacity={0.15} dot={false} isAnimationActive={false} />
                  </AreaChart>
                </ChartBox>
                <ChartBox title={`流量(每${step >= 3600 ? (step === 3600 ? "小时" : ` ${step / 3600} 小时`) : step === 60 ? "分钟" : ` ${step / 60} 分钟`})`}>
                  <BarChart data={points}>
                    <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.15} />
                    <XAxis dataKey="t" tick={AXIS} minTickGap={40} />
                    <YAxis tick={AXIS} tickFormatter={fmtBytes} width={44} />
                    <Tooltip formatter={(v: any) => fmtBytes(Number(v))} contentStyle={tip} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    <Bar dataKey="rx" name="下行" stackId="a" fill={C.primary} isAnimationActive={false} />
                    <Bar dataKey="tx" name="上行" stackId="a" fill={C.secondary} isAnimationActive={false} />
                  </BarChart>
                </ChartBox>
              </div>
            </div>
          )}
          {!loading && probe && (
            <div className="rounded-xl border border-divider bg-content1/60 p-3 mt-1">
              <div className="text-xs font-medium text-default-600 mb-2">延迟(TCP 建连,毫秒)· 每 5 分钟测一次</div>
              {!probe.supported ? (
                <div className="text-xs text-default-500 leading-relaxed py-3">
                  这台转发机的节点程序是 {probe.version || "旧版本"},延迟监测需要 {probe.minVersion} 及以上。
                  在转发机上执行下面这条,选「2. 更新」即可(配置会保留):
                  <code className="block mt-1.5 font-mono text-[11px] break-all bg-default-100 rounded p-2">
                    curl -L https://github.com/Teminuosi/Tms/releases/latest/download/install.sh -o install.sh && bash install.sh
                  </code>
                </div>
              ) : probeTargets.length === 0 ? (
                <div className="text-xs text-default-500 py-3">还没有监测目标,到「监控」页的「延迟目标」里添加。</div>
              ) : probeRows.length === 0 ? (
                <div className="text-xs text-default-500 py-3">还没有数据,节点连上后约 5 分钟出第一批。</div>
              ) : (
                <div className="h-56 w-full text-default-500">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={probeRows}>
                      <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.15} />
                      <XAxis dataKey="t" tick={AXIS} minTickGap={40} />
                      <YAxis tick={AXIS} width={40} />
                      <Tooltip contentStyle={tip}
                        formatter={(v: any, name: any, item: any) => {
                          const id = String(item?.dataKey || "").slice(1);
                          const loss = item?.payload?.["l" + id];
                          return [v == null ? "不通" : `${v} ms${loss ? `(丢包 ${loss}%)` : ""}`, name];
                        }} />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                      {probeTargets.map((t: any, i: number) => (
                        <Line key={t.id} type="monotone" dataKey={"a" + t.id} name={t.name}
                          stroke={LINE_COLORS[i % LINE_COLORS.length]} dot={false} connectNulls={false} isAnimationActive={false} />
                      ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </div>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
}
