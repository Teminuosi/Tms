package com.admin.common.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 车友「在用」状态:服务名解析、只记有流量的上报、快照、并发 */
class OnlineTrackerTest {

    @BeforeEach
    void reset() {
        OnlineTracker.clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> users(Map<String, Object> snap) {
        return (Map<String, Long>) snap.get("users");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> forwards(Map<String, Object> snap) {
        return (Map<String, Long>) snap.get("forwards");
    }

    @Test
    void parseHandlesAllSuffixes() {
        assertArrayEquals(new long[]{12, 34}, OnlineTracker.parse("12_34_0"));
        assertArrayEquals(new long[]{12, 34}, OnlineTracker.parse("12_34_0_tcp"));
        assertArrayEquals(new long[]{12, 34}, OnlineTracker.parse("12_34_0_udp"));
        assertArrayEquals(new long[]{12, 34}, OnlineTracker.parse("12_34_56_tls"));
        assertArrayEquals(new long[]{12, 34}, OnlineTracker.parse("12_34_56_tls_extra"));
    }

    @Test
    void parseRejectsGarbage() {
        assertNull(OnlineTracker.parse(null));
        assertNull(OnlineTracker.parse(""));
        assertNull(OnlineTracker.parse("web_api"));
        assertNull(OnlineTracker.parse("12"));
        assertNull(OnlineTracker.parse("12_"));
        assertNull(OnlineTracker.parse("_12_3"));
        assertNull(OnlineTracker.parse("a_b_c"));
        assertNull(OnlineTracker.parse("12_-3_0"));
        assertNull(OnlineTracker.parse("0_5_0"));
        assertNull(OnlineTracker.parse("12_0_0"));
        assertNull(OnlineTracker.parse("12_99999999999999999999_0"));
        assertNull(OnlineTracker.parse("１２_34_0")); // 全角数字
    }

    @Test
    void recordsOnlyWhenThereIsTraffic() {
        OnlineTracker.record("7_3_0_tcp", 0L, 0L, 1000);
        OnlineTracker.record("7_3_0_tcp", null, null, 1000);
        OnlineTracker.record("web_api", 10L, 10L, 1000);
        Map<String, Object> s = OnlineTracker.snapshot(2000);
        assertTrue(users(s).isEmpty());
        assertTrue(forwards(s).isEmpty());
        assertEquals(2000L, s.get("now"));

        OnlineTracker.record("7_3_0_tcp", 5L, 0L, 1500);
        OnlineTracker.record("8_3_0_udp", 0L, 9L, 1700);
        OnlineTracker.record("9_4_11_tls", 1L, 1L, 1600);
        s = OnlineTracker.snapshot(2000);
        assertEquals(1700L, users(s).get("3"));
        assertEquals(1600L, users(s).get("4"));
        assertEquals(1500L, forwards(s).get("7"));
        assertEquals(1700L, forwards(s).get("8"));
        assertEquals(1600L, forwards(s).get("9"));
    }

    @Test
    void lateOlderReportDoesNotMoveTimeBackwards() {
        OnlineTracker.record("7_3_0_tcp", 5L, 0L, 5000);
        OnlineTracker.record("7_3_0_udp", 5L, 0L, 4000);
        Map<String, Object> s = OnlineTracker.snapshot(6000);
        assertEquals(5000L, users(s).get("3"));
        assertEquals(5000L, forwards(s).get("7"));
    }

    @Test
    void veryOldEntriesArePruned() {
        OnlineTracker.record("7_3_0_tcp", 5L, 0L, 1000);
        long later = 1000 + OnlineTracker.KEEP_MS + 1;
        Map<String, Object> s = OnlineTracker.snapshot(later);
        assertTrue(users(s).isEmpty());
        assertTrue(forwards(s).isEmpty());
    }

    @Test
    void concurrentRecordsKeepTheLatest() throws Exception {
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int base = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    OnlineTracker.record((i % 50 + 1) + "_" + (i % 5 + 1) + "_0_tcp", 1L, 0L, base * 10000L + i);
                    if (i % 500 == 0) {
                        OnlineTracker.snapshot(base * 10000L + i);
                    }
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        Map<String, Object> s = OnlineTracker.snapshot(100000);
        assertEquals(5, users(s).size());
        assertEquals(50, forwards(s).size());
        // 最后一个线程(base=7)的最后几次写入一定是最大值
        assertEquals(7 * 10000L + 4999, users(s).get(String.valueOf(4999 % 5 + 1)));
    }
}
