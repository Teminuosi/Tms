package com.admin.common.aop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志打码。后端日志(docker logs)谁都能看,不能原样打出节点密钥、密码、令牌、私钥、落地链接里的密码。
 * LogAspect 拼请求日志前、WebSocketServer 打节点消息前都先过这里。
 */
public final class LogRedact {

    private LogRedact() {
    }

    /** 键名里带 secret / password / pwd / token / privateKey(含下划线写法 tg_token、private_key) */
    private static final String SENSITIVE =
            "[A-Za-z_]*(?:[Ss]ecret|[Pp]assword|[Pp]wd|[Tt]oken|[Pp]rivate_?[Kk]ey)[A-Za-z_]*";

    /** JSON 字段:"secret":"..."(值里允许转义字符) */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(\"" + SENSITIVE + "\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    /** 字符串里再转义过一层的 JSON:\"secret\":\"...\" */
    private static final Pattern ESC_JSON_FIELD = Pattern.compile(
            "(\\\\\"" + SENSITIVE + "\\\\\"\\s*:\\s*)\\\\\"(?:(?!\\\\\").)*\\\\\"");

    /** key=value:查询串(到 & 为止)、lombok toString 的 Node(secret=xx yy, name=...)(到 ", 下一个键=" 或 ")" 为止) */
    private static final Pattern KV = Pattern.compile(
            "(\\b" + SENSITIVE + "=)[^&\"\\r\\n]*?(?=, [A-Za-z_]+=|\\)|&|\"|\\r|\\n|$)");

    /** /node/install 返回的安装命令:./install.sh -a host:port -s <密钥> */
    private static final Pattern INSTALL_SECRET = Pattern.compile(
            "(install\\.sh\\b[^\"]*?\\s-s\\s+)[^\\s\"\\\\]+");

    /** 链接里的凭据:trojan://密码@host、vless://uuid@host、ss://xxx@host */
    private static final Pattern LINK_CRED = Pattern.compile(
            "([A-Za-z][A-Za-z0-9+.-]*://)[^@/\\s\"\\\\]+@");

    public static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = JSON_FIELD.matcher(s).replaceAll("$1\"***\"");
        out = ESC_JSON_FIELD.matcher(out).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + "\\\"***\\\""));
        out = KV.matcher(out).replaceAll("$1***");
        out = INSTALL_SECRET.matcher(out).replaceAll("$1***");
        out = LINK_CRED.matcher(out).replaceAll("$1***@");
        return out;
    }
}
