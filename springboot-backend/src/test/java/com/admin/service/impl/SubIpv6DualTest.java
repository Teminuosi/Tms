package com.admin.service.impl;

import com.admin.common.utils.ClashUtil;
import com.admin.common.utils.SingboxUtil;
import com.admin.common.utils.SubNames;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 订阅「IPv6 双栈都出」:关着时逐字节不变;开着时每条入站后面紧跟一条 v6 的(地址 2001:db8::/32 文档段) */
class SubIpv6DualTest {

    static final String V6 = "2001:db8::1";
    static final String[] PROTOS = {"vless", "vmess", "hysteria2", "shadowsocks", "trojan", "tuic", "anytls"};

    static Node node(Integer sub, Integer dual, String v6) {
        Node n = new Node();
        n.setName("机A");
        n.setServerIp(" 203.0.113.5 ");
        n.setServerIpv6(v6);
        n.setIpv6Sub(sub);
        n.setIpv6Dual(dual);
        return n;
    }

    static Inbound inbound(String proto) {
        Inbound in = new Inbound();
        in.setProtocol(proto);
        in.setSni("www.example.com");
        in.setPublicKey("pk");
        in.setShortId("ab");
        if ("shadowsocks".equals(proto)) in.setConfigJson("{\"method\":\"aes-128-gcm\",\"password\":\"sspw\"}");
        if ("vmess".equals(proto)) in.setConfigJson("{\"net\":\"ws\",\"path\":\"/p\",\"host\":\"h.example.com\"}");
        return in;
    }

    static InboundUser iu() {
        InboundUser u = new InboundUser();
        u.setUuid("11111111-2222-3333-4444-555555555555");
        u.setPassword("pw");
        return u;
    }

    static Forward fwd() {
        Forward f = new Forward();
        f.setInPort(30001);
        return f;
    }

    static Tunnel tun(String tcp, String udp) {
        Tunnel t = new Tunnel();
        t.setTcpListenAddr(tcp);
        t.setUdpListenAddr(udp);
        return t;
    }

    /** 转发所在隧道在听 IPv6(自动隧道开过 IPv6 / 新建时就是 [::]) */
    static final Tunnel DUAL = tun("[::]", "[::]");

    static SubNames.Name nm(String proto, boolean userSet) {
        return SubNames.resolve(proto, null, "机A", null, false, true, null, userSet ? "我的 节点" : null);
    }

    /** 老逻辑原样:改动前 buildClientLinkRaw + withFragment 的写法 */
    static String oldLink(String proto, String host, String text, boolean userSet) {
        Inbound in = inbound(proto);
        InboundUser u = iu();
        String link;
        switch (proto) {
            case "shadowsocks": link = SingboxUtil.buildShadowsocksLink(host, 30001, "aes-128-gcm", "sspw", text); break;
            case "vmess": link = SingboxUtil.buildVmessLink(u.getUuid(), host, 30001, text, "ws", "/p", "h.example.com"); break;
            case "trojan": link = SingboxUtil.buildTrojanRealityLink("pw", host, 30001, in.getSni(), "pk", "ab", text); break;
            case "hysteria2": link = SingboxUtil.buildHysteria2Link("pw", host, 30001, in.getSni(), text); break;
            case "tuic": link = SingboxUtil.buildTuicLink(u.getUuid(), "pw", host, 30001, in.getSni(), text); break;
            case "anytls": link = SingboxUtil.buildAnyTlsLink("pw", host, 30001, in.getSni(), text); break;
            default: link = SingboxUtil.buildVlessRealityLink(u.getUuid(), host, 30001, in.getSni(), "pk", "ab", text);
        }
        if (userSet && !"vmess".equals(proto)) link = SubNames.withFragment(link, text);
        return link;
    }

    static List<String> links(String proto, Node n, boolean userSet) {
        return links(proto, n, userSet, DUAL);
    }

    static List<String> links(String proto, Node n, boolean userSet, Tunnel t) {
        List<String> out = new ArrayList<>();
        InboundServiceImpl.addSubLinks(out, inbound(proto), iu(), n, fwd(), nm(proto, userSet), t);
        return out;
    }

