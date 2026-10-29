package com.admin.common.utils;

import com.admin.common.dto.GostDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 删 gost 服务时的「名字对不上」兜底。
 *
 * 服务名是 {forwardId}_{userId}_{userTunnelId}。协议/线路的转发由 createForwardForUser 建,
 * 建的时候 userTunnel 传 null → 名字恒为 _0;删的时候 deleteForward 却会查到车友在这条
 * 自动隧道上手动授权过的 user_tunnel,拼成 _{utId} → 节点回 not found → 删不掉。
 *
 * 规则:
 *  1. 先按原来的名字删(和以前发给节点的命令一字不差),成功就结束 —— 正常路径不变。
 *  2. 节点回的是「这个名字 not found」才往下走;其它错误(离线、超时)原样返回失败。
 *  3. 逐个候选名、逐个子服务再删一次;每一项要么删成功、要么节点确认不存在。
 *     全部确认过 = 节点上已经没有这条转发的服务,视为删除成功。
 * 候选名全部是这条转发自己的 {forwardId}_{userId}_ 开头,不会碰到别人的服务。
 */
public final class GostDeleteFallback {

    private static final String OK = "OK";
    private static final String NOT_FOUND = "not found";

    private GostDeleteFallback() {
    }

    /** 候选服务名:第一个是原来的拼法;userTunnel 不为 0 时再加上 _0(协议/线路转发的实际名字)。 */
    public static List<String> candidateNames(Long forwardId, Integer userId, Integer userTunnelId) {
        int ut = userTunnelId == null ? 0 : userTunnelId;
        List<String> names = new ArrayList<>();
        names.add(forwardId + "_" + userId + "_" + ut);
        if (ut != 0) {
            names.add(forwardId + "_" + userId + "_0");
        }
        return names;
    }

    public static boolean isOk(GostDto r) {
        return r != null && Objects.equals(r.getMsg(), OK);
    }

    /**
     * 节点明确说「以 name 开头的服务/链不存在」。gost 原文形如
     * "service 5_2_0_tcp not found" / "chain 5_2_0_chains not found"。
     * 必须带上这个名字,防止把别的含 not found 的报错当成「不存在」。
     */
    public static boolean isNotFound(GostDto r, String name) {
        if (r == null || r.getMsg() == null || name == null) {
            return false;
        }
        String msg = r.getMsg();
        return msg.contains(NOT_FOUND) && msg.contains(" " + name + "_");
    }

    /**
     * 暂停 / 恢复 / 更新用:先按原名发(和以前发给节点的命令一字不差),
     * 节点回「原名 not found」才换下一个候选名(_0)再发。
     * gost 的 Pause/Resume/Update 都是先校验全部名字存在再动手,回 not found 时节点上什么都没改,换名重发是安全的。
     * 所有候选都 not found 时返回原名那次的结果 —— 调用方原有的 not found 处理(如更新时转去按原名 Add)照旧。
     * 离线、超时等其它错误不换名,原样返回。
     */
    public static GostDto firstFound(List<String> candidates, Function<String, GostDto> op) {
        return firstFound(candidates, op, null);
    }

    /** 同上;used 不为 null 时 used[0] 记下返回的是哪个名字的结果(都 not found 时是原名)。 */
    public static GostDto firstFound(List<String> candidates, Function<String, GostDto> op, String[] used) {
        GostDto first = null;
        for (int i = 0; i < candidates.size(); i++) {
            String name = candidates.get(i);
            GostDto r = op.apply(name);
            if (i == 0) {
                first = r;
            }
            if (!isNotFound(r, name)) {
                if (used != null) {
                    used[0] = name;
                }
                return r;
            }
        }
        if (used != null) {
            used[0] = candidates.get(0);
        }
        return first;
    }

    /**
     * 所有【自动】批量暂停(账号/隧道权限/线路/单条转发的到期、超额、停用)都走这里:
     * 只按老版本在这个调用点拼出来的原名发(包括老版本没有 user_tunnel 时拼出的 "x_y_null"),不走 _0 兜底。
     * 发给节点的命令和老版本一字不差:入口按原名停;隧道转发再按同一个原名停远端(不管入口回什么,老版本也是照发)。
     * 节点回「原名 not found」→ 返回 false:节点上什么都没变(gost 的 Pause 先校验名字再动手),
     * 调用方跳过写库 —— 不再写出「库里停了、节点还在跑」的状态;并记进 AutoPauseSkips,
     * 让其它按 status 挑转发的自动任务照老版本的样子跳过它(见 AutoPauseSkips)。
     * 其它结果(成功、离线、超时)和老版本一样返回 true,调用方照旧写库。
     *
     * @param pauseRemote 隧道转发才传(停出口机上的远端服务),否则传 null
     */
    public static boolean pauseByOriginalName(String name, Function<String, GostDto> pauseIn, Function<String, GostDto> pauseRemote) {
        GostDto r = pauseIn.apply(name);
        if (pauseRemote != null) {
            pauseRemote.apply(name);
        }
        return !isNotFound(r, name);
    }

    /**
     * @param candidates candidateNames 的结果,第一个是原名
     * @param whole      原来的整条删除命令(按名字)
     * @param pieces     逐个子服务删除(如 _tcp、_udp 各一条);只有一个实体时传空列表
     * @return null = 删掉了或节点上本来就没有;否则是失败原因
     */
    public static String delete(List<String> candidates,
                                Function<String, GostDto> whole,
                                List<Function<String, GostDto>> pieces) {
        String primary = candidates.get(0);
        GostDto r = whole.apply(primary);
        if (isOk(r)) {
            return null;
        }
        if (!isNotFound(r, primary)) {
            return r == null ? "节点无响应" : r.getMsg();
        }
        List<Function<String, GostDto>> ops = (pieces == null || pieces.isEmpty())
                ? Collections.singletonList(whole) : pieces;
        for (String name : candidates) {
            for (Function<String, GostDto> op : ops) {
                if (ops.size() == 1 && op == whole && name.equals(primary)) {
                    continue; // 上面已经确认过原名 not found
                }
                GostDto x = op.apply(name);
                if (isOk(x) || isNotFound(x, name)) {
                    continue;
                }
                return x == null ? "节点无响应" : x.getMsg();
            }
        }
        return null;
    }
}
