package com.admin.common.aop;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 日志打码:节点密钥、密码、令牌、私钥不能原样进 docker logs;打码本身不能拖慢 / 打断业务 */
class LogRedactTest {

    @Test
    void flowConfigParamsMasked() {
        // /flow/config 的参数按参数名映射:{"rawData":"...","secret":"..."}
        String in = "{\"rawData\":\"abc\",\"secret\":\"d41d8cd98f00b204e9800998ecf8427e\"}";
        String out = LogRedact.redact(in);
        assertFalse(out.contains("d41d8cd98f00b204e9800998ecf8427e"), out);
        assertTrue(out.contains("\"rawData\":\"abc\""), out);
        assertTrue(out.contains("\"secret\":\"***\""), out);
    }

    @Test
    void installCommandMasked() {
        String in = "{\"code\":0,\"data\":\"curl -L https://x/install.sh -o ./install.sh && chmod +x ./install.sh && ./install.sh -a 1.2.3.4:6365 -s SeCrEt123\"}";
        String out = LogRedact.redact(in);
        assertFalse(out.contains("SeCrEt123"), out);
        assertTrue(out.contains("-a 1.2.3.4:6365 -s ***"), out);
    }

    @Test
    void passwordsTokensKeysMasked() {
        String in = "{\"username\":\"admin\",\"password\":\"p@ss\\\"word\",\"pwd\":\"x1\",\"newPassword\":\"x2\","
                + "\"token\":\"eyJ.a.b\",\"subToken\":\"t9\",\"privateKey\":\"PK\",\"secretKey\":\"SK\",\"publicKey\":\"PUB\"}";
        String out = LogRedact.redact(in);
        for (String s : new String[]{"p@ss", "x1", "x2", "eyJ.a.b", "t9", "PK\"", "SK\""}) {
            assertFalse(out.contains(s), s + " 漏了: " + out);
        }
        assertTrue(out.contains("\"username\":\"admin\""), out);
        assertTrue(out.contains("\"publicKey\":\"PUB\""), out); // 公钥不是秘密,照常打
    }

