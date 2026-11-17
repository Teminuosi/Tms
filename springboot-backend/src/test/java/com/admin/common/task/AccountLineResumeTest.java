package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.User;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 账号放开后只拉「status=1、自己也不该停」的线路里停着的转发 */
class AccountLineResumeTest {

    private static final long GB = 1024L * 1024L * 1024L;

    private UserService userService;
    private ForwardService forwardService;
    private InboundMapper inboundMapper;
    private InboundUserMapper inboundUserMapper;
    private InboundLineMapper inboundLineMapper;
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
        f.setStatus(status);
        f.setInFlow(used);
        f.setOutFlow(0L);
        return f;
    }

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        forwardService = mock(ForwardService.class);
        inboundMapper = mock(InboundMapper.class);
        inboundUserMapper = mock(InboundUserMapper.class);
        inboundLineMapper = mock(InboundLineMapper.class);
        r = new AccountLineResume();
        ReflectionTestUtils.setField(r, "userService", userService);
        ReflectionTestUtils.setField(r, "forwardService", forwardService);
        ReflectionTestUtils.setField(r, "inboundMapper", inboundMapper);
        ReflectionTestUtils.setField(r, "inboundUserMapper", inboundUserMapper);
        ReflectionTestUtils.setField(r, "inboundLineMapper", inboundLineMapper);

        // 四条线路:node1 正常(转发停着)、node2 车主手动停、node3 线路自己跑满、node4 线路自己到期
        when(inboundLineMapper.selectList(any())).thenReturn(Arrays.asList(
                line(1, 1, 0L, null),
                line(2, 0, 0L, null),
                line(3, 1, 10L, null),
                line(4, 1, 0L, 1L)));
        when(inboundUserMapper.selectList(any())).thenReturn(Arrays.asList(
                iu(11, 101), iu(11, 102), iu(12, 201), iu(13, 301), iu(14, 401)));
        when(inboundMapper.selectById(11L)).thenReturn(inbound(11, 1));
        when(inboundMapper.selectById(12L)).thenReturn(inbound(12, 2));
        when(inboundMapper.selectById(13L)).thenReturn(inbound(13, 3));
        when(inboundMapper.selectById(14L)).thenReturn(inbound(14, 4));
        // lineForwards 按 inbound_user 出现顺序逐条线路查
        when(forwardService.list(any())).thenReturn(
                Arrays.asList(fwd(101, 0, 5 * GB), fwd(102, 1, 0)),
                Collections.singletonList(fwd(201, 0, 0)),
                Collections.singletonList(fwd(301, 0, 10 * GB)),
                Collections.singletonList(fwd(401, 0, 0)));
        when(forwardService.resumeForwardAsSystem(anyLong())).thenReturn(R.ok());
    }

    @Test
    void resumesOnlyPausedForwardsOfHealthyLines() {
        when(userService.getById(7L)).thenReturn(user(100L, 50 * GB, 0L, 1));
        assertEquals(1, r.resumeIfAccountOk(7L));
        verify(forwardService).resumeForwardAsSystem(101L);
        verify(forwardService, never()).resumeForwardAsSystem(102L); // 本来就在跑
        verify(forwardService, never()).resumeForwardAsSystem(201L); // 线路被车主停了
        verify(forwardService, never()).resumeForwardAsSystem(301L); // 线路自己跑满
        verify(forwardService, never()).resumeForwardAsSystem(401L); // 线路自己到期
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
    }

    @Test
    void flowZeroIsUnlimited() {
        when(userService.getById(7L)).thenReturn(user(0L, 999 * GB, 0L, 1));
        assertEquals(1, r.resumeIfAccountOk(7L));
    }

    @Test
    void adminIsNeverTouched() {
        User admin = user(0L, 0, 0L, 1);
        admin.setRoleId(0);
        when(userService.getById(7L)).thenReturn(admin);
        assertEquals(0, r.resumeIfAccountOk(7L));
        verify(forwardService, never()).resumeForwardAsSystem(anyLong());
    }

    @Test
    void failuresAreSwallowed() {
        when(userService.getById(7L)).thenReturn(user(0L, 0, 0L, 1));
        when(forwardService.resumeForwardAsSystem(101L)).thenThrow(new RuntimeException("node offline"));
        assertEquals(0, r.resumeIfAccountOk(7L));
        when(inboundLineMapper.selectList(any())).thenThrow(new RuntimeException("Table 'gost.inbound_line' doesn't exist"));
        assertEquals(0, r.resumeIfAccountOk(7L));
    }

    @Test
    void shouldTryResumeOnlyOnBlockedToOpen() {
        long now = System.currentTimeMillis();
        User over = user(50L, 70 * GB, 0L, 1);
        User raised = user(100L, 70 * GB, 0L, 1);
        User unlimited = user(0L, 70 * GB, 0L, 1);
        User expired = user(0L, 0, 1L, 1);
        User renewed = user(0L, 0, now + 86_400_000L, 1);
        User disabled = user(0L, 0, 0L, 0);
        User enabled = user(0L, 0, 0L, 1);
        assertTrue(AccountLineResume.shouldTryResume(over, raised, now));
        assertTrue(AccountLineResume.shouldTryResume(over, unlimited, now));
        assertTrue(AccountLineResume.shouldTryResume(expired, renewed, now));
        assertTrue(AccountLineResume.shouldTryResume(disabled, enabled, now));
        assertFalse(AccountLineResume.shouldTryResume(raised, raised, now)); // 改名字 / 密码
        assertFalse(AccountLineResume.shouldTryResume(over, over, now));     // 还超着
        assertFalse(AccountLineResume.shouldTryResume(enabled, disabled, now));
        assertFalse(AccountLineResume.shouldTryResume(null, enabled, now));
    }
}
