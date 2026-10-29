package com.admin.common.utils;

import com.admin.entity.Forward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动批量暂停「原名 not found → 节点不动、库里不写」之后的配套。
 *
 * 老版本遇到这种情况会把转发写成 status=0(节点上其实还在跑)。之后那些按 status 挑转发的
 * 自动任务(每分钟的账号/线路巡检、单条转发到期、每天 0 点的账号/隧道权限到期)就不会再碰它。
 * 新版本库里还是原来的状态,如果不管,这些任务会:
 *   - 每分钟按同一个名字再发一次 not found 的暂停(老版本只发一次);
 *   - 或者被另一个自动任务换个名字真停掉(老版本因为库里已经是 0 而跳过)。
 * 两者都会让节点收到的命令 / 节点上的效果和老版本不一样。
 *
 * 所以这里在内存里记下「老版本此时已经把它写成 0 了」,这些任务挑转发时把它当成 status=0 跳过。
 * 只要这条转发的 status / updated_time 被别处改过(管理员手动暂停、恢复、编辑、续费都会改),
 * 记录就作废 —— 老版本里这些手动操作同样会把那个脏的 0 改掉。
 * 只在内存里,面板重启后清空(重启后第一次巡检会再按原名发一次 not found 的暂停,节点上照样不动)。
 *
 * 不按 status 挑转发的流量上报路径(FlowController)不查这里:老版本每次上报都照发。
 */
public final class AutoPauseSkips {

    /** forwardId → 记下时的 status|updated_time */
    private static final Map<Long, String> SKIPS = new ConcurrentHashMap<>();

    private AutoPauseSkips() {
    }

    private static String stamp(Forward f) {
        return f.getStatus() + "|" + f.getUpdatedTime();
    }

    /** 自动暂停按原名 not found、跳过写库时调用 */
    public static void remember(Forward f) {
        if (f != null && f.getId() != null) {
            SKIPS.put(f.getId(), stamp(f));
        }
    }

    /** 老版本此时是否已经把这条转发写成了 status=0(节点上没停) */
    public static boolean treatedAsPaused(Forward f) {
        if (f == null || f.getId() == null) {
            return false;
        }
        String s = SKIPS.get(f.getId());
        if (s == null) {
            return false;
        }
        if (Objects.equals(s, stamp(f))) {
            return true;
        }
        SKIPS.remove(f.getId(), s); // 别处改过这条转发,记录作废
        return false;
    }

    /** 从「status 不是 0」挑出来的转发里去掉老版本已写成 0 的 */
    public static List<Forward> withoutTreatedAsPaused(List<Forward> list) {
        if (list == null || list.isEmpty() || SKIPS.isEmpty()) {
            return list;
        }
        List<Forward> out = new ArrayList<>(list.size());
        for (Forward f : list) {
            if (!treatedAsPaused(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /** 测试用 */
    static void clear() {
        SKIPS.clear();
    }
}
