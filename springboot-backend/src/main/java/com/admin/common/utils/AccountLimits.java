package com.admin.common.utils;

import com.admin.entity.InboundLine;
import com.admin.entity.User;

/**
 * 车友账号 / 协议线路「该不该停」的判据,全是纯函数,方便单测。
 *
 * 账号总流量(user.flow,单位 GB)对协议 / 中转线路也生效(2026-11-17 三月定):
 * 账号已用(user.in_flow + out_flow)>= flow GB → 这个人名下所有转发一起停,和账号到期 / 停用同一个总闸。
 * flow 为 0 或空 = 不限(客户端「账号总流量(GB,0=不限)」就是这么写的)。
 * 注意:老的隧道权限转发(FlowController.checkUserRelatedLimits)对 0 的处理是「停」,那条路不动。
 */
public final class AccountLimits {

    public static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private static final int ADMIN_ROLE_ID = 0;

    private AccountLimits() {
    }

    /** 账号总流量是否跑满。flowGb 为 null / <=0 = 不限;大到乘出来溢出的也当不限。 */
    public static boolean flowExceeded(Long flowGb, Long inFlow, Long outFlow) {
        if (flowGb == null || flowGb <= 0) {
            return false;
        }
        if (flowGb > Long.MAX_VALUE / BYTES_PER_GB) {
            return false;
        }
        long used = (inFlow == null ? 0L : inFlow) + (outFlow == null ? 0L : outFlow);
        return used >= flowGb * BYTES_PER_GB;
    }

    /** 到期:exp_time 为 null / 0 = 永久 */
    public static boolean expired(Long expTime, long now) {
        return expTime != null && expTime > 0 && expTime <= now;
    }

    /** 账号被停用(和 FlowController.checkUserAccountLimits 的判法一致:status 有值且不是 1) */
    public static boolean disabled(Integer status) {
        return status != null && status != 1;
    }

    public static boolean isAdmin(User u) {
        return u != null && u.getRoleId() != null && u.getRoleId() == ADMIN_ROLE_ID;
    }

    /** 账号总流量是否让这个人该停。管理员(车主自己)不算 —— 车主的转发不能被自己账号的流量数停掉。 */
    public static boolean accountFlowExceeded(User u) {
        return u != null && !isAdmin(u) && flowExceeded(u.getFlow(), u.getInFlow(), u.getOutFlow());
    }

    /** 账号总闸是否关着:到期 / 停用 / 账号总流量跑满,任一即是 */
    public static boolean accountBlocked(User u, long now) {
        if (u == null) {
            return true;
        }
        return expired(u.getExpTime(), now) || disabled(u.getStatus()) || accountFlowExceeded(u);
    }

    /** 线路自己的配额是否跑满(flow 0/null = 不单独限)。判据和 CheckExpiryAsync / InboundServiceImpl.updateLine 一致。 */
    public static boolean lineDepleted(Long lineFlowGb, long usedBytes) {
        if (lineFlowGb == null || lineFlowGb <= 0) {
            return false;
        }
        if (lineFlowGb > Long.MAX_VALUE / BYTES_PER_GB) {
            return false;
        }
        return usedBytes >= lineFlowGb * BYTES_PER_GB;
    }

    /**
     * 账号总闸放开以后,这条线路的转发能不能跟着拉起来:
     * 线路本身必须是 status=1(没被车主手动停、没因自己跑满 / 到期被停),
     * 并且按线路自己的判据(和 InboundServiceImpl.updateLine「改完之后这条线还该不该停」一样)也不该停。
     */
    public static boolean lineMayResume(InboundLine line, long usedBytes, long now) {
        if (line == null || line.getStatus() == null || line.getStatus() != 1) {
            return false;
        }
        if (expired(line.getExpTime(), now)) {
            return false;
        }
        return !lineDepleted(line.getFlow(), usedBytes);
    }
}
