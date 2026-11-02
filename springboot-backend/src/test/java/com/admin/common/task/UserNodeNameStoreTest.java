package com.admin.common.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 启动清理:格式不对 / 位数过长的 ref_key 必须在任何 CAST 之前删掉(MySQL 5.7 严格模式下 CAST 失败会让整条清理不执行) */
class UserNodeNameStoreTest {

    @Test
    void idKeyPattern() {
        for (String ok : new String[]{"1", "0", "42", "1234567890123456789"}) {
            assertTrue(ok.matches(UserNodeNameStore.ID_KEY_RE), ok);
        }
        for (String bad : new String[]{"", "abc", "12x", "-1", "1 ", " 1", "1|0",
                "12345678901234567890", "12345678901234567890123"}) {
            assertFalse(bad.matches(UserNodeNameStore.ID_KEY_RE), bad);
        }
    }

    @Test
    void lineKeyPattern() {
        for (String ok : new String[]{"1|0", "12|5", "1234567890123456789|1234567890123456789"}) {
            assertTrue(ok.matches(UserNodeNameStore.LINE_KEY_RE), ok);
        }
        for (String bad : new String[]{"1", "1|", "|1", "x|y", "1|2|3", "1|0 ",
                "12345678901234567890|0", "1|12345678901234567890123"}) {
            assertFalse(bad.matches(UserNodeNameStore.LINE_KEY_RE), bad);
        }
    }

    @Test
    void formatChecksRunBeforeAnyCast() {
        int firstCast = -1, lastFormat = -1;
        for (int i = 0; i < UserNodeNameStore.CLEANUP.length; i++) {
            String sql = UserNodeNameStore.CLEANUP[i];
            if (sql.contains("CAST(") && firstCast < 0) {
                firstCast = i;
            }
            if (sql.contains("NOT REGEXP")) {
                lastFormat = i;
            }
        }
        assertTrue(firstCast > lastFormat && lastFormat >= 0);
        String all = String.join("\n", UserNodeNameStore.CLEANUP);
        assertTrue(all.contains("NOT REGEXP '" + UserNodeNameStore.ID_KEY_RE + "'"));
        assertTrue(all.contains("NOT REGEXP '" + UserNodeNameStore.LINE_KEY_RE + "'"));
    }
}
