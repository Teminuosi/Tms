package com.admin.common.utils;

import com.admin.service.impl.NodeServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 节点 IPv6:server_ipv6 校验、gost 监听地址输出、上报字段解析 */
class NodeIpv6Test {

    @Test
    void acceptsGlobalUnicastAndNormalizes() {
        assertEquals("2001:db8::1", NodeServiceImpl.normalizeServerIpv6("  2001:DB8::1 "));
        assertEquals("2a01:4f8::abcd", NodeServiceImpl.normalizeServerIpv6("[2A01:4F8::ABCD]"));
        assertEquals("3fff::1", NodeServiceImpl.normalizeServerIpv6("3fff::1"));
    }

    @Test
    void emptyMeansClear() {
        assertEquals("", NodeServiceImpl.normalizeServerIpv6(""));
        assertEquals("", NodeServiceImpl.normalizeServerIpv6("   "));
        assertEquals("", NodeServiceImpl.normalizeServerIpv6("[]"));
        assertEquals("", NodeServiceImpl.normalizeServerIpv6(null));
    }

    @Test
    void rejectsNonGlobalOrNonIpv6() {
        for (String bad : Arrays.asList("::1", "::", "fe80::1", "fd00::1", "fc00::1", "ff02::1",
                "4000::1", "1.2.3.4", "::ffff:1.2.3.4", "example.com", "2001:db8::1%eth0",
                "2001:db8::g", "2001:db8:::1", "[2001:db8::1", "2001:db8::1]:443")) {
            assertThrows(IllegalArgumentException.class, () -> NodeServiceImpl.normalizeServerIpv6(bad), bad);
        }
    }

    @Test
    void gostListenAddr() {
        assertEquals(":443", GostUtil.listenAddr("[::]", 443));
        assertEquals("0.0.0.0:443", GostUtil.listenAddr("0.0.0.0", 443));
        assertEquals("[2001:db8::1]:443", GostUtil.listenAddr("[2001:db8::1]", 443));
        assertEquals("1.2.3.4:80", GostUtil.listenAddr("1.2.3.4", 80));
    }

    @Test
    void parsesStringLists() {
        JSONObject info = JSON.parseObject("{\"ipv6_addrs\":[\"2001:db8::1\",\" \",\"2001:db8::2\"],\"caps\":[\"listen_switch\"]}");
        assertEquals(Arrays.asList("2001:db8::1", "2001:db8::2"), WebSocketServer.stringList(info, "ipv6_addrs"));
        assertEquals(Collections.singletonList("listen_switch"), WebSocketServer.stringList(info, "caps"));
        assertNull(WebSocketServer.stringList(info, "missing"));
        assertNull(WebSocketServer.stringList(JSON.parseObject("{\"caps\":\"x\"}"), "caps"));
        assertTrue(WebSocketServer.nodeCaps(987654L).isEmpty());
        assertTrue(WebSocketServer.nodeV6Addrs(null).isEmpty());
    }

    /** caps 不能一直沿用旧的:节点换回老程序(上报不带 caps)或断线后,面板不能还认为能切 */
    @Test
    void capsFollowLatestReportAndClearOnDisconnect() {
        Long id = 876543L;
        WebSocketServer.forgetReported(id);
        assertFalse(WebSocketServer.capsReported(id));

        WebSocketServer.recordCaps(id, JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::1\"]}"));
        assertTrue(WebSocketServer.capsReported(id));
        assertEquals(Collections.singletonList("listen_switch"), WebSocketServer.nodeCaps(id));

        // 命令响应等非系统信息消息不带 caps:不动缓存
        WebSocketServer.recordCaps(id, JSON.parseObject("{\"type\":\"AddServiceResponse\",\"success\":true}"));
        assertEquals(Collections.singletonList("listen_switch"), WebSocketServer.nodeCaps(id));

