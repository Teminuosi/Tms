package com.admin.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 车友节点名:三处订阅共用的取名函数、转发链接改名、名字校验、Clash 撞名 */
class SubNamesTest {

    // ---------- 老逻辑的逐字副本(aa25a94 的 InboundServiceImpl),用来证明没设车友名时逐字节不变 ----------

    private static String oldProtocolDisplayName(String protocol) {
        if (protocol == null) {
            return "VLESS";
        }
        switch (protocol) {
            case "shadowsocks": return "Shadowsocks";
            case "vmess":       return "VMess";
            case "trojan":      return "Trojan";
            case "hysteria2":   return "Hysteria2";
            case "tuic":        return "TUIC";
            case "anytls":      return "AnyTLS";
            default:            return "VLESS";
        }
    }

    /** 老 buildAggregateSubscription 拼前缀 + 老 buildClientLink 取名 */
    private static String oldLinkName(String protocol, String remarkField, String nodeName, String landingName,
                                      boolean relay, boolean aggregate) {
        String namePrefix = "";
        if (aggregate) {
            StringBuilder prefix = new StringBuilder(nodeName);
            if (relay) {
                prefix.append("→").append(landingName);
            }
            prefix.append(" ");
            namePrefix = prefix.toString();
        }
        boolean customName = remarkField != null && !remarkField.isEmpty();
        String remark = customName ? remarkField : oldProtocolDisplayName(protocol);
        if (!customName && namePrefix != null && !namePrefix.isEmpty()) {
            remark = namePrefix + remark;
        }
        return remark;
    }

    /** 老 buildClashSubscription 取名 */
    private static String oldClashName(String protocol, String remarkField, String nodeName, String landingName,
                                       boolean relay, boolean aggUser) {
        boolean customName = remarkField != null && !remarkField.isEmpty();
        String remark = customName ? remarkField : oldProtocolDisplayName(protocol);
        if (aggUser && !customName) {
            StringBuilder prefix = new StringBuilder(nodeName);
            if (relay) {
                prefix.append("→").append(landingName);
            }
            prefix.append(" ");
            remark = prefix + remark;
        }
        return remark;
    }

    private static final String[] PROTOCOLS = {"vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls", null, "weird"};
    private static final String[] REMARKS = {null, "", "香港 高速", " 带空格 ", "a+b#c", "🇭🇰 HK"};
    private static final String[] NODES = {"面板机", "HK 01", "x"};
    private static final String[] LANDINGS = {"日本", "落地#7", "US west"};