    @Test
    void moreKeyNamesMasked() {
        String in = "{\"passwd\":\"A1\",\"obfs-password\":\"A2\",\"PASSWORD\":\"A3\",\"psk\":\"A4\",\"apiKey\":\"A5\","
                + "\"uuid\":\"A6\",\"tokens\":[\"A7\",\"A8\"],\"token\":123456,\"name\":\"keep\"}";
        String out = LogRedact.redact(in);
        for (String s : new String[]{"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "123456"}) {
            assertFalse(out.contains(s), s + " 漏了: " + out);
        }
        assertTrue(out.contains("\"name\":\"keep\""), out);
    }

    @Test
    void queryStyleMasked() {
        assertEquals("type=1&secret=***&version=2", LogRedact.redact("type=1&secret=abc&version=2"));
        assertEquals("Node(id=1, secret=***, name=x)", LogRedact.redact("Node(id=1, secret=abcd, name=x)"));
    }

    @Test
    void kvStopsAtWhitespaceAndBackslash() {
        // 只遮值本身,不把后面的正常日志一起抹掉,也不吃掉转义 JSON 的反斜杠
        assertEquals("收到 secret=*** 端口 443 状态 正常", LogRedact.redact("收到 secret=abc 端口 443 状态 正常"));
        assertEquals("{\\\"x\\\":\\\"a?secret=***\\\"}", LogRedact.redact("{\\\"x\\\":\\\"a?secret=abc\\\"}"));
    }

    @Test
    void underscoreKeysMasked() {
        String out = LogRedact.redact("{\"tg_token\":\"T1\",\"private_key\":\"K1\",\"sub_token\":\"S1\"}");
        for (String s : new String[]{"T1", "K1", "S1"}) assertFalse(out.contains(s), out);
    }

    @Test
    void escapedJsonInsideStringMasked() {
        String out = LogRedact.redact("{\"data\":\"{\\\"secret\\\":\\\"X9\\\",\\\"name\\\":\\\"n\\\"}\"}");
        assertFalse(out.contains("X9"), out);
        assertTrue(out.contains("name"), out);
    }

    @Test
    void realityKeypairResponseMasked() {
        // 节点回 GenerateRealityKeypair:WebSocketServer 收到消息时打的就是这串
        String in = "{\"type\":\"GenerateRealityKeypairResponse\",\"requestId\":\"r1\",\"data\":{\"privateKey\":\"PRIV-abc\",\"publicKey\":\"PUB-xyz\"}}";
        String out = LogRedact.redact(in);
        assertFalse(out.contains("PRIV-abc"), out);
        assertTrue(out.contains("PUB-xyz"), out);
    }

    @Test
    void linkCredentialMasked() {
        String out = LogRedact.redact("{\"link\":\"trojan://pa55w0rd@1.2.3.4:443?sni=a#hk\"}");
        assertFalse(out.contains("pa55w0rd"), out);
        assertTrue(out.contains("1.2.3.4:443"), out);
        String esc = LogRedact.redact("{\"link\":\"trojan:\\/\\/pw0rd@1.2.3.4:443\"}");
        assertFalse(esc.contains("pw0rd"), esc);
        String vm = LogRedact.redact("vmess://eyJ2IjoiMiIsImlkIjoiYWJjIn0= 和 ss://YWVzLTI1Ni1nY206cGFzcw==#hk");
        assertFalse(vm.contains("eyJ2IjoiMiIsImlkIjoiYWJjIn0"), vm);
        assertFalse(vm.contains("YWVzLTI1Ni1nY206cGFzcw"), vm);
        assertTrue(vm.contains("#hk"), vm);
        assertEquals("见 https://example.com/a?b=1", LogRedact.redact("见 https://example.com/a?b=1"));
    }

    @Test
    void ordinaryTextUnchanged() {
        String in = "{\"name\":\"香港-1\",\"inPort\":20001,\"remark\":\"-s 不是参数\"}";
        assertEquals(in, LogRedact.redact(in));
        assertNull(LogRedact.redact(null));
    }

    // ---------- 打码不能拖垮业务:超长输入线性时间、不栈溢出、出错也不抛 ----------

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    void longLettersNoBacktracking() {
        // 免登录的登录接口用户名不限长:1MB 纯字母不能卡住线程(原 LINK_CRED 平方级回溯)
        String in = "{\"username\":\"" + repeat('a', 1_000_000) + "\"}";
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> LogRedact.redact(in));
        String hex = "{\"data\":\"" + "0123456789abcdef".repeat(10_000) + "\"}";
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> LogRedact.redact(hex));
    }

    @Test
    void longSecretValueNoStackOverflow() throws Exception {
        // RSA-4096 PEM 私钥量级(几 KB)放进 privateKey:默认 1MB 栈的线程里不能 StackOverflowError
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + repeat('A', 20_000) + "\\n-----END PRIVATE KEY-----";
        String in = "{\"privateKey\":\"" + pem + "\",\"data\":\"{\\\"secret\\\":\\\"" + repeat('B', 20_000) + "\\\"}\"}";
        String[] out = new String[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try { out[0] = LogRedact.redact(in); } catch (Throwable e) { err[0] = e; }
        }, "redact", 1024 * 1024);
        t.start();
        t.join(5000);
        assertNull(err[0], String.valueOf(err[0]));
        assertNotNull(out[0]);
        assertFalse(out[0].contains("AAAAAAAAAA"), "私钥没打码");
        assertFalse(out[0].contains("BBBBBBBBBB"), "转义 JSON 里的密钥没打码");
    }

    @Test
    void hugeInputTruncated() {
        String in = repeat('x', 300_000);
        String out = LogRedact.redact(in);
        assertTrue(out.length() < 70_000, "超长日志要截断: " + out.length());
        assertTrue(out.contains("省略"), out.substring(out.length() - 80));
    }

    @Test
    void safeExceptionKeepsStackDropsMessage() {
        Exception ex = new IllegalStateException("{\"secret\":\"LEAK\"}");
        Throwable s = LogRedact.safe(ex);
        assertFalse(String.valueOf(s.getMessage()).contains("LEAK"), s.getMessage());
        assertTrue(s.getMessage().contains("IllegalStateException"), s.getMessage());
        assertArrayEquals(ex.getStackTrace(), s.getStackTrace());
        assertNull(s.getCause());
    }
}
