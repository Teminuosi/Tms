package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 账号放开后只拉 account_pause 里记着的,并且线路 / 隧道权限自己该停的不拉 */
class AccountLineResumeTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long NOW = System.currentTimeMillis();

    private UserService userService;
    private ForwardService forwardService;
    private UserTunnelService userTunnelService;
    private InboundMapper inboundMapper;
    private InboundUserMapper inboundUserMapper;
    private InboundLineMapper inboundLineMapper;
    private AccountPauseStore store;
    private AccountLineResume r;

    private static User user(Long flowGb, long used, Long exp, Integer status) {
        User u = new User();
        u.setId(7L);
        u.setUser("rider");
        u.setRoleId(1);
        u.setFlow(flowGb);
        u.setInFlow(used);
        u.setOutFlow(0L);
        u.setExpTime(exp);
        u.setStatus(status);
        return u;
    }

    private static InboundLine line(long node, Integer status, Long flowGb, Long exp) {
        InboundLine l = new InboundLine();
        l.setUserId(7L);
        l.setNodeId(node);
        l.setStatus(status);
        l.setFlow(flowGb);
        l.setExpTime(exp);
        return l;
    }

    private static InboundUser iu(long inboundId, long fid) {
        InboundUser x = new InboundUser();
        x.setUserId(7L);
        x.setInboundId(inboundId);
        x.setGostForwardId(fid);
        return x;
    }

    private static Inbound inbound(long id, long node) {
        Inbound in = new Inbound();
        in.setId(id);
        in.setNodeId(node);
        return in;
    }

    private static Forward fwd(long id, int status, long used) {
        Forward f = new Forward();
        f.setId(id);
        f.setUserId(7);
        f.setTunnelId((int) (id / 100));
        f.setStatus(status);
        f.setInFlow(used);
        f.setOutFlow(0L);
        f.setCreatedTime(1000L + id);
        return f;
    }

    private static AccountPauseStore.Rec rec(long fid) {
        return new AccountPauseStore.Rec(fid, 7L, 1000L + fid, "flow");
    }

    private static UserTunnel ut(int status, long flowGb, long used, long exp) {
        UserTunnel t = new UserTunnel();
        t.setStatus(status);
        t.setFlow(flowGb);
        t.setInFlow(used);
        t.setOutFlow(0L);
        t.setExpTime(exp);
        return t;
    }

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        forwardService = mock(ForwardService.class);
        userTunnelService = mock(UserTunnelService.class);
        inboundMapper = mock(InboundMapper.class);
        inboundUserMapper = mock(InboundUserMapper.class);
        inboundLineMapper = mock(InboundLineMapper.class);
        store = mock(AccountPauseStore.class);
        r = new AccountLineResume();
        ReflectionTestUtils.setField(r, "userService", userService);
        ReflectionTestUtils.setField(r, "forwardService", forwardService);
        ReflectionTestUtils.setField(r, "userTunnelService", userTunnelService);
        ReflectionTestUtils.setField(r, "inboundMapper", inboundMapper);
        ReflectionTestUtils.setField(r, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(r, "inboundLineMapper", inboundLineMapper);
        ReflectionTestUtils.setField(r, "accountPauseStore", store);

        // 线路:node1 正常、node2 车主手动停、node3 线路自己跑满
        when(inboundLineMapper.selectList(any())).thenReturn(Arrays.asList(
                line(1, 1, 0L, null), line(2, 0, 0L, null), line(3, 1, 10L, null)));
        when(inboundUserMapper.selectList(any())).thenReturn(Arrays.asList(
                iu(11, 101), iu(11, 102), iu(12, 201), iu(13, 301)));
        when(inboundMapper.selectById(11L)).thenReturn(inbound(11, 1));
        when(inboundMapper.selectById(12L)).thenReturn(inbound(12, 2));
        when(inboundMapper.selectById(13L)).thenReturn(inbound(13, 3));
        Forward f101 = fwd(101, 0, 0), f102 = fwd(102, 0, 0), f201 = fwd(201, 0, 0), f301 = fwd(301, 0, 10 * GB);
        when(forwardService.list(any())).thenReturn(
                Arrays.asList(f101, f102), Collections.singletonList(f201), Collections.singletonList(f301));
        when(forwardService.getById(101L)).thenReturn(f101);
        when(forwardService.getById(102L)).thenReturn(f102);
        when(forwardService.getById(201L)).thenReturn(f201);
        when(forwardService.getById(301L)).thenReturn(f301);
        when(forwardService.getById(501L)).thenReturn(fwd(501, 0, 0)); // 老隧道权限转发,权限正常
        when(forwardService.getById(502L)).thenReturn(fwd(502, 0, 0)); // 老隧道权限转发,权限到期
        when(forwardService.getById(601L)).thenReturn(fwd(601, 1, 0)); // 已经被手动恢复过
        Forward other = fwd(701, 0, 0);
        other.setUserId(99); // id 被复用给别人了
        when(forwardService.getById(701L)).thenReturn(other);
        when(userTunnelService.getOne(any())).thenReturn(ut(1, 100, 0, 0), ut(1, 100, 0, 1L));
        when(forwardService.resumeForwardAsSystem(anyLong())).thenReturn(R.ok());
        // 账上:101 201 301 501 502 601 701;102 不在账上(车主在转发页手动停的)
        when(store.listByUser(7L)).thenReturn(Arrays.asList(
                rec(101), rec(201), rec(301), rec(501), rec(502), rec(601), rec(701)));
    }

    @Test
    void resumesOnlyRecordedForwardsWhoseOwnGateIsOpen() {
        when(userService.getById(7L)).thenReturn(user(100L, 50 * GB, 0L, 1));
        assertEquals(2, r.resumeIfAccountOk(7L));
        verify(forwardService).resumeForwardAsSystem(101L);
        verify(forwardService).resumeForwardAsSystem(501L);
        verify(forwardService, never()).resumeForwardAsSystem(102L); // 不在账上:车主手动停的
        verify(forwardService, never()).resumeForwardAsSystem(201L); // 线路被车主停了
        verify(forwardService, never()).resumeForwardAsSystem(301L); // 线路自己跑满
        verify(forwardService, never()).resumeForwardAsSystem(502L); // 隧道权限到期
        verify(forwardService, never()).resumeForwardAsSystem(601L);
        verify(forwardService, never()).resumeForwardAsSystem(701L);
        // 拉起来的、交给别的逻辑的、作废的,记录都删掉
        for (long id : new long[]{101, 201, 301, 501, 502, 601, 701}) {
            verify(store).remove(id);
        }
    }

    @Test
    void nothingWhileAccountStillBlocked() {
        when(userService.getById(7L)).thenReturn(user(50L, 70 * GB, 0L, 1)); // 还超着
        assertEquals(0, r.resumeIfAccountOk(7L));
        when(userService.getById(7L)).thenReturn(user(0L, 0, 1L, 1)); // 到期
        assertEquals(0, r.resumeIfAccountOk(7L));
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 0)); // 停用
        assertEquals(0, r.resumeIfAccountOk(7L));
        verify(forwardService, never()).resumeForwardAsSystem(anyLong());
        verify(store, never()).remove(anyLong());
    }

    @Test
    void flowZeroIsUnlimited() {
        when(userService.getById(7L)).thenReturn(user(0L, 999 * GB, 0L, 1));
        assertEquals(2, r.resumeIfAccountOk(7L));
    }

    @Test
    void emptyLedgerTouchesNothing() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(store.listByUser(7L)).thenReturn(Collections.emptyList());
        assertEquals(0, r.resumeIfAccountOk(7L));
        verify(forwardService, never()).resumeForwardAsSystem(anyLong());
    }

    @Test
    void adminIsNeverTouchedAndLedgerCleared() {
        User admin = user(0L, 0, 0L, 1);
        admin.setRoleId(0);
        when(userService.getById(7L)).thenReturn(admin);
        assertEquals(0, r.resumeIfAccountOk(7L));
        verify(forwardService, never()).resumeForwardAsSystem(anyLong());
        verify(store).removeByUser(7L);
    }

    @Test
    void offlineKeepsRecordPermanentFailureDropsIt() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.resumeForwardAsSystem(101L)).thenReturn(R.err("恢复服务失败：节点不在线"));
        when(forwardService.resumeForwardAsSystem(501L)).thenReturn(R.err("该转发已到期,无法恢复"));
        assertEquals(0, r.resumeIfAccountOk(7L, false));
        verify(store, never()).remove(101L); // 节点离线:留着,下一分钟再补
        verify(store).remove(501L);          // 再试也没用
    }

    @Test
    void exceptionsAreSwallowed() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.resumeForwardAsSystem(101L)).thenThrow(new RuntimeException("boom"));
        assertEquals(1, r.resumeIfAccountOk(7L));
        verify(store, never()).remove(101L);
        when(inboundLineMapper.selectList(any())).thenThrow(new RuntimeException("Table 'gost.inbound_line' doesn't exist"));
        assertEquals(0, r.resumeIfAccountOk(7L));
    }

    @Test
    void revivedLinesRecordWhenAccountBlockedElseResume() {
        when(inboundLineMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(line(1, 1, 0L, null)));
        when(userService.getById(7L)).thenReturn(user(1L, 5 * GB, 0L, 1)); // 账号还超着
        assertEquals(0, r.resumeRevivedLines(7L, Collections.singletonList(1L)));
        verify(store).record(argThat(f -> f.getId() == 101L), eq(AccountPauseStore.REASON_LINE_RESET));
        verify(store).record(argThat(f -> f.getId() == 102L), eq(AccountPauseStore.REASON_LINE_RESET));
        verify(forwardService, never()).resumeForwardAsSystem(anyLong());

        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.list(any())).thenReturn(Arrays.asList(fwd(101, 0, 0), fwd(102, 0, 0)));
        assertEquals(2, r.resumeRevivedLines(7L, Collections.singletonList(1L)));
    }

    @Test
    void userTunnelGateMatchesLegacyCriteria() {
        assertFalse(AccountLineResume.userTunnelBlocked(1, 100L, 0L, 0L, 0L, NOW));
        assertTrue(AccountLineResume.userTunnelBlocked(0, 100L, 0L, 0L, 0L, NOW));
        assertTrue(AccountLineResume.userTunnelBlocked(1, 100L, 0L, 0L, NOW - 1, NOW));
        assertTrue(AccountLineResume.userTunnelBlocked(1, 1L, GB, 0L, 0L, NOW));
        assertTrue(AccountLineResume.userTunnelBlocked(1, 0L, 0L, 0L, 0L, NOW)); // 老路径 0 也是停
    }

    @Test
    void permanentFailureMessages() {
        assertTrue(AccountLineResume.permanentFailure("该转发已到期,无法恢复"));
        assertTrue(AccountLineResume.permanentFailure("隧道已禁用，无法恢复服务"));
        assertTrue(AccountLineResume.permanentFailure("转发不存在"));
        assertFalse(AccountLineResume.permanentFailure("恢复服务失败：节点不在线"));
        assertFalse(AccountLineResume.permanentFailure("恢复服务失败：等待响应超时"));
        assertFalse(AccountLineResume.permanentFailure(null));
        // 节点被删 / 节点上找不到这条服务(入口或隧道转发远端)
        assertTrue(AccountLineResume.permanentFailure("入口节点不存在"));
        assertTrue(AccountLineResume.permanentFailure("出口节点不存在"));
        assertTrue(AccountLineResume.permanentFailure("恢复服务失败：节点上找不到这条转发的服务(节点重装过就会这样,重新保存一下这条转发再试):service 5_2_0_tcp not found"));
        assertTrue(AccountLineResume.permanentFailure("恢复远端服务失败：service 5_2_0_tls not found"));
    }

    @Test
    void retryBudgetCapsEachRound() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        AccountLineResume.Budget b = new AccountLineResume.Budget(1, 30_000L);
        assertEquals(1, r.resumeIfAccountOk(7L, false, b));
        assertTrue(b.exhausted());
        verify(forwardService, times(1)).resumeForwardAsSystem(anyLong());
        verify(store, never()).remove(501L); // 没轮到的留给下一轮
        assertTrue(new AccountLineResume.Budget(5, -1L).exhausted()); // 时间用完也停
    }

    @Test
    void offlineMarksRetryingAndBlockedClearsIt() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.resumeForwardAsSystem(101L)).thenReturn(R.err("恢复服务失败：节点不在线"));
        r.resumeIfAccountOk(7L, false);
        verify(store).markRetrying(101L);
        when(userService.getById(7L)).thenReturn(user(0L, 0, 1L, 1)); // 又到期了
        r.resumeIfAccountOk(7L, false);
        verify(store).clearRetrying(7L);
    }

    @Test
    void thrownNodeMissingIsPermanent() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.resumeForwardAsSystem(101L)).thenThrow(new RuntimeException("节点不存在"));
        when(forwardService.resumeForwardAsSystem(501L)).thenThrow(new RuntimeException("connection reset"));
        r.resumeIfAccountOk(7L, false);
        verify(store).remove(101L);
        verify(store, never()).remove(501L);
        verify(store).markRetrying(501L);
    }
}
