package com.admin.monitor;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把节点每 2 秒报一次的系统信息攒成「每分钟一条」存库,再汇总成「每小时一条」。
 *
 * 节点那边一行不用改:CPU/内存/累计收发字节本来就在报,之前只是转给浏览器、没存。
 * 写库只在定时任务里做,收消息的线程只动内存,不拖慢 WebSocket。
 */
@Slf4j
@Component
public class MetricCollector {

    static final long MINUTE = 60_000L;
    static final long HOUR = 3_600_000L;
    static final int GRAN_MIN = 1;
    static final int GRAN_HOUR = 60;

    private static MetricCollector INSTANCE;

    @Resource
    private JdbcTemplate jdbc;

    /** 当前这一分钟还在攒的桶 */
    private final Map<Long, Bucket> open = new ConcurrentHashMap<>();
    /** 已经攒完、等着写库的桶 */
    private final List<Bucket> done = new ArrayList<>();
    /** 每个节点上一次的累计收发字节,用来算差值 */
    private final Map<Long, long[]> lastCounters = new ConcurrentHashMap<>();

    static class Bucket {
        final long nodeId;
        final long ts;
        double cpuSum, cpuMax, memSum;
        long rx, tx;
        int n;

        Bucket(long nodeId, long ts) {
            this.nodeId = nodeId;
            this.ts = ts;
        }
    }

    @PostConstruct
    void init() {
        INSTANCE = this;
    }

    /** WebSocketServer 收到节点系统信息时调用。任何异常都吞掉,不能影响原有的广播 */
    public static void onNodeInfo(Long nodeId, JSONObject info) {
        MetricCollector c = INSTANCE;
        if (c == null || nodeId == null || info == null || !info.containsKey("memory_usage")) {
            return;
        }
        try {
            c.add(nodeId, info, System.currentTimeMillis());
            if (info.containsKey("probe")) {
                ProbeService.onProbeReport(nodeId, info.getJSONObject("probe"));
            }
        } catch (Exception e) {
            log.debug("记录节点指标失败: {}", e.getMessage());
        }
    }

    void add(long nodeId, JSONObject info, long now) {
        double cpu = info.getDoubleValue("cpu_usage");
        double mem = info.getDoubleValue("memory_usage");
        long rx = info.getLongValue("bytes_received");
        long tx = info.getLongValue("bytes_transmitted");

        // 累计值变小 = 节点重启或网卡计数清零,这一次的差值算 0,从新值重新开始算
        long[] last = lastCounters.put(nodeId, new long[]{rx, tx});
        long dRx = 0, dTx = 0;
        if (last != null && rx >= last[0] && tx >= last[1]) {
            dRx = rx - last[0];
            dTx = tx - last[1];
        }

        long minute = now / MINUTE * MINUTE;
        synchronized (this) {
            Bucket b = open.get(nodeId);
            if (b == null || b.ts != minute) {
                if (b != null) {
                    done.add(b);
                }
                b = new Bucket(nodeId, minute);
                open.put(nodeId, b);
            }
            b.cpuSum += cpu;
            b.cpuMax = Math.max(b.cpuMax, cpu);
            b.memSum += mem;
            b.rx += dRx;
            b.tx += dTx;
            b.n++;
        }
    }

    /** 把已经过去的那些分钟写库。节点掉线后它最后那个桶也要靠这里收走 */
    List<Bucket> drain(long now) {
        long minute = now / MINUTE * MINUTE;
        List<Bucket> out;
        synchronized (this) {
            Iterator<Map.Entry<Long, Bucket>> it = open.entrySet().iterator();
            while (it.hasNext()) {
                Bucket b = it.next().getValue();
                if (b.ts < minute) {
                    done.add(b);
                    it.remove();
                }
            }
            out = new ArrayList<>(done);
            done.clear();
        }
        return out;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 60_000)
    public void flush() {
        List<Bucket> list = drain(System.currentTimeMillis());
        if (list.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate(
                    "INSERT INTO monitor_metric (node_id, gran, ts, cpu_avg, cpu_max, mem_avg, rx, tx, samples) " +
                            "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                            // 同一分钟写两次(比如重连前后各攒了一个桶)就合并,不覆盖
                            "cpu_avg=(cpu_avg*samples+VALUES(cpu_avg)*VALUES(samples))/(samples+VALUES(samples))," +
                            "mem_avg=(mem_avg*samples+VALUES(mem_avg)*VALUES(samples))/(samples+VALUES(samples))," +
                            "cpu_max=GREATEST(cpu_max,VALUES(cpu_max)), rx=rx+VALUES(rx), tx=tx+VALUES(tx), samples=samples+VALUES(samples)",
                    list, list.size(), (ps, b) -> {
                        ps.setLong(1, b.nodeId);
                        ps.setInt(2, GRAN_MIN);
                        ps.setLong(3, b.ts);
                        ps.setDouble(4, b.cpuSum / b.n);
                        ps.setDouble(5, b.cpuMax);
                        ps.setDouble(6, b.memSum / b.n);
                        ps.setLong(7, b.rx);
                        ps.setLong(8, b.tx);
                        ps.setInt(9, b.n);
                    });
        } catch (Exception e) {
            // 表没建出来等情况:丢掉这一批,不重试、不堆积内存
            log.warn("写入监控指标失败: {}", e.getMessage());
        }
    }

    /**
     * 每小时把分钟数据汇总成小时数据,再删过期的。
     * 每次都重算最近 3 个小时(幂等覆盖),面板中途重启过也能补上。
     */
    @Scheduled(cron = "0 2 * * * ?")
    public void rollupAndPrune() {
        long now = System.currentTimeMillis();
        long hourStart = now / HOUR * HOUR;
        try {
            jdbc.update(
                    "INSERT INTO monitor_metric (node_id, gran, ts, cpu_avg, cpu_max, mem_avg, rx, tx, samples) " +
                            "SELECT node_id, ?, ts - MOD(ts, ?), SUM(cpu_avg*samples)/SUM(samples), MAX(cpu_max), " +
                            "SUM(mem_avg*samples)/SUM(samples), SUM(rx), SUM(tx), SUM(samples) " +
                            "FROM monitor_metric WHERE gran = ? AND ts >= ? AND ts < ? GROUP BY node_id, ts - MOD(ts, ?) " +
                            "ON DUPLICATE KEY UPDATE cpu_avg=VALUES(cpu_avg), cpu_max=VALUES(cpu_max), mem_avg=VALUES(mem_avg), " +
                            "rx=VALUES(rx), tx=VALUES(tx), samples=VALUES(samples)",
                    GRAN_HOUR, HOUR, GRAN_MIN, hourStart - 3 * HOUR, hourStart, HOUR);
            jdbc.update("DELETE FROM monitor_metric WHERE gran = ? AND ts < ?", GRAN_MIN, now - 7 * 24 * HOUR);
            jdbc.update("DELETE FROM monitor_metric WHERE gran = ? AND ts < ?", GRAN_HOUR, now - 90 * 24 * HOUR);
            // 删掉的转发机,历史数据一起清掉
            jdbc.update("DELETE FROM monitor_metric WHERE node_id NOT IN (SELECT id FROM node)");
        } catch (Exception e) {
            log.warn("监控数据汇总/清理失败: {}", e.getMessage());
        }
    }
}
