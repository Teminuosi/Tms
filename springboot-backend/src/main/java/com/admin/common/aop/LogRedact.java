package com.admin.common.aop;

import java.util.regex.Pattern;

/**
 * 请求日志打码。后端日志(docker logs)谁都能看,不能原样打出节点密钥、密码、令牌、私钥。
 * LogAspect 把请求参数、返回值、异常信息拼进日志前都先过这里。
 */
final class LogRedact {

    private LogRedact() {
    }

    private static final String SENSITIVE = "[A-Za-z]*(?:[Ss]ecret|[Pp]assword|[Pp]wd|[Tt]oken|[Pp]rivate[Kk]ey)[A-Za-z]*";

    /** JSON 字段:"secret":"..."、"newPassword":"..."(值里允许转义字符) */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(\"" + SENSITIVE + "\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    /** key=value 形式:查询串、lombok toString 的 Node(secret=xxx, ...) */
    private static final Pattern KV = Pattern.compile(
            "(\\b" + SENSITIVE + "=)[^&\\s\",)]+");

    /** /node/install 返回的安装命令:./install.sh -a host:port -s <密钥> */
    private static final Pattern INSTALL_SECRET = Pattern.compile(
            "(install\\.sh\\b[^\"]*?\\s-s\\s+)[^\\s\"\\\\]+");

    static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = JSON_FIELD.matcher(s).replaceAll("$1\"***\"");
        out = KV.matcher(out).replaceAll("$1***");
        out = INSTALL_SECRET.matcher(out).replaceAll("$1***");
        return out;
    }
}
