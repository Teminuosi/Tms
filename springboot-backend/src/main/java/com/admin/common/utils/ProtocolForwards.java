package com.admin.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * 按条件查转发,排除协议/线路转发。
     * 老部署走 update 升级上来、从没建过协议的,库里可能根本没有 inbound_user 表 ——
     * 那就不存在协议转发,按原条件查(和以前一模一样),不能因为子查询报错把隧道权限检查/流量上报弄挂。
     * 其它错误照常抛。
     *
     * @param where 每次调用返回一个新的查询条件(排除失败时要用干净的条件重查)
     */
    public static <T> List<T> listExcluding(Function<QueryWrapper<T>, List<T>> lister, Supplier<QueryWrapper<T>> where) {
        try {
            return lister.apply(exclude(where.get()));
        } catch (RuntimeException e) {
            if (!mentionsInboundUser(e)) {
                throw e;
            }
            return lister.apply(where.get());
        }
    }

    private static boolean mentionsInboundUser(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("inbound_user") && m.contains("doesn't exist")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
