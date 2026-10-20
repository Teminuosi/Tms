package com.admin.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/** 总览数据:管理员的监控页和公开状态页共用 */
@Component
public class OverviewService {

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private NodeUsage nodeUsage;

    /** 状态页也用这份数据,publicOnly=true 时去掉 IP、到期、额度这些不该公开的 */
    public List<Map<String, Object>> build(boolean publicOnly) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "SELECT n.id, n.name, n.server_ip serverIp, n.status, n.version, m.expire_date expireDate, m.quota_gb quotaGb, " +
                        "COALESCE(m.reset_day,1) resetDay, COALESCE(m.traffic_mode,'both') trafficMode, " +
                        "COALESCE(m.public_show,1) publicShow, m.public_name publicName " +
                        "FROM node n LEFT JOIN monitor_node_meta m ON m.node_id = n.id ORDER BY n.id");
        Map<Long, Map<String, Object>> latest = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT x.node_id, x.cpu_avg cpu, x.mem_avg mem, x.rx DIV 60 rxRate, x.tx DIV 60 txRate FROM monitor_metric x " +
                        "JOIN (SELECT node_id, MAX(ts) ts FROM monitor_metric WHERE gran = 1 AND ts >= ? GROUP BY node_id) y " +
                        "ON x.node_id = y.node_id AND x.ts = y.ts AND x.gran = 1", now - 3 * 60_000L)) {
            latest.put(((Number) r.get("node_id")).longValue(), r);
        }
        Map<Long, List<Map<String, Object>>> probes = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT p.node_id, t.id targetId, t.name, ROUND(p.avg_ms,1) avg, ROUND(p.loss,1) loss FROM monitor_probe p " +
                        "JOIN (SELECT node_id, target_id, MAX(ts) ts FROM monitor_probe WHERE gran = 1 AND ts >= ? GROUP BY node_id, target_id) q " +
                        "ON p.node_id = q.node_id AND p.target_id = q.target_id AND p.ts = q.ts AND p.gran = 1 " +
                        "JOIN monitor_probe_target t ON t.id = p.target_id ORDER BY t.sort, t.id", now - 15 * 60_000L)) {
            probes.computeIfAbsent(((Number) r.get("node_id")).longValue(), k -> new java.util.ArrayList<>()).add(r);
        }
        Map<Long, Long> since = new java.util.HashMap<>();
        if (!publicOnly) {
            for (Map<String, Object> n : nodes) {
                since.put(((Number) n.get("id")).longValue(), NodeUsage.cycleStart(((Number) n.get("resetDay")).intValue()));
            }
        }
        Map<Long, long[]> used = nodeUsage.usageSince(since);

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> n : nodes) {
            long id = ((Number) n.get("id")).longValue();
            if (publicOnly && ((Number) n.get("publicShow")).intValue() == 0) {
                continue;
            }
            Map<String, Object> o = new java.util.LinkedHashMap<>();
            o.put("id", id);
            Object pn = n.get("publicName");
            o.put("name", publicOnly && pn != null ? pn : n.get("name"));
            o.put("online", n.get("status") != null && ((Number) n.get("status")).intValue() == 1);
            Map<String, Object> l = latest.get(id);
            o.put("cpu", l == null ? null : l.get("cpu"));
            o.put("mem", l == null ? null : l.get("mem"));
            o.put("rxRate", l == null ? null : l.get("rxRate"));
            o.put("txRate", l == null ? null : l.get("txRate"));
            o.put("probes", probes.getOrDefault(id, List.of()));
            if (!publicOnly) {
                o.put("serverIp", n.get("serverIp"));
                o.put("version", n.get("version"));
                o.put("probeSupported", ProbeService.supports(n.get("version") == null ? null : n.get("version").toString()));
                o.put("expireDate", n.get("expireDate") == null ? null : n.get("expireDate").toString());
                o.put("quotaGb", n.get("quotaGb"));
                o.put("resetDay", n.get("resetDay"));
                o.put("trafficMode", n.get("trafficMode"));
                o.put("publicShow", ((Number) n.get("publicShow")).intValue() == 1);
                o.put("publicName", pn);
                long[] u = used.getOrDefault(id, new long[]{0, 0});
                o.put("cycleRx", u[0]);
                o.put("cycleTx", u[1]);
                o.put("cycleUsed", NodeUsage.counted(u[0], u[1], String.valueOf(n.get("trafficMode"))));
            }
            out.add(o);
        }
        return out;
    }
}