    /**
     * 节点开关开着,但这条协议的转发不在听 IPv6(老版本把转发落进了车主手建的隧道,
     * 或开启后又在隧道页把监听改回 0.0.0.0):不出 v6 那条,和关着时逐字节一样。
     * hysteria2 / tuic 走 UDP,看 udp_listen_addr;其他看 tcp_listen_addr。
     */
    @Test
    void noV6LinkWhenForwardNotListeningOnV6() {
        Tunnel[] notV6 = {null, tun("0.0.0.0", "0.0.0.0"), tun(null, null), tun("1.2.3.4", "[::]")};
        for (String p : PROTOS) {
            boolean udpProto = "hysteria2".equals(p) || "tuic".equals(p);
            for (boolean us : new boolean[]{false, true}) {
                String only = oldLink(p, "203.0.113.5", nm(p, us).text, us);
                for (Tunnel t : notV6) {
                    if (udpProto && t != null && "[::]".equals(t.getUdpListenAddr())) {
                        continue;
                    }
                    assertEquals(java.util.Collections.singletonList(only), links(p, node(1, 1, V6), us, t), p);
                }
                // tcp [::]、udp 0.0.0.0:只走 TCP 的协议出 v6;UDP 类不出;ss(TCP+UDP 都用)也不出
                boolean bothProto = "shadowsocks".equals(p);
                assertEquals(udpProto || bothProto ? 1 : 2, links(p, node(1, 1, V6), us, tun("[::]", "0.0.0.0")).size(), p);
                assertEquals(udpProto ? 2 : 1, links(p, node(1, 1, V6), us, tun("0.0.0.0", " [::] ")).size(), p);
            }
        }
        // Clash 同理
        String[] ps = {"vless", "hysteria2"};
        String off = ClashUtil.buildConfig(clash(node(null, null, null), DUAL, ps));
        assertEquals(off, ClashUtil.buildConfig(clash(node(1, 1, V6), tun("0.0.0.0", "0.0.0.0"), ps)));
        assertEquals(off, ClashUtil.buildConfig(clash(node(1, 1, V6), null, ps)));
        List<Map<String, Object>> half = clash(node(1, 1, V6), tun("[::]", "0.0.0.0"), ps);
        assertEquals(3, half.size());
        assertEquals(V6, half.get(1).get("server"));
        assertEquals("vless", half.get(1).get("type"));
    }

    /**
     * 按协议实际用到的传输看监听:ss 客户端 TCP 和 UDP 都直连这个端口,两边都得是 [::] 才出 v6
     * (只有 TCP 是 [::] 时 v6 那条 UDP 不通);hysteria2 / tuic 只看 UDP;vless / vmess / trojan / anytls 只看 TCP。
     */
    @Test
    void listensV6ByTransportsTheProtocolUses() {
        Tunnel both = tun("[::]", "[::]"), tcpOnly = tun("[::]", "0.0.0.0"), udpOnly = tun("0.0.0.0", "[::]");
        assertTrue(InboundServiceImpl.listensV6(inbound("shadowsocks"), both));
        assertFalse(InboundServiceImpl.listensV6(inbound("shadowsocks"), tcpOnly));
        assertFalse(InboundServiceImpl.listensV6(inbound("shadowsocks"), udpOnly));
        assertFalse(InboundServiceImpl.listensV6(inbound("shadowsocks"), tun("[::]", null)));
        for (String p : new String[]{"hysteria2", "tuic"}) {
            assertTrue(InboundServiceImpl.listensV6(inbound(p), udpOnly), p);
            assertFalse(InboundServiceImpl.listensV6(inbound(p), tcpOnly), p);
        }
        for (String p : new String[]{"vless", "vmess", "trojan", "anytls"}) {
            assertTrue(InboundServiceImpl.listensV6(inbound(p), tcpOnly), p);
            assertFalse(InboundServiceImpl.listensV6(inbound(p), udpOnly), p);
        }
        for (String p : PROTOS) {
            assertTrue(InboundServiceImpl.listensV6(inbound(p), both), p);
        }
        // 链接 / Clash:ss 半双栈不出 v6,和关着时逐字节一样
        String ssOff = oldLink("shadowsocks", "203.0.113.5", nm("shadowsocks", false).text, false);
        assertEquals(java.util.Collections.singletonList(ssOff), links("shadowsocks", node(1, 1, V6), false, tcpOnly));
        assertEquals(2, links("shadowsocks", node(1, 1, V6), false, both).size());
        String[] ss = {"shadowsocks"};
        assertEquals(ClashUtil.buildConfig(clash(node(null, null, null), DUAL, ss)),
                ClashUtil.buildConfig(clash(node(1, 1, V6), tcpOnly, ss)));
        assertEquals(2, clash(node(1, 1, V6), both, ss).size());
    }

    @Test
    void offIsByteIdentical() {
        Node[] offs = {node(null, null, null), node(0, 0, V6), node(1, 0, V6), node(0, 1, V6), node(1, 1, null), node(1, 1, "  ")};
        for (String p : PROTOS) {
            for (boolean us : new boolean[]{false, true}) {
                for (Node n : offs) {
                    List<String> l = links(p, n, us);
                    assertEquals(1, l.size(), p);
                    assertEquals(oldLink(p, "203.0.113.5", nm(p, us).text, us), l.get(0), p);
                }
            }
        }
    }

