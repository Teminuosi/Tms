import { useEffect, useState } from "react";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Progress } from "@heroui/progress";
import { Spinner } from "@heroui/spinner";
import Network from "@/api/network";
import { fmtBytes, latencyColor } from "@/pages/monitor";

// 公开状态页:不用登录。面板里没打开时只显示一句「未开启」,不泄露任何东西
export default function StatusPage() {
  const [data, setData] = useState<any>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = () =>
      Network.get("/open_api/status")
        .then((r: any) => { if (!alive) return; if (r.code === 0) { setData(r.data); setFailed(false); } else setFailed(true); })
        .catch(() => alive && setFailed(true));
    load();
    const t = setInterval(load, 30000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  useEffect(() => {
    if (data?.title) document.title = data.title;
  }, [data?.title]);

  if (!data && !failed) return <div className="min-h-screen flex items-center justify-center"><Spinner /></div>;
  if (failed && !data) return <div className="min-h-screen flex items-center justify-center text-default-500 text-sm">暂时打不开,稍后再试</div>;
  if (!data.enabled) return <div className="min-h-screen flex items-center justify-center text-default-500 text-sm">状态页未开启</div>;

  const nodes: any[] = data.nodes || [];
  const online = nodes.filter((n) => n.online).length;

  return (
    <div className="min-h-screen px-4 py-8 max-w-6xl mx-auto">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">{data.title}</h1>
        <p className="text-sm text-default-500 mt-1">
          {online} / {nodes.length} 在线 · 每 30 秒自动刷新 · 更新于 {new Date(data.updatedAt).toLocaleTimeString()}
        </p>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {nodes.map((n) => (
          <Card key={n.id} className="border border-divider">
            <CardBody className="space-y-2.5 text-xs">
              <div className="flex justify-between items-center gap-2">
                <span className="font-semibold text-sm truncate">{n.name}</span>
                <Chip size="sm" variant="flat" color={n.online ? "success" : "danger"}>{n.online ? "在线" : "离线"}</Chip>
              </div>
              {n.online && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <div className="flex justify-between mb-1"><span className="text-default-500">CPU</span><span>{n.cpu == null ? "-" : `${n.cpu.toFixed(1)}%`}</span></div>
                      <Progress size="sm" value={n.cpu || 0} color="primary" aria-label="CPU" />
                    </div>
                    <div>
                      <div className="flex justify-between mb-1"><span className="text-default-500">内存</span><span>{n.mem == null ? "-" : `${n.mem.toFixed(1)}%`}</span></div>
                      <Progress size="sm" value={n.mem || 0} color="success" aria-label="内存" />
                    </div>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-default-500">网速</span>
                    <span>↓ {fmtBytes(n.rxRate)}/s · ↑ {fmtBytes(n.txRate)}/s</span>
                  </div>
                  {(n.probes || []).length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {n.probes.map((p: any) => (
                        <Chip key={p.targetId} size="sm" variant="flat" color={latencyColor(p.avg, p.loss)}>
                          {p.name} {p.avg == null ? "不通" : `${Math.round(p.avg)}ms`}
                        </Chip>
                      ))}
                    </div>
                  )}
                </>
              )}
            </CardBody>
          </Card>
        ))}
      </div>
    </div>
  );
}
