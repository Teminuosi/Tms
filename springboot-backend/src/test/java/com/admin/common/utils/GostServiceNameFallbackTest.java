package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暂停 / 恢复 / 更新的「名字对不上」兜底(GostDeleteFallback.firstFound)。
 * 模拟节点照 go-gost/x/socket/service.go:Pause/Resume/Update 都先校验全部名字存在,
 * 有一个不存在就回 "service xxx not found"、什么都不动。
 */
class GostServiceNameFallbackTest {

    static class FakeNode {
        final Set<String> services = new HashSet<>();
        final Set<String> paused = new HashSet<>();
        final List<List<String>> received = new ArrayList<>();
        boolean offline = false;

        private GostDto check(List<String> names) {
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
            r.setMsg("OK");
            return r;
        }

        Function<String, GostDto> pause() {
            return n -> {
                List<String> names = Arrays.asList(n + "_tcp", n + "_udp");
                GostDto r = check(names);
                if ("OK".equals(r.getMsg())) {
                    paused.addAll(names);
                }
                return r;
            };
        }

        Function<String, GostDto> resume() {
            return n -> {
                List<String> names = Arrays.asList(n + "_tcp", n + "_udp");
                GostDto r = check(names);
                if ("OK".equals(r.getMsg())) {
                    paused.removeAll(names);
                }
                return r;
            };
        }
    }

    @Test
    void matchedNameSendsExactlyOneOriginalCommand() {
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_7_tcp", "5_2_7_udp"));
        GostDto r = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause());
        assertEquals("OK", r.getMsg());
        assertEquals(1, node.received.size());
        assertEquals(Arrays.asList("5_2_7_tcp", "5_2_7_udp"), node.received.get(0));
        assertEquals(new HashSet<>(Arrays.asList("5_2_7_tcp", "5_2_7_udp")), node.paused);
    }

    @Test
    void zeroNameWithoutUserTunnelSendsOneCommand() {
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_0_tcp", "5_2_0_udp"));
        GostDto r = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, null), node.pause());
        assertEquals("OK", r.getMsg());
        assertEquals(1, node.received.size());
    }

    @Test
    void protocolForwardWithUserTunnelPausesAndResumesTheZeroName() {
        // 协议转发建的时候是 _0,车友在这条隧道上又有 user_tunnel(id=7) → 以前按 5_2_7 发,节点 not found,停不掉
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_0_tcp", "5_2_0_udp", "15_2_0_tcp", "15_2_0_udp"));
        List<String> names = GostDeleteFallback.candidateNames(5L, 2, 7);

        GostDto r = GostDeleteFallback.firstFound(names, node.pause());
        assertEquals("OK", r.getMsg());
        assertEquals(new HashSet<>(Arrays.asList("5_2_0_tcp", "5_2_0_udp")), node.paused);
        assertEquals(2, node.received.size());
        assertEquals(Arrays.asList("5_2_7_tcp", "5_2_7_udp"), node.received.get(0));

        r = GostDeleteFallback.firstFound(names, node.resume());
        assertEquals("OK", r.getMsg());
        assertTrue(node.paused.isEmpty());
        // 别人的服务(15_ 开头)从头到尾没被点名
        for (List<String> sent : node.received) {
            for (String s : sent) {
                assertTrue(s.startsWith("5_2_"), s);
            }
        }
    }

    @Test
    void allMissingReturnsPrimaryResultSoCallerFallsBackAsBefore() {
        // 更新时两个名字都没有 → 返回原名那次的 not found,调用方照旧转去按原名 Add
        FakeNode node = new FakeNode();
        GostDto r = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause());
        assertEquals("service 5_2_7_tcp not found", r.getMsg());
        assertEquals(2, node.received.size());
    }

    @Test
    void offlineDoesNotTryOtherNames() {
        FakeNode node = new FakeNode();
        node.offline = true;
        GostDto r = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause());
        assertEquals("节点不在线", r.getMsg());
        assertEquals(1, node.received.size());
    }

    @Test
    void notFoundForAnotherNameIsNotTreatedAsMissing() {
        // 节点回的是别的名字 not found(比如同批里另一条),不能当成「原名不存在」去换名
        List<String> sent = new ArrayList<>();
        GostDto got = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), n -> {
            sent.add(n);
            GostDto r = new GostDto();
            r.setMsg("service 15_2_7_tcp not found");
            return r;
        });
        assertEquals("service 15_2_7_tcp not found", got.getMsg());
        assertEquals(Collections.singletonList("5_2_7"), sent);
    }

    @Test
    void remoteAndChainNamesAreRecognised() {
        List<String> sent = new ArrayList<>();
        GostDto got = GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), n -> {
            sent.add(n);
            GostDto r = new GostDto();
            r.setMsg(n.endsWith("_7") ? "service " + n + "_tls not found" : "OK");
            return r;
        });
        assertEquals("OK", got.getMsg());
        assertEquals(Arrays.asList("5_2_7", "5_2_0"), sent);
    }

    @Test
    void usedNameReportsWhichCandidateAnswered() {
        FakeNode node = new FakeNode();
        node.services.addAll(Arrays.asList("5_2_0_tcp", "5_2_0_udp"));
        String[] used = new String[1];
        GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause(), used);
        assertEquals("5_2_0", used[0]);

        node.services.addAll(Arrays.asList("5_2_7_tcp", "5_2_7_udp"));
        GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause(), used);
        assertEquals("5_2_7", used[0]);

        // 都没有 → 记原名(调用方随后按原名 Add)
        GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(6L, 2, 7), node.pause(), used);
        assertEquals("6_2_7", used[0]);

        // 离线 → 原名
        node.offline = true;
        GostDeleteFallback.firstFound(GostDeleteFallback.candidateNames(5L, 2, 7), node.pause(), used);
        assertEquals("5_2_7", used[0]);
    }

    @Test
    void nullUserTunnelIdIsZeroNotNull() {
        // ResetFlowAsync 以前拿 null 拼出 "5_2_null"
        assertEquals(Collections.singletonList("5_2_0"), GostDeleteFallback.candidateNames(5L, 2, null));
    }
}
