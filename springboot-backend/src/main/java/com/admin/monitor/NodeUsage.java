package com.admin.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转发机本流量周期(从每月「重置日」算起)用了多少。
 *
 * 数据来自面板自己记的 monitor_metric,所以只从面板升级、开始记录之后才有;
 * 跟 VPS 商家后台的数字会有出入(商家可能按网卡、按方向、按 1000 还是 1024 算)。
 */
@Component
public class NodeUsage {

    @Resource
    private JdbcTemplate jdbc;

    /** 本周期的起点:今天 >= 重置日 → 本月重置日;否则上个月重置日。重置日超过当月天数按月末算 */
    static long cycleStart(int resetDay, ZonedDateTime now) {
        int day = Math.max(1, Math.min(resetDay, 31));
        LocalDate today = now.toLocalDate();
        LocalDate thisMonth = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));
        LocalDate start;
        if (!today.isBefore(thisMonth)) {
            start = thisMonth;
        } else {
            LocalDate prev = today.minusMonths(1);
            start = prev.withDayOfMonth(Math.min(day, prev.lengthOfMonth()));
        }
        return start.atStartOfDay(now.getZone()).toInstant().toEpochMilli();
    }

    public static long cycleStart(int resetDay) {
        return cycleStart(resetDay, ZonedDateTime.now(ZoneId.systemDefault()));
    }

    /**
     * 每个节点从 since 到现在的收/发字节:{nodeId: [rx, tx]}。
     * 分钟数据只留 7 天,所以 6 天前的部分用小时数据、之后用分钟数据,两段不重叠。
     */
    public Map<Long, long[]> usageSince(Map<Long, Long> sinceByNode) {
        Map<Long, long[]> out = new HashMap<>();
        if (sinceByNode.isEmpty()) {
            return out;
        }
        long now = System.currentTimeMillis();
        long boundary = (now - 6 * 24 * MetricCollector.HOUR) / MetricCollector.HOUR * MetricCollector.HOUR;
        for (Map.Entry<Long, Long> e : sinceByNode.entrySet()) {
            long node = e.getKey();
            long since = e.getValue();
            long rx = 0, tx = 0;
            if (since < boundary) {
                List<Map<String, Object>> r = jdbc.queryForList(
                        "SELECT COALESCE(SUM(rx),0) rx, COALESCE(SUM(tx),0) tx FROM monitor_metric " +
                                "WHERE node_id = ? AND gran = ? AND ts >= ? AND ts < ?",
                        node, MetricCollector.GRAN_HOUR, since, boundary);
                rx += ((Number) r.get(0).get("rx")).longValue();
                tx += ((Number) r.get(0).get("tx")).longValue();
            }
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT COALESCE(SUM(rx),0) rx, COALESCE(SUM(tx),0) tx FROM monitor_metric " +
                            "WHERE node_id = ? AND gran = ? AND ts >= ?",
                    node, MetricCollector.GRAN_MIN, Math.max(since, boundary));
            rx += ((Number) r.get(0).get("rx")).longValue();
            tx += ((Number) r.get(0).get("tx")).longValue();
            out.put(node, new long[]{rx, tx});
        }
        return out;
    }

    /** 按 traffic_mode 算「计入额度」的字节数 */
    public static long counted(long rx, long tx, String mode) {
        if ("out".equals(mode)) {
            return tx;
        }
        if ("max".equals(mode)) {
            return Math.max(rx, tx);
        }
        return rx + tx;
    }
}
