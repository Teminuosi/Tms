package com.admin.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** monitor_setting 表的读写。没配过的项一律返回默认值 —— 默认就是「全关」 */
@Component
public class MonitorSettings {

    public static final String TG_TOKEN = "tg_token";
    public static final String TG_CHAT = "tg_chat";
    /** 掉线多少分钟才报,0 = 不报 */
    public static final String OFFLINE_MIN = "alert_offline_min";
    /** 到期前几天开始每天提醒,0 = 不报 */
    public static final String EXPIRE_DAYS = "alert_expire_days";
    /** 流量额度用到 80% / 100% 时提醒,1 = 开 */
    public static final String TRAFFIC_ALERT = "alert_traffic";
    public static final String STATUS_ENABLED = "status_enabled";
    public static final String STATUS_TITLE = "status_title";

    private static final Map<String, String> DEFAULTS = Map.of(
            OFFLINE_MIN, "3",
            EXPIRE_DAYS, "3",
            TRAFFIC_ALERT, "1",
            STATUS_ENABLED, "0",
            STATUS_TITLE, "节点状态");

    @Resource
    private JdbcTemplate jdbc;

    public Map<String, String> all() {
        Map<String, String> m = new HashMap<>(DEFAULTS);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT k, v FROM monitor_setting");
            for (Map<String, Object> r : rows) {
                m.put(String.valueOf(r.get("k")), r.get("v") == null ? "" : r.get("v").toString());
            }
        } catch (Exception ignored) {
            // 表还没建出来:按默认值(全关)处理
        }
        return m;
    }

    public String get(String k) {
        return all().getOrDefault(k, "");
    }

    public int getInt(String k) {
        try {
            return Integer.parseInt(get(k).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void set(String k, String v) {
        jdbc.update("INSERT INTO monitor_setting (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)", k, v);
    }

    /** Telegram 两项都填了才算开启告警 */
    public boolean telegramReady() {
        Map<String, String> m = all();
        return !m.getOrDefault(TG_TOKEN, "").isBlank() && !m.getOrDefault(TG_CHAT, "").isBlank();
    }
}
