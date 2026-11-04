package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.service.SpeedLimitService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 节点重装后重新保存转发:主服务按原名新建前,先把它引用的限速器补到节点上
 * (gost 找不到限速器会静默当成不限速)。
 */
class ForwardLimiterRecreateTest {

    @Test
    void ruleLimiterIsEnsuredOnTheInNode() {
        SpeedLimitService sls = mock(SpeedLimitService.class);
        when(sls.ensureLimiterOnNode(5, 3L)).thenReturn(R.ok());
        ForwardServiceImpl.ensureLimiterBeforeRecreate(sls, 5, 3L);
        verify(sls).ensureLimiterOnNode(5, 3L);
    }

    @Test
    void noLimiterOrPerUserLimiterIsSkipped() {
        SpeedLimitService sls = mock(SpeedLimitService.class);
        ForwardServiceImpl.ensureLimiterBeforeRecreate(sls, null, 3L);
        // 车友专属限速器(900000000 + userId):规则 id 没存在转发上,补不了,不能拿它当规则 id 去查
        ForwardServiceImpl.ensureLimiterBeforeRecreate(sls, 900000007, 3L);
        verify(sls, never()).ensureLimiterOnNode(any(), any());
    }

    @Test
    void failureDoesNotBlockTheSave() {
        SpeedLimitService sls = mock(SpeedLimitService.class);
        when(sls.ensureLimiterOnNode(5, 3L)).thenReturn(R.err("限速规则不存在"));
        assertDoesNotThrow(() -> ForwardServiceImpl.ensureLimiterBeforeRecreate(sls, 5, 3L));
        SpeedLimitService boom = mock(SpeedLimitService.class);
        when(boom.ensureLimiterOnNode(5, 3L)).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> ForwardServiceImpl.ensureLimiterBeforeRecreate(boom, 5, 3L));
        assertDoesNotThrow(() -> ForwardServiceImpl.ensureLimiterBeforeRecreate(null, 5, 3L));
    }
}
