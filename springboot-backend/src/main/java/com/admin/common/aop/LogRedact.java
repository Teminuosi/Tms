package com.admin.common.aop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志打码。后端日志(docker logs)谁都能看,不能原样打出节点密钥、密码、令牌、私钥、落地链接里的凭据。
 * LogAspect 拼请求日志前、WebSocketServer 打节点消息前都先过这里。
 *
 * 写正则的硬规矩(2026-11-10 复验踩过):
 * - 不许有会平方级回溯的写法:可变长前缀必须有左边界(引号 / \b / 后顾)并且限长;值用占有量词(++ / *+)
 * - 不许让 Java 正则递归吃栈:循环分组里放占有的整段([^"\\]++),别逐字符循环
 * - 打码自己出任何错都不能抛出去连累业务;超长输入先截断
 */
public final class LogRedact {

    private LogRedact() {
    }

    /** 超过这个长度先截断再打码(日志本来也没人看几十 KB 的一行) */
    static final int MAX_LEN = 64 * 1024;

    /**
     * 键名里带这些词就算秘密(不分大小写,含 tg_token / private_key / obfs-password 这类写法)。
     * link:落地 / 客户端链接整段就是凭据,格式五花八门(标准 base64 的 ss、住宅 socks 裸格式 user:pw@ip:port …),整段遮
     */
    private static final String KEYWORD =
            "(?:secret|passw(?:or)?d|pwd|token|private[_-]?key|psk|api[_-]?key|uuid|link)";
    /** 键名:前面最多 40 个字母 / 下划线 / 连字符,后面还允许数字(password2、token_v2),限长防回溯 */
    private static final String KEY = "[A-Za-z_-]{0,40}?" + KEYWORD + "[A-Za-z0-9_-]{0,40}";

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** JSON 字段:"secret":"..." / "tokens":[...] / "token":123;值被截断没收尾也照样遮 */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(\"" + KEY + "\"\\s*:\\s*)(?:\"(?:[^\"\\\\]++|\\\\.)*+(?:\"|\\z)|\\[[^\\]]*+(?:\\]|\\z)|\\{[^{}]*+(?:\\}|\\z)|-?\\d++(?:\\.\\d++)?)", CI);

    /** 字符串里再转义过一层的 JSON:\"secret\":\"...\" */
    private static final Pattern ESC_JSON_FIELD = Pattern.compile(
            "(\\\\\"" + KEY + "\\\\\"\\s*:\\s*)\\\\\"(?:[^\\\\]++|\\\\(?!\"))*+(?:\\\\\"|\\z)", CI);

    /** key=value:查询串、lombok toString 的 Node(secret=xx, ...);值到空白 / & / , / ) / 引号 / 反斜杠为止 */
    private static final Pattern KV = Pattern.compile(
            "(\\b" + KEY + "=)[^&\\s\",)\\\\]++", CI);

    /** /node/install 返回的安装命令:./install.sh -a host:port -s <密钥> */
    private static final Pattern INSTALL_SECRET = Pattern.compile(
            "(install\\.sh\\b[^\"\\n]{0,300}?\\s-s\\s+)[^\\s\"\\\\]++");

    /** 链接里的凭据:trojan://密码@host、vless://uuid@host、https://user:pw@host,含 JSON 转义过的 :\/\/ */
    private static final Pattern LINK_CRED = Pattern.compile(
            "(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]{0,15}:(?:\\\\?/){2})[^@/\\s\"\\\\]{1,1024}+@");

    /** 只在截断过时用:截断点落在 scheme:// 和 @ 之间,前半截凭据也要遮(没截断时结尾的普通网址不能动) */
    private static final Pattern LINK_CRED_TAIL = Pattern.compile(
            "(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]{0,15}:(?:\\\\?/){2})[^@/\\s\"\\\\]{1,1024}+\\z");

    /** 整段 base64 就是凭据的链接:vmess://base64、老式 ss://base64、标准 base64(带 /)的 ss://xxx@host */
    private static final Pattern B64_LINK = Pattern.compile(
            "(?<![A-Za-z0-9+.-])((?:vmess|ssr?):(?:\\\\?/){2})[A-Za-z0-9+/=_-]{8,}+", CI);

    public static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        try {
            String tail = "";
            if (s.length() > MAX_LEN) {
                tail = "…(日志过长,省略 " + (s.length() - MAX_LEN) + " 字符)";
                s = s.substring(0, MAX_LEN);
            }
            String out = JSON_FIELD.matcher(s).replaceAll("$1\"***\"");
            out = ESC_JSON_FIELD.matcher(out).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + "\\\"***\\\""));
            out = KV.matcher(out).replaceAll("$1***");
            out = INSTALL_SECRET.matcher(out).replaceAll("$1***");
            out = LINK_CRED.matcher(out).replaceAll("$1***@");
            if (!tail.isEmpty()) {
                out = LINK_CRED_TAIL.matcher(out).replaceAll("$1***");
            }
            out = B64_LINK.matcher(out).replaceAll("$1***");
            return out + tail;
        } catch (Throwable t) {
            // 打码出错(包括 StackOverflowError)不能连累业务:整段不打
            return "[日志打码失败,已省略 " + s.length() + " 字符]";
        }
    }

    /**
     * 给 logback 的异常替身:保留类型和堆栈,丢掉消息和 cause。
     * fastjson 等解析异常的消息里会带整段原文(可能有私钥),原样交给 logback 打码就白做了。
     */
    public static Throwable safe(Throwable ex) {
        if (ex == null) {
            return null;
        }
        RuntimeException r = new RuntimeException(ex.getClass().getName());
        r.setStackTrace(ex.getStackTrace());
        return r;
    }
}
