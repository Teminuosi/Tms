package com.admin.monitor;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警:掉线 / 恢复、VPS 快到期、月流量快用完,推到 Telegram。
 * 没填 Bot Token 和 Chat ID 就什么都不做 —— 老用户升级后默认是关的。
 */
@Slf4j
@Component
public class AlertService {

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private MonitorSettings settings;
    @Resource
    private NodeUsage usage;

    private final long startedAt = System.currentTimeMillis();
    /** 节点第一次被看到离线的时间 */
    private final Map<Long, Long> offlineSince = new ConcurrentHashMap<>();
    /** 已经报过离线的节点(恢复时要报一声) */
    private final Map<Long, Boolean> offlineAlerted = new ConcurrentHashMap<>();

    /** 发一条 Telegram 消息。返回 null = 成功,否则是失败原因 */
    public String sendTelegram(String text) {
        Map<String, String> s = settings.all();
        String token = s.getOrDefault(MonitorSettings.TG_TOKEN, "").trim();
        String chat = s.getOrDefault(MonitorSettings.TG_CHAT, "").trim();
        if (token.isEmpty() || chat.isEmpty()) {
            return "没填 Bot Token 或 Chat ID";
        }
        JSONObject body = new JSONObject();
        body.put("chat_id", chat);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        try (HttpResponse r = HttpRequest.post("https://api.telegram.org/bot" + token + "/sendMessage")
                .body(body.toJSONString(), "application/json")
                .timeout(10_000)
                .execute()) {
            if (r.isOk()) {
                return null;
            }
            JSONObject err = JSONObject.parseObject(r.body());
            return err != null && err.getString("description") != null ? err.getString("description") : ("HTTP " + r.getStatus());
        } catch (Exception e) {
            return "连不上 Telegram:" + e.getMessage();
        }
    }

    private void notify(String text) {
        String err = sendTelegram(text);
        if (err != null) {
            log.warn("告警发送失败: {}", err);
        }
    }

    /** 掉线 / 恢复。node.status 由 WebSocket 连上/断开时维护,这里只轮询它 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void checkOffline() {
        int minutes = settings.getInt(MonitorSettings.OFFLINE_MIN);
        if (minutes <= 0 || !settings.telegramReady()) {
            offlineSince.clear();
            offlineAlerted.clear();
            return;
        }
        long now = System.currentTimeMillis();
        final List<Map<String, Object>> nodes;
        try {
            nodes = jdbc.queryForList("SELECT id, name, status FROM node");
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> n : nodes) {
            long id = ((Number) n.get("id")).longValue();
            String name = String.valueOf(n.get("name"));
            boolean online = n.get("status") != null && ((Number) n.get("status")).intValue() == 1;
            if (online) {
                Long since = offlineSince.remove(id);
                if (offlineAlerted.remove(id) != null) {
                    long mins = since == null ? 0 : (now - since) / 60_000;
                    notify("🟢 转发机「" + name + "」恢复在线(离线约 " + mins + " 分钟)");
                }
                continue;
            }
            offlineSince.putIfAbsent(id, now);
            // 面板自己刚重启时所有节点都要几秒才连回来,前 5 分钟不报,免得一重启就刷屏
            if (now - startedAt < 5 * 60_000L) {
                continue;
            }
            if (!offlineAlerted.containsKey(id) && now - offlineSince.get(id) >= minutes * 60_000L) {
                offlineAlerted.put(id, true);
                notify("🔴 转发机「" + name + "」已离线超过 " + minutes + " 分钟");
            }
        }
        // 删掉的节点
        offlineSince.keySet().removeIf(id -> nodes.stream().noneMatch(n -> ((Number) n.get("id")).longValue() == id));
        offlineAlerted.keySet().removeIf(id -> nodes.stream().noneMatch(n -> ((Number) n.get("id")).longValue() == id));
    }

    /** 到期提醒:每天上午 10 点,到期前 N 天起每天一条,到期当天再一条 */
    @Scheduled(cron = "0 0 10 * * ?")
    public void checkExpiry() {
        int days = settings.getInt(MonitorSettings.EXPIRE_DAYS);
        if (days <= 0 || !settings.telegramReady()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT n.name, m.expire_date FROM monitor_node_meta m JOIN node n ON n.id = m.node_id " +
                            "WHERE m.expire_date IS NOT NULL");
            LocalDate today = LocalDate.now();
            for (Map<String, Object> r : rows) {
                LocalDate exp = ((java.sql.Date) r.get("expire_date")).toLocalDate();
                long left = ChronoUnit.DAYS.between(today, exp);
                if (left < 0 || left > days) {
                    continue;
                }
                notify(left == 0
                        ? "⏰ 转发机「" + r.get("name") + "」今天到期(" + exp + "),记得续费"
                        : "⏰ 转发机「" + r.get("name") + "」还有 " + left + " 天到期(" + exp + ")");
            }
        } catch (Exception e) {
            log.warn("到期检查失败: {}", e.getMessage());
        }
    }

    /** 流量提醒:用到 80% 报一次,用完报一次,每个周期各一次(记在 monitor_setting 里,重启不重复报) */
    @Scheduled(fixedDelay = 10 * 60_000, initialDelay = 120_000)
    public void checkTraffic() {
        if (settings.getInt(MonitorSettings.TRAFFIC_ALERT) != 1 || !settings.telegramReady()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT m.node_id, n.name, m.quota_gb, m.reset_day, m.traffic_mode FROM monitor_node_meta m " +
                            "JOIN node n ON n.id = m.node_id WHERE m.quota_gb IS NOT NULL AND m.quota_gb > 0");
            Map<Long, Long> since = new HashMap<>();
            for (Map<String, Object> r : rows) {
                since.put(((Number) r.get("node_id")).longValue(), NodeUsage.cycleStart(((Number) r.get("reset_day")).intValue()));
            }
            Map<Long, long[]> used = usage.usageSince(since);
            Map<String, String> s = settings.all();
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("node_id")).longValue();
                long[] u = used.getOrDefault(id, new long[]{0, 0});
                long quota = ((Number) r.get("quota_gb")).longValue() * 1024L * 1024 * 1024;
                double pct = NodeUsage.counted(u[0], u[1], String.valueOf(r.get("traffic_mode"))) * 100.0 / quota;
                int level = pct >= 100 ? 100 : pct >= 80 ? 80 : 0;
                if (level == 0) {
                    continue;
                }
                String key = "traffic_alerted_" + id;
                String mark = since.get(id) + ":" + level;
                String prev = s.getOrDefault(key, "");
                // 同一周期里报过同级或更高级的就不再报
                if (prev.startsWith(since.get(id) + ":") && Integer.parseInt(prev.substring(prev.indexOf(':') + 1)) >= level) {
                    continue;
                }
                settings.set(key, mark);
                notify((level == 100 ? "🚫" : "⚠️") + " 转发机「" + r.get("name") + "」本月流量已用 "
                        + String.format("%.0f%%", pct) + "(额度 " + r.get("quota_gb") + " GB)");
            }
        } catch (Exception e) {
            log.warn("流量检查失败: {}", e.getMessage());
        }
    }
}
