package com.admin.common.aop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 请求日志打码:节点密钥、密码、令牌、私钥不能原样进 docker logs */
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
    void queryStyleMasked() {
        assertEquals("type=1&secret=***&version=2", LogRedact.redact("type=1&secret=abc&version=2"));
    }

    @Test
    void ordinaryTextUnchanged() {
        String in = "{\"name\":\"香港-1\",\"inPort\":20001,\"remark\":\"-s 不是参数\"}";
        assertEquals(in, LogRedact.redact(in));
        assertNull(LogRedact.redact(null));
    }
}
