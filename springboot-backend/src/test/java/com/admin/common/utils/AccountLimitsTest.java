package com.admin.common.utils;

import com.admin.entity.InboundLine;
import com.admin.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountLimitsTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long NOW = 1_800_000_000_000L;

    private static User user(Integer role, Long flowGb, long in, long out, Long exp, Integer status) {
        User u = new User();
        u.setId(5L);
        u.setRoleId(role);
        u.setFlow(flowGb);
        u.setInFlow(in);
        u.setOutFlow(out);
        u.setExpTime(exp);
        u.setStatus(status);
        return u;
    }

    private static InboundLine line(Integer status, Long flowGb, Long exp) {
        InboundLine l = new InboundLine();
        l.setStatus(status);
        l.setFlow(flowGb);
        l.setExpTime(exp);
        return l;
    }

    @Test
    void flowZeroOrNullIsUnlimited() {
        assertFalse(AccountLimits.flowExceeded(0L, 999 * GB, 999 * GB));
        assertFalse(AccountLimits.flowExceeded(null, 999 * GB, 999 * GB));
        assertFalse(AccountLimits.flowExceeded(-1L, 999 * GB, 0L));
    }

    @Test
    void flowExceededAtOrAboveLimit() {
        assertFalse(AccountLimits.flowExceeded(50L, 30 * GB, 20 * GB - 1));
        assertTrue(AccountLimits.flowExceeded(50L, 30 * GB, 20 * GB));
        assertTrue(AccountLimits.flowExceeded(50L, 40 * GB, 30 * GB)); // 粉丝说的「设 50G 用了 70G」
        assertFalse(AccountLimits.flowExceeded(50L, null, null));
    }

    @Test
    void hugeFlowDoesNotOverflowIntoPause() {
        assertFalse(AccountLimits.flowExceeded(Long.MAX_VALUE, Long.MAX_VALUE / 2, 0L));
        assertFalse(AccountLimits.flowExceeded(Long.MAX_VALUE / GB + 1, 1L, 0L));
    }

    @Test
    void adminIsNeverBlockedByAccountFlow() {
        User admin = user(0, 1L, 10 * GB, 10 * GB, 0L, 1);
        assertFalse(AccountLimits.accountFlowExceeded(admin));
        assertFalse(AccountLimits.accountBlocked(admin, NOW));
        User rider = user(1, 1L, 10 * GB, 10 * GB, 0L, 1);
        assertTrue(AccountLimits.accountFlowExceeded(rider));
        assertTrue(AccountLimits.accountBlocked(rider, NOW));
    }

    @Test
    void accountBlockedByExpiryOrDisableOrFlow() {
        assertFalse(AccountLimits.accountBlocked(user(1, 0L, 900 * GB, 0, 0L, 1), NOW)); // 不限 + 永久
        assertFalse(AccountLimits.accountBlocked(user(1, 0L, 0, 0, null, 1), NOW));
        assertTrue(AccountLimits.accountBlocked(user(1, 0L, 0, 0, NOW - 1, 1), NOW));
        assertTrue(AccountLimits.accountBlocked(user(1, 0L, 0, 0, NOW, 1), NOW));
        assertFalse(AccountLimits.accountBlocked(user(1, 0L, 0, 0, NOW + 1, 1), NOW));
        assertTrue(AccountLimits.accountBlocked(user(1, 0L, 0, 0, 0L, 0), NOW));
        assertFalse(AccountLimits.accountBlocked(user(1, 0L, 0, 0, 0L, null), NOW));
        assertTrue(AccountLimits.accountBlocked(user(1, 10L, 10 * GB, 0, 0L, 1), NOW));
        assertTrue(AccountLimits.accountBlocked(null, NOW));
    }

    @Test
    void lineMayResumeOnlyWhenLineItselfIsFine() {
        // 正常线路,无配额无到期
        assertTrue(AccountLimits.lineMayResume(line(1, 0L, null), 999 * GB, NOW));
        assertTrue(AccountLimits.lineMayResume(line(1, null, 0L), 0, NOW));
        // 车主手动停 / 线路自己停过的(status=0)永远不拉
        assertFalse(AccountLimits.lineMayResume(line(0, 0L, null), 0, NOW));
        assertFalse(AccountLimits.lineMayResume(line(null, 0L, null), 0, NOW));
        assertFalse(AccountLimits.lineMayResume(null, 0, NOW));
        // 线路自己到期了
        assertFalse(AccountLimits.lineMayResume(line(1, 0L, NOW - 1), 0, NOW));
        assertTrue(AccountLimits.lineMayResume(line(1, 0L, NOW + 1000), 0, NOW));
        // 线路自己跑满了
        assertFalse(AccountLimits.lineMayResume(line(1, 10L, null), 10 * GB, NOW));
        assertTrue(AccountLimits.lineMayResume(line(1, 10L, null), 10 * GB - 1, NOW));
    }

    @Test
    void lineDepletedMatchesUpdateLineCriteria() {
        assertFalse(AccountLimits.lineDepleted(0L, 999 * GB));
        assertFalse(AccountLimits.lineDepleted(null, 999 * GB));
        assertTrue(AccountLimits.lineDepleted(1L, GB));
        assertFalse(AccountLimits.lineDepleted(1L, GB - 1));
    }
}
