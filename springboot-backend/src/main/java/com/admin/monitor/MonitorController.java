package com.admin.monitor;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/** 监控:历史曲线等。只给管理员(@RequireRole) */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    @Resource
    private JdbcTemplate jdbc;

    /**
     * 某台转发机的历史指标。range: 1h / 24h 用分钟数据,7d / 30d 用小时数据。
     * 返回的 rxRate/txRate 是这一段的平均速度(字节/秒),rx/tx 是这一段的总字节数。
     */
    @RequireRole
    @PostMapping("/metrics")
    public R metrics(@RequestBody Map<String, Object> params) {
        Object idObj = params.get("nodeId");
        if (idObj == null) {
            return R.err("缺少 nodeId");
        }
        long nodeId = Long.parseLong(idObj.toString());
        String range = String.valueOf(params.getOrDefault("range", "24h"));

        Window w = Window.of(range);
        // 按「点」聚合,别把 1440 个分钟点全丢给前端:画出来密得看不清
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ts - MOD(ts, ?) ts, ROUND(SUM(cpu_avg*samples)/SUM(samples),1) cpu, ROUND(MAX(cpu_max),1) cpuMax, " +
                        "ROUND(SUM(mem_avg*samples)/SUM(samples),1) mem, SUM(rx) rx, SUM(tx) tx, " +
                        "SUM(rx) DIV ? rxRate, SUM(tx) DIV ? txRate FROM monitor_metric " +
                        "WHERE node_id = ? AND gran = ? AND ts >= ? GROUP BY ts - MOD(ts, ?) ORDER BY 1",
                w.stepMs, w.stepMs / 1000, w.stepMs / 1000, nodeId, w.gran, System.currentTimeMillis() - w.spanMs, w.stepMs);
        return R.ok(Map.of("range", w.range, "stepSeconds", w.stepMs / 1000, "points", rows));
    }

    /** 时间范围 → 用哪种粒度的原始数据、每个点多宽 */
    static class Window {
        final String range;
        final long spanMs, stepMs;
        final int gran;

        Window(String range, long spanMs, int gran, long stepMs) {
            this.range = range;
            this.spanMs = spanMs;
            this.gran = gran;
            this.stepMs = stepMs;
        }

        static Window of(String range) {
            long H = MetricCollector.HOUR, M = MetricCollector.MINUTE;
            switch (range) {
                case "1h": return new Window("1h", H, MetricCollector.GRAN_MIN, M);                 // 60 点
                case "7d": return new Window("7d", 7 * 24 * H, MetricCollector.GRAN_HOUR, H);       // 168 点
                case "30d": return new Window("30d", 30 * 24 * H, MetricCollector.GRAN_HOUR, 4 * H); // 180 点
                default: return new Window("24h", 24 * H, MetricCollector.GRAN_MIN, 5 * M);         // 288 点
            }
        }
    }

    @Resource
    private ProbeService probeService;

    /** 某台转发机到各目标的延迟/丢包。supported=false 表示节点版本太老,还不能测 */
    @RequireRole
    @PostMapping("/probes")
    public R probes(@RequestBody Map<String, Object> params) {
        Object idObj = params.get("nodeId");
        if (idObj == null) {
            return R.err("缺少 nodeId");
        }
        long nodeId = Long.parseLong(idObj.toString());
        String range = String.valueOf(params.getOrDefault("range", "24h"));
        // 延迟本来就 5 分钟一个点,1h/24h 不用再聚合
        Window w = Window.of(range);
        long step = w.gran == MetricCollector.GRAN_MIN ? MetricCollector.MINUTE : w.stepMs;
        List<Map<String, Object>> nodeRows = jdbc.queryForList("SELECT version FROM node WHERE id = ?", nodeId);
        String version = nodeRows.isEmpty() || nodeRows.get(0).get("version") == null ? null : nodeRows.get(0).get("version").toString();

        List<Map<String, Object>> targets = jdbc.queryForList(
                "SELECT id, name FROM monitor_probe_target ORDER BY sort, id");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT target_id targetId, ts - MOD(ts, ?) ts, ROUND(AVG(avg_ms),1) avg, ROUND(AVG(loss),1) loss FROM monitor_probe " +
                        "WHERE node_id = ? AND gran = ? AND ts >= ? GROUP BY target_id, ts - MOD(ts, ?) ORDER BY 2",
                step, nodeId, w.gran, System.currentTimeMillis() - w.spanMs, step);
        return R.ok(Map.of(
                "supported", ProbeService.supports(version),
                "minVersion", ProbeService.MIN_AGENT_VERSION,
                "version", version == null ? "" : version,
                "targets", targets,
                "points", rows));
    }

    @Resource
    private MonitorSettings settings;
    @Resource
    private AlertService alertService;
    @Resource
    private OverviewService overviewService;

    /** 设置。Bot Token 不回传原文,只告诉前端「填过了」 */
    @RequireRole
    @PostMapping("/settings/get")
    public R getSettings() {
        Map<String, String> s = new java.util.HashMap<>(settings.all());
        String token = s.getOrDefault(MonitorSettings.TG_TOKEN, "");
        s.put(MonitorSettings.TG_TOKEN, "");
        s.put("tg_token_set", token.isBlank() ? "0" : "1");
        s.keySet().removeIf(k -> k.startsWith("traffic_alerted_") || k.equals("probe_seeded"));
        return R.ok(s);
    }

    @RequireRole
    @PostMapping("/settings/save")
    public R saveSettings(@RequestBody Map<String, Object> p) {
        String[] keys = {MonitorSettings.TG_CHAT, MonitorSettings.OFFLINE_MIN, MonitorSettings.EXPIRE_DAYS,
                MonitorSettings.TRAFFIC_ALERT, MonitorSettings.STATUS_ENABLED, MonitorSettings.STATUS_TITLE};
        for (String k : keys) {
            if (p.containsKey(k)) {
                String v = p.get(k) == null ? "" : p.get(k).toString().trim();
                if (v.length() > 200) {
                    return R.err("内容太长");
                }
                settings.set(k, v);
            }
        }
        // Token 留空 = 不改;要清掉得明确传 clear_tg_token
        Object token = p.get(MonitorSettings.TG_TOKEN);
        if (token != null && !token.toString().isBlank()) {
            settings.set(MonitorSettings.TG_TOKEN, token.toString().trim());
        }
        if (Boolean.TRUE.equals(p.get("clear_tg_token"))) {
            settings.set(MonitorSettings.TG_TOKEN, "");
        }
        return R.ok();
    }

    @RequireRole
    @PostMapping("/settings/test")
    public R testTelegram() {
        String err = alertService.sendTelegram("✅ TMS 告警测试:能收到这条,说明 Telegram 告警配置好了");
        return err == null ? R.ok() : R.err(err);
    }

    /** 转发机的到期日 / 流量额度 / 状态页显示 */
    @RequireRole
    @PostMapping("/meta/save")
    public R saveMeta(@RequestBody Map<String, Object> p) {
        long nodeId = Long.parseLong(String.valueOf(p.get("nodeId")));
        String exp = p.get("expireDate") == null ? "" : p.get("expireDate").toString().trim();
        java.sql.Date expDate = null;
        if (!exp.isEmpty()) {
            try {
                expDate = java.sql.Date.valueOf(java.time.LocalDate.parse(exp));
            } catch (Exception e) {
                return R.err("到期日格式应为 2026-12-31");
            }
        }
        Integer quota = null;
        String q = p.get("quotaGb") == null ? "" : p.get("quotaGb").toString().trim();
        if (!q.isEmpty()) {
            try {
                quota = Integer.parseInt(q);
            } catch (Exception e) {
                return R.err("流量额度要填整数(GB)");
            }
            if (quota <= 0) {
                quota = null;
            }
        }
        int resetDay;
        try {
            resetDay = Integer.parseInt(String.valueOf(p.getOrDefault("resetDay", "1")));
        } catch (Exception e) {
            resetDay = 1;
        }
        resetDay = Math.max(1, Math.min(31, resetDay));
        String mode = String.valueOf(p.getOrDefault("trafficMode", "both"));
        if (!mode.equals("out") && !mode.equals("max")) {
            mode = "both";
        }
        int show = Boolean.FALSE.equals(p.get("publicShow")) ? 0 : 1;
        String publicName = p.get("publicName") == null ? null : p.get("publicName").toString().trim();
        if (publicName != null && (publicName.isEmpty() || publicName.length() > 64)) {
            publicName = publicName.isEmpty() ? null : publicName.substring(0, 64);
        }
        jdbc.update("INSERT INTO monitor_node_meta (node_id, expire_date, quota_gb, reset_day, traffic_mode, public_show, public_name) " +
                        "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE expire_date=VALUES(expire_date), quota_gb=VALUES(quota_gb), " +
                        "reset_day=VALUES(reset_day), traffic_mode=VALUES(traffic_mode), public_show=VALUES(public_show), public_name=VALUES(public_name)",
                nodeId, expDate, quota, resetDay, mode, show, publicName);
        return R.ok();
    }

    /**
     * 总览:每台转发机的最新指标、本周期流量、到期、各目标最新延迟。
     * 「最新」取的是最近 3 分钟里最后一条分钟数据。
     */
    @RequireRole
    @PostMapping("/overview")
    public R overview() {
        return R.ok(overviewService.build(false));
    }

    @RequireRole
    @PostMapping("/targets/list")
    public R targets() {
        return R.ok(jdbc.queryForList(
                "SELECT id, name, host, port, enabled, sort FROM monitor_probe_target ORDER BY sort, id"));
    }

    @RequireRole
    @PostMapping("/targets/save")
    public R saveTarget(@RequestBody Map<String, Object> p) {
        String name = p.get("name") == null ? "" : p.get("name").toString().trim();
        String host = p.get("host") == null ? "" : p.get("host").toString().trim();
        int port;
        try {
            port = Integer.parseInt(String.valueOf(p.get("port")));
        } catch (Exception e) {
            return R.err("端口不对");
        }
        if (name.isEmpty() || name.length() > 64) {
            return R.err("名称不能为空,且不超过 64 个字");
        }
        if (!host.matches("^[A-Za-z0-9.:\\-\\[\\]]{1,255}$")) {
            return R.err("地址只能是 IP 或域名");
        }
        if (port < 1 || port > 65535) {
            return R.err("端口范围 1-65535");
        }
        int enabled = Boolean.FALSE.equals(p.get("enabled")) || "0".equals(String.valueOf(p.get("enabled"))) ? 0 : 1;
        Object id = p.get("id");
        if (id == null || id.toString().isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM monitor_probe_target", Integer.class);
            if (count != null && count >= 20) {
                return R.err("最多 20 个目标");
            }
            jdbc.update("INSERT INTO monitor_probe_target (name, host, port, enabled, sort) VALUES (?,?,?,?,?)",
                    name, host, port, enabled, count == null ? 0 : count + 1);
        } else {
            jdbc.update("UPDATE monitor_probe_target SET name=?, host=?, port=?, enabled=? WHERE id=?",
                    name, host, port, enabled, Long.parseLong(id.toString()));
        }
        probeService.pushAllOnline();
        return R.ok();
    }

    @RequireRole
    @PostMapping("/targets/delete")
    public R deleteTarget(@RequestBody Map<String, Object> p) {
        long id = Long.parseLong(String.valueOf(p.get("id")));
        jdbc.update("DELETE FROM monitor_probe_target WHERE id = ?", id);
        jdbc.update("DELETE FROM monitor_probe WHERE target_id = ?", id);
        probeService.pushAllOnline();
        return R.ok();
    }
}
