package com.admin.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 删协议/线路/取消分配:哪些失败算「节点没答复」(照删),哪些是 gost 明确报错(拒绝) */
class InboundDeleteUnreachableTest {

    @Test
    void unreachable() {
        assertTrue(InboundServiceImpl.isNodeUnreachable(null));
        assertTrue(InboundServiceImpl.isNodeUnreachable("节点不在线"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("节点连接已断开"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("等待响应超时"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("节点无响应"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("发送消息失败: Broken pipe"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("隧道不存在"));
        assertTrue(InboundServiceImpl.isNodeUnreachable("入口节点不存在"));
    }

    @Test
    void gostErrorsAreRefused() {
        assertFalse(InboundServiceImpl.isNodeUnreachable("save config failed: permission denied"));
        assertFalse(InboundServiceImpl.isNodeUnreachable("无响应消息"));
        assertFalse(InboundServiceImpl.isNodeUnreachable("chain 5_2_0_chains is in use"));
    }
}
