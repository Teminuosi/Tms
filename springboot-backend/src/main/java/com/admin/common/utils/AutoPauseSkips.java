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
 * 记录同时写进库里的 auto_pause_skip 表(AutoPauseSkipsStore),面板重启后读回来 ——
 * 老版本的脏 0 在库里,重启后照样生效;只放内存的话,重启后 CheckExpiryAsync 会按 _0
 * 把老版本一直没停的转发真停掉(真跑坐实过)。写表失败只记日志,退回只在内存。
 *
 * 不按 status 挑转发的流量上报路径(FlowController)不查这里:老版本每次上报都照发。
 */
public final class AutoPauseSkips {

    /** 持久化:由 AutoPauseSkipsStore 在启动时挂上;没挂上(或测试)时只在内存 */
    public interface Persist {
        void save(long forwardId, String stamp);

        void delete(long forwardId, String stamp);
    }

    /** forwardId → 记下时的 status|updated_time */
    private static final Map<Long, String> SKIPS = new ConcurrentHashMap<>();

    private static volatile Persist persist;

    private AutoPauseSkips() {
    }

    /** 启动时调用:挂上持久化,并装入库里已有的记录 */
    public static void attach(Persist p, Map<Long, String> loaded) {
        if (loaded != null) {
            SKIPS.putAll(loaded);
        }
        persist = p;
    }

    private static String stamp(Forward f) {
        return f.getStatus() + "|" + f.getUpdatedTime();
    }

    /** 自动暂停按原名 not found、跳过写库时调用 */
    public static void remember(Forward f) {
        if (f != null && f.getId() != null) {
            String s = stamp(f);
            String prev = SKIPS.put(f.getId(), s);
            Persist p = persist;
            if (p != null && !s.equals(prev)) {
                p.save(f.getId(), s);
            }
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
        if (SKIPS.remove(f.getId(), s)) { // 别处改过这条转发,记录作废
            Persist p = persist;
            if (p != null) {
                p.delete(f.getId(), s);
            }
        }
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
        persist = null;
    }
}
