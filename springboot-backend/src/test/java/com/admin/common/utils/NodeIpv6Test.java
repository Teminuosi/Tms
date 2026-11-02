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
}
