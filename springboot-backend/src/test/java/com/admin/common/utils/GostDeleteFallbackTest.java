package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 模拟一个 gost 节点:按名字记着有哪些服务,DeleteService 的语义照 go-gost/x/socket/service.go(先全部校验存在,再删)。 */
class GostDeleteFallbackTest {

    static class FakeNode {
        final Set<String> services = new HashSet<>();
        final List<List<String>> received = new ArrayList<>();
        boolean offline = false;

        GostDto delete(List<String> names) {
            received.add(names);
            GostDto r = new GostDto();
            if (offline) {
                r.setMsg("节点不在线");
                return r;
            }
            for (String n : names) {
                if (!services.contains(n)) {
                    r.setMsg("service " + n + " not found");
                    return r;
                }
            }
            services.removeAll(names);
            r.setMsg("OK");
            return r;
        }

        Function<String, GostDto> whole() {
            return n -> delete(Arrays.asList(n + "_tcp", n + "_udp"));
        }

        List<Function<String, GostDto>> pieces() {
            return Arrays.asList(n -> delete(Collections.singletonList(n + "_tcp")),
                    n -> delete(Collections.singletonList(n + "_udp")));
        }
    }

    @Test
    void candidateNames() {
        assertEquals(Arrays.asList("5_2_0"), GostDeleteFallback.candidateNames(5L, 2, null));
        assertEquals(Arrays.asList("5_2_0"), GostDeleteFallback.candidateNames(5L, 2, 0));
        assertEquals(Arrays.asList("5_2_7", "5_2_0"), GostDeleteFallback.candidateNames(5L, 2, 7));
    }

    @Test
    void normalPathSendsExactlyOneOriginalCommand() {
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_7_tcp", "5_2_7_udp", "9_2_7_tcp"));
        String err = GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, 7), node.whole(), node.pieces());
        assertNull(err);
        assertEquals(1, node.received.size());
        assertEquals(Arrays.asList("5_2_7_tcp", "5_2_7_udp"), node.received.get(0));
        assertEquals(Collections.singleton("9_2_7_tcp"), node.services);
    }

    @Test
    void protocolForwardWithUserTunnelFallsBackToZeroName() {
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_0_tcp", "5_2_0_udp", "15_2_0_tcp", "15_2_0_udp"));
        String err = GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, 7), node.whole(), node.pieces());
        assertNull(err);
        // 别人的服务(15_ 开头)不能动
        assertEquals(new HashSet<>(Arrays.asList("15_2_0_tcp", "15_2_0_udp")), node.services);
    }

    @Test
    void neverExistedCountsAsDeleted() {
        FakeNode node = new FakeNode();
        node.services.add("6_2_0_tcp");
        assertNull(GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, null), node.whole(), node.pieces()));
        assertEquals(Collections.singleton("6_2_0_tcp"), node.services);
    }

    @Test
    void halfPresentIsStillCleanedUp() {
        FakeNode node = new FakeNode();
        node.services.add("5_2_0_tcp"); // udp 没了,整条删会因 udp not found 一个都不删
        assertNull(GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, null), node.whole(), node.pieces()));
        assertTrue(node.services.isEmpty());
    }

    @Test
    void offlineIsFailure() {
        FakeNode node = new FakeNode();
        node.offline = true;
        assertEquals("节点不在线", GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, 7), node.whole(), node.pieces()));
        assertEquals(1, node.received.size());
    }

    @Test
    void notFoundForSomeoneElsesNameIsNotTrusted() {
        GostDto r = new GostDto();
        r.setMsg("service 15_2_0_tcp not found");
        assertFalse(GostDeleteFallback.isNotFound(r, "5_2_0") && !r.getMsg().contains(" 5_2_0_"));
        assertFalse(GostDeleteFallback.isNotFound(r, "5_2_0"));
        r.setMsg("发送消息失败: x not found");
        assertFalse(GostDeleteFallback.isNotFound(r, "5_2_0"));
        r.setMsg("chain 5_2_0_chains not found");
        assertTrue(GostDeleteFallback.isNotFound(r, "5_2_0"));
    }

    @Test
    void singleEntityFallback() {
        // 链/远端只有一个实体:原名 not found → 试 _0;都 not found → 视为没有
        List<String> sent = new ArrayList<>();
        Function<String, GostDto> chain = n -> {
            sent.add(n);
            GostDto r = new GostDto();
            r.setMsg("chain " + n + "_chains not found");
            return r;
        };
        assertNull(GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, 7), chain, Collections.emptyList()));
        assertEquals(Arrays.asList("5_2_7", "5_2_0"), sent);
    }

    @Test
    void errorDuringFallbackIsFailure() {
        FakeNode node = new FakeNode();
        Function<String, GostDto> whole = n -> {
            GostDto r = new GostDto();
            r.setMsg(n.endsWith("_7") ? "service " + n + "_tcp not found" : "等待响应超时");
            return r;
        };
        assertEquals("等待响应超时", GostDeleteFallback.delete(GostDeleteFallback.candidateNames(5L, 2, 7), whole, Collections.emptyList()));
    }
}
