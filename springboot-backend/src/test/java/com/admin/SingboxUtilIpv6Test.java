package com.admin;

import com.admin.common.utils.SingboxUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IPv6 机器的分享链接要给地址包方括号;IPv4 / 域名的链接必须和原来一字不差(不能影响老用户)。
 * 纯工具类测试,不起 Spring。
 */
class SingboxUtilIpv6Test {

    @Test
    void uriHostLeavesIpv4AndDomainsUntouched() {
        assertSame("203.0.113.5", SingboxUtil.uriHost("203.0.113.5"));
        assertSame("hk.example.com", SingboxUtil.uriHost("hk.example.com"));
        assertNull(SingboxUtil.uriHost(null));
        assertEquals("", SingboxUtil.uriHost(""));
    }

    @Test
    void uriHostBracketsIpv6Once() {
        assertEquals("[2001:db8::1]", SingboxUtil.uriHost("2001:db8::1"));
        assertEquals("[2001:db8::1]", SingboxUtil.uriHost(" 2001:db8::1 "));
        assertEquals("[2001:db8::1]", SingboxUtil.uriHost("[2001:db8::1]"));
    }

    @Test
    void isIpv6Literal() {
        assertTrue(SingboxUtil.isIpv6Literal("2001:db8::1"));
        assertTrue(SingboxUtil.isIpv6Literal("[2001:db8::1]"));
        assertFalse(SingboxUtil.isIpv6Literal("203.0.113.5"));
        assertFalse(SingboxUtil.isIpv6Literal("hk.example.com"));
        assertFalse(SingboxUtil.isIpv6Literal(""));
        assertFalse(SingboxUtil.isIpv6Literal(null));
    }

    @Test
    void ipv6LinksDifferOnlyInBracketedHost() {
        String v4 = "203.0.113.5", v6 = "2001:db8::1";
        String[][] pairs = {
                {SingboxUtil.buildVlessRealityLink("u", v4, 443, "www.apple.com", "pk", "ab", "r"),
                        SingboxUtil.buildVlessRealityLink("u", v6, 443, "www.apple.com", "pk", "ab", "r")},
                {SingboxUtil.buildShadowsocksLink(v4, 8388, "aes-256-gcm", "pw", "r"),
                        SingboxUtil.buildShadowsocksLink(v6, 8388, "aes-256-gcm", "pw", "r")},
                {SingboxUtil.buildTrojanRealityLink("pw", v4, 443, "www.apple.com", "pk", "ab", "r"),
                        SingboxUtil.buildTrojanRealityLink("pw", v6, 443, "www.apple.com", "pk", "ab", "r")},
                {SingboxUtil.buildHysteria2Link("pw", v4, 443, "a.com", "r"),
                        SingboxUtil.buildHysteria2Link("pw", v6, 443, "a.com", "r")},
                {SingboxUtil.buildTuicLink("u", "pw", v4, 443, "a.com", "r"),
                        SingboxUtil.buildTuicLink("u", "pw", v6, 443, "a.com", "r")},
                {SingboxUtil.buildAnyTlsLink("pw", v4, 443, "a.com", "r"),
                        SingboxUtil.buildAnyTlsLink("pw", v6, 443, "a.com", "r")},
        };
        for (String[] p : pairs) {
            assertTrue(p[0].contains("@" + v4 + ":"), "IPv4 链接原样: " + p[0]);
            assertTrue(p[1].contains("@[" + v6 + "]:"), "IPv6 链接带方括号: " + p[1]);
            assertEquals(p[0], p[1].replace("[" + v6 + "]", v4), "除了主机名其它完全一样");
        }
    }
}
