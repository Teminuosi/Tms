package com.admin.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 协议/线路转发的认法:inbound_user.gost_forward_id 指向的那条转发。
 *
 * 这类转发由 InboundServiceImpl 分配协议时经 createForwardForUser 建,只受【线路】自己的
 * 停用/到期/流量管(inbound_line + FlowController.checkLineRelatedLimits + CheckExpiryAsync)。
 * 车友在同一条隧道上另有手动授权(user_tunnel)时,那份授权到期/超额/停用/改限速
 * 都不该连带停掉或改动协议线路 —— 老版本因为服务名对不上,实际上从来没动到过它们,
 * 在线车友一直是这么用的;按名字修好之后不能突然变成「授权一到期,协议线路跟着断」。
 *
 * forward 表本身没有类型/来源字段;名字 inbound-{入站}-user-{车友} 可以被改,不可靠;
 * inbound_user.gost_forward_id 是分配协议时写下、删协议时一起删的,是唯一可靠的关联。
 */
public final class ProtocolForwards {

    public static final String IDS_SQL =
            "select gost_forward_id from inbound_user where gost_forward_id is not null";

    private ProtocolForwards() {
    }

    /** 在按 user_tunnel 查转发的条件上排除协议/线路转发 */
    public static <T> QueryWrapper<T> exclude(QueryWrapper<T> qw) {
        return qw.notInSql("id", IDS_SQL);
    }
}