    @Test
    void noNamesIsByteIdenticalToOldLogic() {
        int n = 0;
        for (String p : PROTOCOLS) {
            for (String r : REMARKS) {
                for (String node : NODES) {
                    for (String land : LANDINGS) {
                        for (boolean relay : new boolean[]{false, true}) {
                            for (boolean agg : new boolean[]{false, true}) {
                                String ln = relay ? land : null;
                                SubNames.Name nm = SubNames.resolve(p, r, node, ln, relay, agg, null, null);
                                assertEquals(oldLinkName(p, r, node, ln, relay, agg), nm.text);
                                assertEquals(oldClashName(p, r, node, ln, relay, agg), nm.text);
                                assertFalse(nm.userSet, "没设车友名时不能换编码");
                                // 空串当没设
                                SubNames.Name nm2 = SubNames.resolve(p, r, node, ln, relay, agg, "", "");
                                assertEquals(nm.text, nm2.text);
                                assertFalse(nm2.userSet);
                                n++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(n > 1000);
    }

    @Test
    void protocolDisplayNameUnchanged() {
        for (String p : PROTOCOLS) {
            assertEquals(oldProtocolDisplayName(p), SubNames.protocolDisplayName(p));
        }
    }

    @Test
    void aliasOverridesGlobalRemarkAndPrefix() {
        // 别名 + 协议名(没全局改名)
        assertEquals("香港-1 VLESS", SubNames.resolve("vless", null, "面板机", "日本", true, true, "香港-1", null).text);
        assertEquals("香港-1 VLESS", SubNames.resolve("vless", "", "面板机", "日本", true, false, "香港-1", null).text);
        // 别名 + 全局改名:别名盖过全局改名的「不加前缀」规则,但全局名留着
        SubNames.Name nm = SubNames.resolve("trojan", "高速T", "面板机", null, false, true, "香港-1", null);
        assertEquals("香港-1 高速T", nm.text);
        assertTrue(nm.userSet);
        // 单条和聚合一样
        assertEquals(nm.text, SubNames.resolve("trojan", "高速T", "面板机", null, false, false, "香港-1", null).text);
    }

    @Test
    void perUserProtocolNameWinsVerbatim() {
        SubNames.Name nm = SubNames.resolve("vmess", "全局", "面板机", "日本", true, true, "别名", "我的 VMess 🚀");
        assertEquals("我的 VMess 🚀", nm.text);
        assertTrue(nm.userSet);
        assertEquals("自定义", SubNames.resolve("vless", null, "n", null, false, false, null, "自定义").text);
        assertEquals("自定义", SubNames.resolve("vless", null, "n", null, false, true, null, "自定义").text);
    }

    @Test
    void fragmentEncodingForUserNames() {
        assertEquals("%E9%A6%99%E6%B8%AF%201%20VLESS", SubNames.encodeFragment("香港 1 VLESS"));
        assertEquals("a%2Bb%23c", SubNames.encodeFragment("a+b#c"));
        assertEquals("vless://u@h:1?x=1#a%20b", SubNames.withFragment("vless://u@h:1?x=1#old+name", "a b"));
        assertEquals("ss://x@h:1#a%20b", SubNames.withFragment("ss://x@h:1", "a b"));
        // 往返:客户端按「+ 是字面量」解,解出来就是原名
        assertEquals("a+b c#🚀", SubNames.decodeFragment(SubNames.encodeFragment("a+b c#🚀")));
    }

    // ---------- 转发链接改名 ----------

    private static String vmess(String json) {
        return "vmess://" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject vmessJson(String link) {
        String b64 = link.substring(8);
        return JSON.parseObject(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
    }

    @Test
    void forwardRenameUrlSchemes() {
        String[] links = {
                "vless://uuid@1.2.3.4:20001?encryption=none&security=reality&type=tcp#%5B%E8%BD%AC%E5%8F%91%5D%20a",
                "ss://YWVzOnB3@1.2.3.4:20002#old",
                "trojan://pw@1.2.3.4:20003?security=reality#x",
                "hysteria2://pw@1.2.3.4:20004?sni=a&insecure=1#y",
        };
        for (String l : links) {
            String head = l.substring(0, l.indexOf('#'));
            assertEquals(head + "#%E9%A6%99%E6%B8%AF%20%E8%BD%AC%E5%8F%91", SubNames.applyForwardName(l, "香港 转发"));
        }
        // 没有 # 的补上
        assertEquals("socks://YTpi@1.2.3.4:5#n", SubNames.applyForwardName("socks://YTpi@1.2.3.4:5", "n"));
        // 名字为空原样返回(同一个对象)
        String l = links[0];
        assertSame(l, SubNames.applyForwardName(l, null));
        assertSame(l, SubNames.applyForwardName(l, ""));
        // 不是链接的原样返回
        assertEquals("1.2.3.4:80", SubNames.applyForwardName("1.2.3.4:80", "n"));
    }

    @Test
    void forwardRenameVmessRewritesPs() {
        String json = "{\"v\":\"2\",\"ps\":\"源节点\",\"add\":\"1.2.3.4\",\"port\":\"20005\",\"id\":\"abc\",\"net\":\"ws\"}";
        String plain = vmess(json);
        String withFrag = plain + "#%5B%E8%BD%AC%E5%8F%91%5D%20x";
        for (String l : new String[]{plain, withFrag}) {
            String out = SubNames.applyForwardName(l, "车友 VMess 🚀");
            assertTrue(out.startsWith("vmess://"));
            assertFalse(out.contains("#"), "多余的 # 要去掉");
            JSONObject j = vmessJson(out);
            assertEquals("车友 VMess 🚀", j.getString("ps"));
            assertEquals("1.2.3.4", j.getString("add"));
            assertEquals("20005", j.getString("port"));
            assertEquals("ws", j.getString("net"));
            // 字段顺序不变(只改 ps)
            assertTrue(new String(Base64.getDecoder().decode(out.substring(8)), StandardCharsets.UTF_8)
                    .startsWith("{\"v\":\"2\",\"ps\":"));
        }
        // 默认名:vmess 看 ps
        assertEquals("源节点", SubNames.forwardLinkName(withFrag));
        // base64url / 无填充也认
        String urlb64 = "vmess://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals("n", vmessJson(SubNames.applyForwardName(urlb64, "n")).getString("ps"));
    }

    @Test
    void forwardRenameMalformedVmessUnchanged() {
        String bad = "vmess://!!!not-base64!!!";
        assertSame(bad, SubNames.applyForwardName(bad, "n"));
        String notJson = "vmess://" + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        assertSame(notJson, SubNames.applyForwardName(notJson, "n"));
        assertEquals("", SubNames.forwardLinkName(bad));
    }

    @Test
    void forwardDefaultName() {
        assertEquals("[转发] a b", SubNames.forwardLinkName("vless://u@h:1?x#%5B%E8%BD%AC%E5%8F%91%5D%20a%20b"));
        assertEquals("a+b", SubNames.forwardLinkName("ss://x@h:1#a+b"));
        assertEquals("", SubNames.forwardLinkName("ss://x@h:1"));
        assertEquals("", SubNames.forwardLinkName(null));
    }

    // ---------- 名字校验 ----------

    @Test
    void normalizeName() {
        assertArrayEquals(new String[]{"香港-1", null}, SubNames.normalize("  香港-1 \t "));
        assertArrayEquals(new String[]{"", null}, SubNames.normalize("   "));
        assertArrayEquals(new String[]{"", null}, SubNames.normalize(null));
        StringBuilder emoji64 = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            emoji64.append("🚀");
        }
        assertNull(SubNames.normalize(emoji64.toString())[1], "64 个 emoji 可以");
        assertNotNull(SubNames.normalize(emoji64 + "a")[1], "65 个字不行");
        assertNotNull(SubNames.normalize("a\nb")[1]);
        assertNotNull(SubNames.normalize("a\rb")[1]);
        assertNotNull(SubNames.normalize("a\tb")[1]);
        assertNotNull(SubNames.normalize("a\u0000b")[1]);
        assertNotNull(SubNames.normalize("a" + (char) 0x2028 + "b")[1]);
        assertNotNull(SubNames.normalize("a\u0085b")[1]);
        assertNull(SubNames.normalize("🇭🇰 香港 #1 a+b")[1]);
    }

    // ---------- Clash 撞名 ----------

    @Test
    void clashReservedNamesAreAvoided() {
        Set<String> used = ClashUtil.newNameSet();
        assertEquals("DIRECT 2", ClashUtil.uniqueName("DIRECT", used));
        assertEquals("REJECT 2", ClashUtil.uniqueName("REJECT", used));
        assertEquals("GLOBAL 2", ClashUtil.uniqueName("GLOBAL", used));
        assertEquals("🚀 节点选择 2", ClashUtil.uniqueName("🚀 节点选择", used));
        assertEquals("♻️ 自动选择 2", ClashUtil.uniqueName("♻️ 自动选择", used));
        assertEquals("🐟 漏网之鱼 2", ClashUtil.uniqueName("🐟 漏网之鱼", used));
        // 普通名字和以前一样
        assertEquals("VLESS", ClashUtil.uniqueName("VLESS", used));
        assertEquals("VLESS 2", ClashUtil.uniqueName("VLESS", used));
        assertEquals("面板机→日本 VLESS", ClashUtil.uniqueName("面板机→日本 VLESS", used));
    }

    @Test
    void clashConfigLoadsWithReservedNodeName() {
        Set<String> used = ClashUtil.newNameSet();
        java.util.List<java.util.Map<String, Object>> proxies = new java.util.ArrayList<>();
        proxies.add(ClashUtil.toProxy("trojan", ClashUtil.uniqueName("🚀 节点选择", used), "1.2.3.4", 1,
                null, "pw", "sni", "pk", "sid", null));
        String yaml = ClashUtil.buildConfig(proxies);
        java.util.Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(yaml);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object p : (java.util.List<?>) root.get("proxies")) {
            names.add(String.valueOf(((java.util.Map<?, ?>) p).get("name")));
        }
        for (Object g : (java.util.List<?>) root.get("proxy-groups")) {
            assertFalse(names.contains(String.valueOf(((java.util.Map<?, ?>) g).get("name"))), "节点名不能和分组同名");
        }
    }
}
