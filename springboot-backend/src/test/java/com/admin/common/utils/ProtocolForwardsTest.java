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

    @Test
    void listExcluding_noInboundUserTable_fallsBackToPlainQuery() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        java.util.List<Forward> r = ProtocolForwards.listExcluding(qw -> {
            String s = qw.getSqlSegment();
            seen.add(s);
            if (s.contains("inbound_user")) {
                throw new RuntimeException("wrap", new java.sql.SQLSyntaxErrorException("Table 'gost.inbound_user' doesn't exist"));
            }
            return java.util.Collections.singletonList(new Forward());
        }, () -> new QueryWrapper<Forward>().eq("tunnel_id", 1));
        assertEquals(1, r.size());
        assertEquals(2, seen.size());
        assertFalse(seen.get(1).contains("inbound_user"), seen.get(1));
    }

    @Test
    void listExcluding_otherErrorsStillThrow() {
        assertThrows(RuntimeException.class, () -> ProtocolForwards.listExcluding(qw -> {
            throw new RuntimeException("Communications link failure");
        }, () -> new QueryWrapper<Forward>().eq("tunnel_id", 1)));
    }
}