    @Test
    void onAddsBracketedV6Link() {
        for (String p : PROTOS) {
            for (boolean us : new boolean[]{false, true}) {
                for (String v6 : new String[]{V6, " [2001:db8::1] "}) {
                    List<String> l = links(p, node(1, 1, v6), us);
                    assertEquals(2, l.size(), p);
                    String text = nm(p, us).text;
                    assertEquals(oldLink(p, "203.0.113.5", text, us), l.get(0), p);
                    assertEquals(oldLink(p, V6, text + " v6", us), l.get(1), p);
                    if ("vmess".equals(p)) {
                        String json = new String(Base64.getDecoder().decode(l.get(1).substring(8)), StandardCharsets.UTF_8);
                        assertTrue(json.contains("\"add\":\"2001:db8::1\""), json);
                        assertTrue(json.contains(" v6\""), json);
                    } else {
                        assertTrue(l.get(1).contains("@[2001:db8::1]:30001"), l.get(1));
                        assertTrue(l.get(1).contains(us ? "v6" : "+v6"), l.get(1));
                    }
                }
            }
        }
    }

    static List<Map<String, Object>> clash(Node n, String... protos) {
        return clash(n, DUAL, protos);
    }

    static List<Map<String, Object>> clash(Node n, Tunnel t, String... protos) {
        List<Map<String, Object>> proxies = new ArrayList<>();
        Set<String> used = ClashUtil.newNameSet();
        for (String p : protos) {
            InboundServiceImpl.addClashProxies(proxies, used, inbound(p), iu(), n, fwd(), nm(p, false).text, t);
        }
        return proxies;
    }

    @Test
    void clashOffUnchangedOnAddsUniqueV6() {
        String[] ps = {"vless", "vmess", "hysteria2", "shadowsocks", "vless"};
        String off = ClashUtil.buildConfig(clash(node(null, null, null), ps));
        assertEquals(off, ClashUtil.buildConfig(clash(node(1, 0, V6), ps)));
        assertFalse(off.contains(" v6")); // 注意 dns 段本来就有 ipv6: false

        List<Map<String, Object>> on = clash(node(1, 1, "[" + V6 + "]"), ps);
        assertEquals(10, on.size());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < on.size(); i++) {
            Map<String, Object> p = on.get(i);
            assertTrue(names.add((String) p.get("name")), "重名: " + p.get("name"));
            assertEquals(i % 2 == 0 ? "203.0.113.5" : V6, p.get("server"));
            if (i % 2 == 1) assertTrue(((String) p.get("name")).contains(" v6"), (String) p.get("name"));
        }
        // 两条同名 vless:第二条 v6 被 uniqueName 加了序号
        assertTrue(names.contains(on.get(1).get("name")) && !on.get(1).get("name").equals(on.get(9).get("name")));
        String yaml = ClashUtil.buildConfig(on);
        for (String nmv : names) {
            // 每个名字:proxies 一次 + 节点选择 + 自动选择 = 至少 3 次
            int c = yaml.split(java.util.regex.Pattern.quote(nmv), -1).length - 1;
            assertTrue(c >= 3, nmv + " 出现 " + c);
        }
    }

    /** 整份订阅按 buildClash 的两段走:先全部 v4,最后补 v6 */
    static List<Map<String, Object>> clashMany(Object[][] rows) {
        List<Map<String, Object>> proxies = new ArrayList<>();
        Set<String> used = ClashUtil.newNameSet();
        List<Runnable> pending = new ArrayList<>();
        for (Object[] r : rows) {
            InboundServiceImpl.addClashProxies(proxies, used, inbound("vless"), iu(), (Node) r[0], fwd(), (String) r[1], DUAL, pending);
        }
        pending.forEach(Runnable::run);
        return proxies;
    }

    static List<String> v4Names(List<Map<String, Object>> ps) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> p : ps) {
            if ("203.0.113.5".equals(p.get("server"))) out.add((String) p.get("name"));
        }
        return out;
    }

    @Test
    void enablingDualNeverRenamesAnyV4Node() {
        // 车友另有一个节点就叫「X v6」,还有一个叫「DIRECT」(Clash 保留名)
        for (Integer dual : new Integer[]{0, 1}) {
            Node a = node(1, dual, V6);
            Node other = node(null, null, null);
            Object[][] rows = {{a, "X"}, {other, "X v6"}, {a, "DIRECT"}, {other, "X v6 2"}};
            List<Map<String, Object>> off = clashMany(new Object[][]{{node(null, null, null), "X"}, {other, "X v6"},
                    {node(null, null, null), "DIRECT"}, {other, "X v6 2"}});
            List<Map<String, Object>> got = clashMany(rows);
            assertEquals(v4Names(off), v4Names(got));
            if (dual == 1) {
                assertEquals(6, got.size());
                Set<String> all = new java.util.HashSet<>();
                for (Map<String, Object> p : got) assertTrue(all.add((String) p.get("name")), "重名 " + p.get("name"));
                // v6 紧跟自己的 v4,名字 = v4 实际名 + " v6"(再去重)
                assertEquals(V6, got.get(1).get("server"));
                assertTrue(((String) got.get(1).get("name")).startsWith("X v6 "), (String) got.get(1).get("name"));
                assertEquals(V6, got.get(4).get("server"));
                assertEquals(got.get(3).get("name") + " v6", got.get(4).get("name"));
            }
        }
    }
}