        // 老节点程序(v13)的系统信息不带 caps:视为没有
        WebSocketServer.recordCaps(id, JSON.parseObject("{\"uptime\":6,\"cpu_usage\":1.0}"));
        assertTrue(WebSocketServer.capsReported(id));
        assertTrue(WebSocketServer.nodeCaps(id).isEmpty());

        WebSocketServer.recordCaps(id, JSON.parseObject("{\"uptime\":7,\"caps\":[\"listen_switch\"]}"));
        WebSocketServer.forgetReported(id);
        assertFalse(WebSocketServer.capsReported(id));
        assertTrue(WebSocketServer.nodeCaps(id).isEmpty());
    }

    /** 这次上报里的 v6 列表:新节点不带 ipv6_addrs = 没有了(omitempty);老节点 / 非系统信息不动 */
    @Test
    void reportedV6DistinguishesNewNodeWithoutV6FromOldNode() {
        assertEquals(Collections.singletonList("2001:db8::1"),
                WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::1\"]}")));
        // 新节点(带 caps)没 v6:字段整个不带 → 清空
        assertEquals(Collections.emptyList(),
                WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"]}")));
        // 显式空数组 / null 也是空
        assertEquals(Collections.emptyList(),
                WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[]}")));
        assertEquals(Collections.emptyList(),
                WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":null}")));
        // 老节点程序(不带 caps)本来就不报 v6:不动
        assertNull(WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"cpu_usage\":1.0}")));
        // 命令响应等非系统信息:不动
        assertNull(WebSocketServer.reportedV6(JSON.parseObject("{\"type\":\"AddServiceResponse\",\"success\":true}")));
        assertNull(WebSocketServer.reportedV6(JSON.parseObject("{\"caps\":[\"listen_switch\"]}")));
        // 格式不对:不动
        assertNull(WebSocketServer.reportedV6(JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":\"x\"}")));
        assertNull(WebSocketServer.reportedV6(null));
    }

    /** 节点没了 v6 以后面板不能还显示旧地址;老节点照旧 */
    @Test
    void v6ListClearsWhenNewNodeStopsReportingIt() {
        Long id = 765432L;
        WebSocketServer ws = new WebSocketServer(); // nodeService 为 null:只动内存,不写库
        WebSocketServer.forgetReported(id);

        ws.recordV6AndCaps(id, JSON.parseObject("{\"uptime\":5,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::1\"]}"));
        assertEquals(Collections.singletonList("2001:db8::1"), WebSocketServer.nodeV6Addrs(id));

        // 命令响应不带 ipv6_addrs:不动
        ws.recordV6AndCaps(id, JSON.parseObject("{\"type\":\"AddServiceResponse\",\"success\":true}"));
        assertEquals(Collections.singletonList("2001:db8::1"), WebSocketServer.nodeV6Addrs(id));

        // 新节点这次系统信息里没有 ipv6_addrs(机器的 v6 没了):清空
        ws.recordV6AndCaps(id, JSON.parseObject("{\"uptime\":6,\"caps\":[\"listen_switch\"]}"));
        assertTrue(WebSocketServer.nodeV6Addrs(id).isEmpty());

        // v6 回来了:重新显示
        ws.recordV6AndCaps(id, JSON.parseObject("{\"uptime\":7,\"caps\":[\"listen_switch\"],\"ipv6_addrs\":[\"2001:db8::2\"]}"));
        assertEquals(Collections.singletonList("2001:db8::2"), WebSocketServer.nodeV6Addrs(id));

        // 老节点程序的系统信息(不带 caps、也不带 ipv6_addrs):和以前一样不动
        ws.recordV6AndCaps(id, JSON.parseObject("{\"uptime\":8,\"cpu_usage\":1.0}"));
        assertEquals(Collections.singletonList("2001:db8::2"), WebSocketServer.nodeV6Addrs(id));

        WebSocketServer.forgetReported(id);
        assertTrue(WebSocketServer.nodeV6Addrs(id).isEmpty());
    }
}
