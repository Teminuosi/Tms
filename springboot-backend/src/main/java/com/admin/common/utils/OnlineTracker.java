package com.admin.common.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车友「在用」状态:只在内存里记每个车友 / 每条转发最后一次有流量上报的时间。
 * <p>
 * 【依据】节点上 gost 每个服务每 5 秒看一次,只有这 5 秒里有流量才上报(go-gost/x/service/service.go observeStats,
 * u+d>0 才 sendTrafficReport);服务名是 "转发id_用户id_用户隧道id" 后面再跟 _tcp / _udp / _tls。
 * 每个车友都有自己的 gost 端口,所以「他那几个服务最近有流量」= 「他在用」。
 * <p>
 * 不落库、不改表:面板重启后从头记(重启前的「最后在用」会丢,界面显示「—」)。
 * 任何异常都不能影响流量记账 —— 调用方 try/catch 包着,这里本身也不抛。
 */
public final class OnlineTracker {

    /** 超过这么久没流量的记录在取快照时顺手清掉,免得删掉的转发一直占内存 */
    static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    private static final ConcurrentHashMap<Long, Long> USERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> FORWARDS = new ConcurrentHashMap<>();

    private OnlineTracker() {
    }

    /**
     * 从服务名里取 [转发id, 用户id]。认不出来返回 null(比如 "web_api"、空串、非数字)。
     * 后缀 _tcp / _udp / _tls 以及以后可能多出来的段都不影响:只看前两段。
     */
    public static long[] parse(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        String[] parts = serviceName.trim().split("_");
        if (parts.length < 2) {
            return null;
        }
        long fwd = positiveLong(parts[0]);
        long uid = positiveLong(parts[1]);
        if (fwd <= 0 || uid <= 0) {
            return null;
        }
        return new long[]{fwd, uid};
    }

    private static long positiveLong(String s) {
        if (s == null || s.isEmpty() || s.length() > 18) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Long.parseLong(s);
    }

    /** 记一次上报。u+d 不大于 0、服务名认不出来就什么都不做。 */
    public static void record(String serviceName, Long u, Long d, long nowMs) {
        long up = u == null ? 0L : u;
        long down = d == null ? 0L : d;
        if (up <= 0 && down <= 0) {
            return;
        }
        long[] ids = parse(serviceName);
        if (ids == null) {
            return;
        }
        FORWARDS.merge(ids[0], nowMs, Math::max);
        USERS.merge(ids[1], nowMs, Math::max);
    }

    /** {users:{userId: lastSeenMs}, forwards:{forwardId: lastSeenMs}, now: 面板当前毫秒} */
    public static Map<String, Object> snapshot(long nowMs) {
        Map<String, Object> out = new HashMap<>();
        out.put("users", copy(USERS, nowMs));
        out.put("forwards", copy(FORWARDS, nowMs));
        out.put("now", nowMs);
        return out;
    }

    private static Map<String, Long> copy(ConcurrentHashMap<Long, Long> src, long nowMs) {
        Map<String, Long> m = new HashMap<>();
        for (Map.Entry<Long, Long> e : src.entrySet()) {
            Long t = e.getValue();
            if (t == null || nowMs - t > KEEP_MS) {
                src.remove(e.getKey(), t);
                continue;
            }
            m.put(String.valueOf(e.getKey()), t);
        }
        return m;
    }

    /** 只给测试用 */
    static void clear() {
        USERS.clear();
        FORWARDS.clear();
    }
}
