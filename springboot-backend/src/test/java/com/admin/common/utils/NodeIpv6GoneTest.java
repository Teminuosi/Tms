package com.admin.common.utils;

import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 新节点持续报「没有公网 IPv6」→ 防抖后清库里的 server_ipv6,
 * 但只清节点(面板这次启动以来)报过的那个地址;手填、节点从没报过的不清。
 * v6 回来时自动填写照旧;老节点(不带 caps)、命令响应一律不动。
 * 每个用例用不同的节点 id(报过的地址集合是静态的、断线也不清)。
 */
class NodeIpv6GoneTest {

    static final String EMPTY = "{\"uptime\":5,\"caps\":[\"listen_switch\"]}";
    /** 报的是库里那个地址(大小写不同,比较要规范化) */
    static final String SEEN = "{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:DB8::1\"]}";
    static final String HAS = "{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::9\"]}";
    static final String OLD = "{\"uptime\":5,\"cpu_usage\":1.0}";

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Node.class);
    }

    static final Pattern WHERE_EQ = Pattern.compile("server_ipv6\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    /**
     * 假的库:一台节点。update 按 wrapper 的 where 判断 server_ipv6 的条件(IS NULL / = 值,比较并设置),
     * 满足才按 set 的值改,返回是否改了。
     */
    static final class Env {
        final Node node = new Node();
        final NodeService ns = mock(NodeService.class);
        final WebSocketServer ws = new WebSocketServer();
        int updates;      // 真改成了几次
        int attempts;     // 发了几次 update
        /** 不为 null 时:getById 读完之后把库里改成这个值(模拟车主同一时刻保存),用一次 */
        String ownerSavesAfterRead;
        /** 大于 0 时:下一次 update 抛异常,用掉一次 */
        int failNextUpdates;

        Env(long id, String v6) {
            node.setId(id);
            node.setServerIpv6(v6);
            when(ns.getById(any())).thenAnswer(a -> {
                Node copy = new Node();
                copy.setId(node.getId());
                copy.setServerIpv6(node.getServerIpv6());
                if (ownerSavesAfterRead != null) {
                    node.setServerIpv6(ownerSavesAfterRead);
                    ownerSavesAfterRead = null;
                }
                return copy;
            });
            when(ns.update(any(Wrapper.class))).thenAnswer(a -> {
                attempts++;
                if (failNextUpdates > 0) {
                    failNextUpdates--;
                    throw new RuntimeException("db down");
                }
                LambdaUpdateWrapper<?> w = a.getArgument(0);
                String where = w.getSqlSegment();
                String cur = node.getServerIpv6();
                boolean match;
                if (where.contains("server_ipv6 IS NULL")) {
                    match = cur == null || cur.isEmpty();
                } else {
                    Matcher c = WHERE_EQ.matcher(where);
                    assertTrue(c.find(), "清除必须带 server_ipv6 = 读到的值: " + where);
                    match = java.util.Objects.equals(cur, w.getParamNameValuePairs().get(c.group(1)));
                }
                if (!match) {
                    return false;
                }
                Matcher m = Pattern.compile("server_ipv6=#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(w.getSqlSet());
                assertTrue(m.find(), w.getSqlSet());
                Object v = w.getParamNameValuePairs().get(m.group(1));
                node.setServerIpv6(v == null ? null : v.toString());
                updates++;
                return true;
            });
            ReflectionTestUtils.setField(ws, "nodeService", ns);
            WebSocketServer.forgetReported(id);
            WebSocketServer.forgetNodeV6(id);
        }

        void report(String json, long now) {
            ws.recordV6AndCaps(node.getId(), JSON.parseObject(json), now);
        }

        /** 从 t 起每 step 毫秒报一次空,共 n 次;返回下一个时间 */
        long empties(int n, long t, long step) {
            for (int i = 0; i < n; i++, t += step) {
                report(EMPTY, t);
            }
            return t;
        }
    }

    @Test
    void seenThenLostIsClearedOnlyAfterEnoughReportsAndTime() {
        Env e = new Env(5551L, "2001:db8::1");
        long t = 1_000_000L;
        e.report(SEEN, t - 2_000L);
        // 次数够了但时间不够(2 秒一报,30 次 = 58 秒):不清
        e.empties(WebSocketServer.V6_GONE_REPORTS, t, 2_000L);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        // 再报一次,跨过 60 秒:清
        e.report(EMPTY, t + 60_000L);
        assertNull(e.node.getServerIpv6());
        assertEquals(1, e.updates);
        // 之后一直空:不再反复写库
        e.empties(50, t + 62_000L, 2_000L);
        assertEquals(1, e.updates);
    }

    @Test
    void storedValueIsComparedAfterNormalising() {
        Env e = new Env(5560L, "[2001:DB8::1]");   // 库里写法不同,规范化后是同一个地址
        e.report("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8:0::1\"]}", 0);
        e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
    }

    @Test
    void handFilledValueNeverReportedIsNotCleared() {
        // 车主手填的地址节点从没报过(被过滤的网段 / 特殊环境):一直空也不清
        Env e = new Env(5561L, "2001:db8::1");
        e.empties(200, 0, 3_000L);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        assertEquals(0, e.updates);
        // 节点报的是别的地址,然后没了:库里那个它没报过,还是不清
        Env f = new Env(5562L, "2001:db8::1");
        f.report(HAS, 0);
        f.empties(200, 3_000L, 3_000L);
        assertEquals("2001:db8::1", f.node.getServerIpv6());
        assertEquals(0, f.updates);
    }

    @Test
    void reconnectBetweenSeeingAndLosingStillClears() {
        Env e = new Env(5563L, "2001:db8::1");
        e.report(SEEN, 0);
        WebSocketServer.forgetReported(5563L);     // 丢 v6 时节点重连了一次:报过的地址还记得
        e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
        assertEquals(1, e.updates);
    }

    @Test
    void timeAloneIsNotEnough() {
        Env e = new Env(5552L, "2001:db8::1");
        e.report(SEEN, 0);
        // 报得很慢:时间过了 60 秒,次数没到 → 不清
        e.empties(WebSocketServer.V6_GONE_REPORTS - 1, 10_000L, 10_000L);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        assertEquals(0, e.updates);
    }

    @Test
    void addressReportOrDisconnectResetsTheStreak() {
        Env e = new Env(5553L, "2001:db8::1");
        e.report(SEEN, 0);
        long t = e.empties(25, 3_000L, 3_000L);
        e.report(HAS, t);                 // 中间报了一次地址:重新数
        t = e.empties(25, t + 3_000L, 3_000L);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        WebSocketServer.forgetReported(5553L); // 断线 / 重连:重新数
        e.empties(25, t, 3_000L);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        assertEquals(0, e.updates);
    }

    @Test
    void oldNodeAndCommandResponsesNeverClear() {
        Env e = new Env(5554L, "2001:db8::1");
        e.report(SEEN, 0);
        for (int i = 1; i < 100; i++) {
            e.report(OLD, i * 3_000L);
            e.report("{\"type\":\"AddServiceResponse\",\"success\":true}", i * 3_000L);
        }
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        assertEquals(0, e.updates);
        // 显式空数组但不带 caps(不会有这种节点,保险):也不计
        assertFalse(WebSocketServer.v6GoneShouldClear(5554L, JSON.parseObject("{\"uptime\":1,\"ipv6_addrs\":[]}"),
                Collections.emptyList(), 999_999_999L));
    }

    @Test
    void refillsWhenV6ComesBack() {
        Env e = new Env(5555L, "2001:db8::1");
        e.report(SEEN, 0);
        long t = e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
        // v6 回来:库里是空的 → 自动填上新地址
        e.report(HAS, t);
        assertEquals("2001:db8::9", e.node.getServerIpv6());
        assertEquals(2, e.updates);
        // 再报同样的地址:不重复写库
        e.report(HAS, t + 3_000L);
        assertEquals(2, e.updates);
    }

    @Test
    void manualValueIsNotOverwrittenWhenV6Present() {
        // 库里已有(车主手填的)地址,节点报了别的地址:和以前一样不覆盖
        Env e = new Env(5556L, "2001:db8::1");
        e.report(HAS, 0);
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        assertEquals(0, e.updates);
    }

    @Test
    void nothingStoredMeansNoWrite() {
        Env e = new Env(5557L, null);
        e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 0, 3_000L);
        assertNull(e.node.getServerIpv6());
        assertEquals(0, e.updates);
    }

    @Test
    void listClearedTogetherWithColumn() {
        // 清库那一次内存里的列表也照常置空
        Env e = new Env(5558L, "2001:db8::1");
        e.report(SEEN, 0);
        e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertTrue(WebSocketServer.nodeV6Addrs(5558L).isEmpty());
        assertNull(e.node.getServerIpv6());
    }

    static final String AB = "{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::a\",\"2001:db8::b\"]}";

    @Test
    void refillPrefersTheAddressThatWasCleared() {
        // 多地址机器,车主选的是第二个 B;断网后清掉,回来时填回 B 而不是第一个 A
        Env e = new Env(5570L, "2001:db8::b");
        e.report(AB, 0);
        long t = e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
        e.report(AB, t);
        assertEquals("2001:db8::b", e.node.getServerIpv6());

        // 填回后记忆丢掉:下次清掉再回来、B 不在列表里了 → 用第一个
        t = e.empties(WebSocketServer.V6_GONE_REPORTS + 1, t + 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
        e.report(HAS, t);
        assertEquals("2001:db8::9", e.node.getServerIpv6());
    }

    @Test
    void pickRefillFallsBackToFirst() {
        // 按规范化比较认出是同一个地址,写进库的是 normalizeServerIpv6 的写法(去括号、小写)
        assertEquals("2001:db8:0::b", WebSocketServer.pickRefill(java.util.Arrays.asList("2001:db8::a", "2001:DB8:0::B"), "2001:db8::b"));
        assertEquals("2001:db8::a", WebSocketServer.pickRefill(java.util.Arrays.asList("2001:db8::a", "2001:db8::c"), "2001:db8::b"));
        assertEquals("2001:db8::a", WebSocketServer.pickRefill(java.util.Arrays.asList("2001:db8::a"), null));
        assertNull(WebSocketServer.pickRefill(Collections.singletonList("fe80::1"), null));
    }

    @Test
    void clearIsCompareAndSet() {
        // 读到 ::1 之后车主刚好保存了 ::77:清除不能把车主的值抹掉
        Env e = new Env(5571L, "2001:db8::1");
        e.report(SEEN, 0);
        long t = e.empties(WebSocketServer.V6_GONE_REPORTS - 1, 3_000L, 3_000L);
        assertEquals(0, e.attempts);
        e.ownerSavesAfterRead = "2001:db8::77";
        e.report(EMPTY, t);
        assertEquals("2001:db8::77", e.node.getServerIpv6());
        assertEquals(1, e.attempts);
        assertEquals(0, e.updates);
        // 这一轮已经处理过:不再反复查库 / 写库
        e.empties(20, t + 3_000L, 3_000L);
        assertEquals(1, e.attempts);
    }

    @Test
    void refillIsCompareAndSet() {
        Env e = new Env(5572L, "2001:db8::1");
        e.report(SEEN, 0);
        long t = e.empties(WebSocketServer.V6_GONE_REPORTS + 1, 3_000L, 3_000L);
        assertNull(e.node.getServerIpv6());
        // v6 回来的同一时刻车主自己填了 ::88:自动填写不能覆盖
        e.ownerSavesAfterRead = "2001:db8::88";
        e.report(SEEN, t);
        assertEquals("2001:db8::88", e.node.getServerIpv6());
    }

    @Test
    void dbErrorRetriesOnNextReport() {
        Env e = new Env(5573L, "2001:db8::1");
        e.report(SEEN, 0);
        long t = e.empties(WebSocketServer.V6_GONE_REPORTS - 1, 3_000L, 3_000L);
        assertEquals(0, e.attempts);
        e.failNextUpdates = 1;
        e.report(EMPTY, t);                       // 到点了,但写库抛异常
        assertEquals("2001:db8::1", e.node.getServerIpv6());
        e.report(EMPTY, t + 3_000L);              // 下一次上报重试成功
        assertNull(e.node.getServerIpv6());
        assertEquals(2, e.attempts);
        assertEquals(1, e.updates);
    }

    @Test
    void notSeenIsADefiniteNoAndIsNotRetried() {
        Env e = new Env(5574L, "2001:db8::1");     // 手填、没报过
        e.empties(WebSocketServer.V6_GONE_REPORTS + 50, 0, 3_000L);
        assertEquals(0, e.attempts);
        verify(e.ns, times(1)).getById(any());     // 到点查过一次库,之后不再查
    }

    @Test
    void seenSetIsBoundedAndDroppedOnNodeDelete() {
        long id = 5575L;
        WebSocketServer.forgetNodeV6(id);
        for (int i = 1; i <= WebSocketServer.V6_SEEN_MAX + 4; i++) {
            WebSocketServer.rememberV6(id, Collections.singletonList("2001:db8::" + Integer.toHexString(i)));
        }
        assertFalse(WebSocketServer.v6WasSeen(id, "2001:db8::1"));   // 最早的被挤掉
        assertTrue(WebSocketServer.v6WasSeen(id, "2001:db8::" + Integer.toHexString(WebSocketServer.V6_SEEN_MAX + 4)));
        assertTrue(WebSocketServer.v6WasSeen(id, "2001:db8::5"));    // 第 5 个还在(20 个里留最近 16 个)
        // 重新报一次的地址算最新,不会被挤掉
        WebSocketServer.rememberV6(id, Collections.singletonList("2001:db8::5"));
        WebSocketServer.rememberV6(id, Collections.singletonList("2001:db8::100"));
        assertTrue(WebSocketServer.v6WasSeen(id, "2001:db8::5"));
        assertFalse(WebSocketServer.v6WasSeen(id, "2001:db8::6"));
        WebSocketServer.forgetNodeV6(id);
        assertFalse(WebSocketServer.v6WasSeen(id, "2001:db8::5"));
    }
}
