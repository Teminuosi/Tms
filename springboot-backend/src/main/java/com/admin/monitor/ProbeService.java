package com.admin.monitor;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 延迟 / 丢包监测。
 *
 * 测是节点自己在旁路测的(go-gost/x/socket/probe.go),面板这边只做两件事:
 * 节点连上来时把目标列表下发给它;收到结果存库。
 *
 * 只给 1.2.5 及以上的节点下发 —— 老节点不认识 SetProbes,
 * 而且不能退回去用 TcpPing 命令定时测:老节点处理命令是串行的,
 * 定时测会把车主的正常操作挤到超时。老节点就是「升级节点后可用」。
 */
@Slf4j
@Component
public class ProbeService {

    static final String MIN_AGENT_VERSION = "1.2.5";
    static final int INTERVAL_SECONDS = 300;

    private static ProbeService INSTANCE;

    @Resource
    private JdbcTemplate jdbc;

    /** 下发配置走 send_msg,最多阻塞 10 秒,放到单独线程,不卡 WebSocket 建连 */
    private final ExecutorService pusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "probe-push");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        INSTANCE = this;
    }

    public static boolean supports(String version) {
        return version != null && compareVersion(version, MIN_AGENT_VERSION) >= 0;
    }

    static int compareVersion(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    /** WebSocketServer 在节点连上来时调用 */
    public static void onNodeConnected(Long nodeId, String version) {
        ProbeService s = INSTANCE;
        if (s == null || nodeId == null || !supports(version)) {
            return;
        }
        s.pusher.submit(() -> s.push(nodeId));
    }

    /** 目标列表改了之后,推给所有在线、版本够的节点 */
    public void pushAllOnline() {
        try {
            List<Map<String, Object>> nodes = jdbc.queryForList("SELECT id, version FROM node WHERE status = 1");
            for (Map<String, Object> n : nodes) {
                Object v = n.get("version");
                if (supports(v == null ? null : v.toString())) {
                    long id = ((Number) n.get("id")).longValue();
                    pusher.submit(() -> push(id));
                }
            }
        } catch (Exception e) {
            log.warn("下发延迟监测配置失败: {}", e.getMessage());
        }
    }

    void push(long nodeId) {
        try {
            JSONArray targets = new JSONArray();
            for (Map<String, Object> t : jdbc.queryForList(
                    "SELECT id, host, port FROM monitor_probe_target WHERE enabled = 1 ORDER BY sort, id")) {
                targets.add(new JSONObject(t));
            }
            JSONObject cfg = new JSONObject();
            cfg.put("targets", targets);
            cfg.put("interval", INTERVAL_SECONDS);
            cfg.put("count", 4);
            cfg.put("timeout", 2000);
            GostDto r = WebSocketServer.send_msg(nodeId, cfg, "SetProbes");
            if (r == null || !"OK".equals(r.getMsg())) {
                log.info("节点 {} 延迟监测配置未生效: {}", nodeId, r == null ? "无响应" : r.getMsg());
            }
        } catch (Exception e) {
            log.info("节点 {} 下发延迟监测配置失败: {}", nodeId, e.getMessage());
        }
    }

    /** 节点系统信息里带回来的一轮结果 */
    public static void onProbeReport(Long nodeId, JSONObject probe) {
        ProbeService s = INSTANCE;
        if (s == null || nodeId == null || probe == null) {
            return;
        }
        try {
            long ts = probe.getLongValue("ts") / MetricCollector.MINUTE * MetricCollector.MINUTE;
            JSONArray results = probe.getJSONArray("results");
            if (ts <= 0 || results == null || results.isEmpty()) {
                return;
            }
            s.jdbc.batchUpdate(
                    "INSERT INTO monitor_probe (node_id, target_id, gran, ts, avg_ms, loss, samples) VALUES (?,?,?,?,?,?,1) " +
                            "ON DUPLICATE KEY UPDATE avg_ms=VALUES(avg_ms), loss=VALUES(loss)",
                    results, results.size(), (ps, o) -> {
                        JSONObject r = (JSONObject) o;
                        double avg = r.getDoubleValue("avg");
                        ps.setLong(1, nodeId);
                        ps.setInt(2, r.getIntValue("id"));
                        ps.setInt(3, MetricCollector.GRAN_MIN);
                        ps.setLong(4, ts);
                        if (avg < 0) {
                            ps.setNull(5, java.sql.Types.FLOAT);
                        } else {
                            ps.setDouble(5, avg);
                        }
                        ps.setDouble(6, r.getDoubleValue("loss"));
                    });
        } catch (Exception e) {
            log.debug("保存延迟监测结果失败: {}", e.getMessage());
        }
    }

    /** 跟指标一样:每小时汇总一次、删过期的(明细 7 天,小时 90 天) */
    @Scheduled(cron = "0 3 * * * ?")
    public void rollupAndPrune() {
        long now = System.currentTimeMillis();
        long H = MetricCollector.HOUR;
        long hourStart = now / H * H;
        try {
            jdbc.update(
                    "INSERT INTO monitor_probe (node_id, target_id, gran, ts, avg_ms, loss, samples) " +
                            "SELECT node_id, target_id, ?, ts - MOD(ts, ?), AVG(avg_ms), AVG(loss), COUNT(*) " +
                            "FROM monitor_probe WHERE gran = ? AND ts >= ? AND ts < ? GROUP BY node_id, target_id, ts - MOD(ts, ?) " +
                            "ON DUPLICATE KEY UPDATE avg_ms=VALUES(avg_ms), loss=VALUES(loss), samples=VALUES(samples)",
                    MetricCollector.GRAN_HOUR, H, MetricCollector.GRAN_MIN, hourStart - 3 * H, hourStart, H);
            jdbc.update("DELETE FROM monitor_probe WHERE gran = ? AND ts < ?", MetricCollector.GRAN_MIN, now - 7 * 24 * H);
            jdbc.update("DELETE FROM monitor_probe WHERE gran = ? AND ts < ?", MetricCollector.GRAN_HOUR, now - 90 * 24 * H);
            jdbc.update("DELETE FROM monitor_probe WHERE node_id NOT IN (SELECT id FROM node)");
            jdbc.update("DELETE FROM monitor_probe WHERE target_id NOT IN (SELECT id FROM monitor_probe_target)");
        } catch (Exception e) {
            log.warn("延迟数据汇总/清理失败: {}", e.getMessage());
        }
    }
}
