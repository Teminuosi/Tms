package com.admin.common.utils;

import com.admin.entity.Forward;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 按 user_tunnel 查转发时排除协议/线路转发(inbound_user.gost_forward_id 指向的)。 */
class ProtocolForwardsTest {

    @Test
    void excludeAppendsNotInSubquery_keepsOriginalConditions() {
        QueryWrapper<Forward> qw = new QueryWrapper<Forward>().eq("tunnel_id", 311).eq("user_id", 3);
        String sql = ProtocolForwards.exclude(qw).getSqlSegment();
        assertTrue(sql.contains("tunnel_id ="), sql);
        assertTrue(sql.contains("user_id ="), sql);
        assertTrue(sql.contains("id NOT IN (select gost_forward_id from inbound_user where gost_forward_id is not null)"), sql);
    }
}
