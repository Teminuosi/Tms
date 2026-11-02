package com.admin.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 订阅里的节点名怎么算 —— 单条线路订阅、聚合订阅、Clash 三处共用这一个函数。
 *
 * 以前三处各写一份(buildClientLink 一份、buildClashSubscription 一份),
 * 注释里写着「两边必须一起改」,加车友节点名之后再各写一份迟早对不上。
 *
 * 优先级(三月 2026-11-01 拍板):
 *   车友协议名(原样)> 线路别名(「别名 」+ 全局改名或协议名)> 以前的规则。
 * 以前的规则:改过名(全局 remark)就用 remark;没改过用协议名,聚合订阅再加「机器→落地 」前缀。
 *
 * 【没设车友名字时必须逐字节和以前一样】SubNamesTest 里留着一份老逻辑的副本逐个比对。
 */
public final class SubNames {

    private SubNames() {
    }

    /** 名字最长多少个字(按 Unicode 码点算,emoji 算一个,和 utf8mb4 VARCHAR(64) 一致) */
    public static final int MAX_LEN = 64;

    /**
     * Clash 里不能拿来当节点名的:我们自己的三个分组名 + Mihomo 的内置策略。
     * 节点叫这些名字时 Mihomo 加载整份配置报错(分组和节点同名 / 覆盖内置策略)。
     */
    public static final Set<String> CLASH_RESERVED = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "🚀 节点选择", "♻️ 自动选择", "🐟 漏网之鱼",
            "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")));

    /** 算出来的名字 + 是否用到了车友自己的设置(决定链接里名字的编码方式,见 fragment) */
    public static final class Name {
        public final String text;
        public final boolean userSet;

        Name(String text, boolean userSet) {
            this.text = text;
            this.userSet = userSet;
        }
    }

    /** 协议在客户端里显示的名字(没改过名时兜底用) */
    public static String protocolDisplayName(String protocol) {
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

    /**
     * @param protocol     入站协议
     * @param globalRemark 全局改名(Inbound.remark),空 = 没改过
     * @param nodeName     机器名(聚合订阅前缀用)
     * @param landingName  落地名;直连传 null。落地记录没了由调用方传「落地#id」(和以前一致)
     * @param relay        是不是中转线路(landingId != null)
     * @param aggregate    聚合订阅(true)还是单条线路订阅(false);Clash 同理
     * @param lineAlias    车友这条线路的别名,空 = 没设
     * @param userName     车友这个协议的名字,空 = 没设
     */
    public static Name resolve(String protocol, String globalRemark, String nodeName, String landingName,
                               boolean relay, boolean aggregate, String lineAlias, String userName) {
        if (notEmpty(userName)) {
            return new Name(userName, true);
        }
        boolean customName = globalRemark != null && !globalRemark.isEmpty();
        String base = customName ? globalRemark : protocolDisplayName(protocol);
        if (notEmpty(lineAlias)) {
            return new Name(lineAlias + " " + base, true);
        }
        // ↓ 以前的规则,一个字都不能变
        if (aggregate && !customName) {
            StringBuilder prefix = new StringBuilder(String.valueOf(nodeName));
            if (relay) {
                prefix.append("→").append(landingName);
            }
            prefix.append(" ");
            return new Name(prefix + base, false);
        }
        return new Name(base, false);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 链接 # 后面的名字。
     *
     * 以前一直用 URLEncoder,空格会变成「+」。实测源码:v2rayN(Uri.GetComponents Unescaped)、
     * v2rayNG(decodeURIComponent 里特意把 + 当字面量)、Mihomo(Go url.Parse 的 Fragment)
     * 都把「+」原样显示 —— 「香港 VLESS」会显示成「香港+VLESS」。
     * 但老名字(没设车友名)要逐字节不变,所以只有用到车友设置的名字才改用 %20。
     */
    public static String encodeFragment(String name) {
        try {
            return java.net.URLEncoder.encode(name == null ? "" : name, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /** 把链接 # 后面换成新名字(%20 编码);没有 # 就补上。link 为空原样返回 */
    public static String withFragment(String link, String name) {
        if (link == null || link.isEmpty()) {
            return link;
        }
        int i = link.indexOf('#');
        String head = i >= 0 ? link.substring(0, i) : link;
        return head + "#" + encodeFragment(name);
    }

    /**
     * 给分到车友名下的转发链接套上车友起的名字(name 为空原样返回,逐字节不变)。
     *
     * vmess 的名字在 base64 JSON 的 ps 里,# 后面那段客户端不认 —— 以前客户端往 vmess 链接后面
     * 补「#[转发] 名字」,订阅里显示的还是源链接的 ps,所以「VMess 转发改不了名」。
     * 这里把 ps 改掉、去掉多余的 #。解不开(不是合法 base64 / JSON)就原样返回,不冒险。
     */
    public static String applyForwardName(String link, String name) {
        if (link == null || name == null || name.isEmpty()) {
            return link;
        }
        String s = link.trim();
        if (s.regionMatches(true, 0, "vmess://", 0, 8)) {
            int i = s.indexOf('#');
            String b64 = (i >= 0 ? s.substring(8, i) : s.substring(8)).trim();
            JSONObject j = decodeVmess(b64);
            if (j == null) {
                return link;
            }
            j.put("ps", name);
            return "vmess://" + Base64.getEncoder().encodeToString(j.toJSONString().getBytes(StandardCharsets.UTF_8));
        }
        if (!s.contains("://")) {
            return link;
        }
        return withFragment(s, name);
    }

    /** 这条转发链接在客户端里默认显示成什么(vmess 看 ps,其余看 # 后面);读不出来返回 "" */
    public static String forwardLinkName(String link) {
        if (link == null) {
            return "";
        }
        String s = link.trim();
        if (s.regionMatches(true, 0, "vmess://", 0, 8)) {
            int i = s.indexOf('#');
            JSONObject j = decodeVmess((i >= 0 ? s.substring(8, i) : s.substring(8)).trim());
            return j == null ? "" : (j.getString("ps") == null ? "" : j.getString("ps"));
        }
        int i = s.indexOf('#');
        if (i < 0) {
            return "";
        }
        return decodeFragment(s.substring(i + 1));
    }

    /** 按客户端的方式解 # 后面:只解 %XX,「+」是字面量(和 v2rayN / v2rayNG / Mihomo 一致) */
    public static String decodeFragment(String frag) {
        try {
            return java.net.URLDecoder.decode(frag.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return frag;
        }
    }

    private static JSONObject decodeVmess(String b64) {
        try {
            String t = b64.replace('-', '+').replace('_', '/');
            while (t.length() % 4 != 0) {
                t = t + "=";
            }
            byte[] raw = Base64.getDecoder().decode(t);
            Object o = JSON.parse(new String(raw, StandardCharsets.UTF_8), Feature.OrderedField);
            return o instanceof JSONObject ? (JSONObject) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验并整理车友起的名字。返回 [整理后的名字, 错误]:错误非 null 表示不能用。
     * 空串(去空白后)= 清掉这个名字,是合法的。
     */
    public static String[] normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.codePointCount(0, s.length()) > MAX_LEN) {
            return new String[]{s, "名字最多 " + MAX_LEN + " 个字"};
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
                return new String[]{s, "名字里不能有换行或控制字符"};
            }
            i += Character.charCount(cp);
        }
        return new String[]{s, null};
    }

    /** 线路的 ref_key:「机器id|落地id」,直连落地 id 记 0 */
    public static String lineKey(Long nodeId, Long landingId) {
        return nodeId + "|" + (landingId == null ? 0L : landingId);
    }

    /** 一个车友的全部名字(一次查出来,订阅生成时查表用) */
    public static final class UserNames {
        public static final UserNames EMPTY = new UserNames(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

        public final Map<String, String> lines;
        public final Map<Long, String> inbounds;
        public final Map<Long, String> forwards;

        public UserNames(Map<String, String> lines, Map<Long, String> inbounds, Map<Long, String> forwards) {
            this.lines = lines;
            this.inbounds = inbounds;
            this.forwards = forwards;
        }

        public static UserNames mutable() {
            return new UserNames(new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        public String line(Long nodeId, Long landingId) {
            return lines.get(lineKey(nodeId, landingId));
        }

        public String inbound(Long inboundId) {
            return inboundId == null ? null : inbounds.get(inboundId);
        }

        public String forward(Long forwardId) {
            return forwardId == null ? null : forwards.get(forwardId);
        }
    }
}
