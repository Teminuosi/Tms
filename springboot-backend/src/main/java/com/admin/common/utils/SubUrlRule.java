package com.admin.common.utils;

import java.net.URI;

/**
 * 网站配置 sub_url(订阅地址,给车友的)的校验与规范化。
 * <p>
 * 填了之后,网页 / 客户端给车友看、复制、出二维码的订阅链接都换成
 * {sub_url}/api/v1/open_api/sub?token=… —— 所以它只能是「协议 + 主机名 [+ 端口] [+ 路径前缀]」,
 * 不能带 ? 和 #(否则拼出来的链接坏掉),末尾的 / 去掉。不填 = 和以前一样用面板地址。
 */
public final class SubUrlRule {

    public static final String KEY = "sub_url";
    public static final int MAX_LEN = 200;

    private SubUrlRule() {
    }

    /** 去首尾空白、去末尾的 /;null 当空串 */
    public static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 校验规范化之后的值。返回 null = 合法;否则是给人看的中文原因。空串由调用方决定是否允许(=清掉)。
     */
    public static String check(String normalized) {
        String s = normalized == null ? "" : normalized;
        if (s.isEmpty()) {
            return "订阅地址不能为空";
        }
        if (s.length() > MAX_LEN) {
            return "订阅地址太长(最多 " + MAX_LEN + " 个字符)";
        }
        String lower = s.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "订阅地址要以 http:// 或 https:// 开头,例如 https://sub.example.com";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return "订阅地址里不能有空格或控制字符";
            }
            if (c == '?' || c == '#') {
                return "订阅地址不能带 ? 或 #(只填 https://子域名,最多加端口和路径前缀)";
            }
            if (c == '\\' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`') {
                return "订阅地址里有不允许的字符: " + c;
            }
            if (c > 0x7e) {
                return "订阅地址只能用英文字符(中文域名请填 xn-- 开头的 punycode 写法)";
            }
        }
        // 显式语法(客户端 tmsSubUrl.cjs 逐条照抄,两边结论必须一致):
        // 协议://主机[:端口][/路径前缀];主机 = 域名(字母数字和 -,不以 - 开头结尾,每段 ≤63,最后一段以字母开头)
        // 或四段 IPv4 或 [IPv6];端口 1-65535;路径只许 URI 允许的字符和合法的 %XX
        String rest = s.substring(s.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        if (authority.isEmpty()) {
            return "订阅地址里没有主机名,例如 https://sub.example.com";
        }
        if (authority.indexOf('@') >= 0) {
            return "订阅地址不能带账号密码(@ 前面那段)";
        }
        String host;
        String portStr = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return HOST_ERR;
            }
            host = authority.substring(0, end + 1);
            String after = authority.substring(end + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return HOST_ERR;
                }
                portStr = after.substring(1);
            }
            if (!host.matches("\\[[0-9A-Fa-f:.]+\\]")) {
                return HOST_ERR;
            }
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            if (colon >= 0) {
                portStr = authority.substring(colon + 1);
            }
            if (!validHostname(host)) {
                return HOST_ERR;
            }
        }
        if (portStr != null) {
            if (!portStr.matches("[0-9]{1,5}") || Integer.parseInt(portStr) < 1 || Integer.parseInt(portStr) > 65535) {
                return "订阅地址的端口不对";
            }
        }
        if (!path.matches("(/|[A-Za-z0-9._~!$&()*+,;=:@-]|%[0-9A-Fa-f]{2})*")) {
            return "订阅地址的路径里有不允许的字符(只填路径前缀,例如 /sub)";
        }
        // 兜底:java.net.URI 也得认
        URI u;
        try {
            u = new URI(s);
        } catch (Exception e) {
            return "订阅地址格式不对,例如 https://sub.example.com";
        }
        String uriHost = u.getHost();
        if (uriHost == null || uriHost.isEmpty()) {
            return "订阅地址里没有主机名,例如 https://sub.example.com";
        }
        if (u.getRawUserInfo() != null) {
            return "订阅地址不能带账号密码(@ 前面那段)";
        }
        int port = u.getPort();
        if (port == 0 || port > 65535) {
            return "订阅地址的端口不对";
        }
        return null;
    }

    static final String HOST_ERR = "订阅地址的主机名不对(只能用字母、数字、- 和 .,例如 sub.example.com)";

    static boolean validHostname(String h) {
        if (h.isEmpty() || h.length() > 253) {
            return false;
        }
        if (h.matches("[0-9.]+")) {
            // 全是数字和点:只认四段 IPv4
            String[] p = h.split("\\.", -1);
            if (p.length != 4) {
                return false;
            }
            for (String x : p) {
                if (!x.matches("[0-9]{1,3}") || Integer.parseInt(x) > 255) {
                    return false;
                }
            }
            return true;
        }
        String[] labels = h.split("\\.", -1);
        for (String l : labels) {
            if (!l.matches("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) {
                return false;
            }
        }
        return Character.isLetter(labels[labels.length - 1].charAt(0));
    }
}
