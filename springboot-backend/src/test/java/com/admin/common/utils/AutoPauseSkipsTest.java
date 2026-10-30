package com.admin.common.utils;

import com.admin.entity.Forward;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoPauseSkipsTest {

    private static Forward f(long id, int status, Long updated) {
        Forward f = new Forward();
        f.setId(id);
        f.setStatus(status);
        f.setUpdatedTime(updated);
        return f;
    }

    @AfterEach
    void reset() {
        AutoPauseSkips.clear();
    }

    @Test
    void rememberedForwardIsSkippedUntilChangedElsewhere() {
        Forward a = f(1, 1, 100L);
        Forward b = f(2, 1, 100L);
        assertEquals(Arrays.asList(a, b), AutoPauseSkips.withoutTreatedAsPaused(Arrays.asList(a, b)));

        AutoPauseSkips.remember(a);
        // 同一条转发重新从库里读出来(状态和更新时间没变)→ 当作老版本已写成 0,跳过
        assertEquals(Collections.singletonList(b), AutoPauseSkips.withoutTreatedAsPaused(Arrays.asList(f(1, 1, 100L), b)));

        // 别处改过(手动恢复/编辑会改 updated_time)→ 作废,照常处理
        List<Forward> r = AutoPauseSkips.withoutTreatedAsPaused(Arrays.asList(f(1, 1, 200L), b));
        assertEquals(2, r.size());
        // 作废后不再生效
        assertFalse(AutoPauseSkips.treatedAsPaused(f(1, 1, 100L)));
    }

    @Test
    void nullUpdatedTimeWorks() {
        Forward a = f(3, 1, null);
        AutoPauseSkips.remember(a);
        assertTrue(AutoPauseSkips.treatedAsPaused(f(3, 1, null)));
        assertFalse(AutoPauseSkips.treatedAsPaused(f(3, 0, null)));
    }

    @Test
    void persistedAcrossRestart() {
        java.util.Map<Long, String> db = new java.util.HashMap<>();
        java.util.List<String> writes = new java.util.ArrayList<>();
        AutoPauseSkips.Persist p = new AutoPauseSkips.Persist() {
            public void save(long id, String stamp) { db.put(id, stamp); writes.add("save " + id); }
            public void delete(long id, String stamp) { db.remove(id, stamp); writes.add("delete " + id); }
        };
        AutoPauseSkips.attach(p, null);
        AutoPauseSkips.remember(f(7, 1, 100L));
        AutoPauseSkips.remember(f(7, 1, 100L)); // 同样的记录不重复写库(流量上报每几秒都会来一次)
        assertEquals(Collections.singletonList("save 7"), writes);

        // 「重启」:内存清空,从库里装回来,照样跳过
        AutoPauseSkips.clear();
        AutoPauseSkips.attach(p, new java.util.HashMap<>(db));
        assertTrue(AutoPauseSkips.treatedAsPaused(f(7, 1, 100L)));

        // 别处改过 → 作废,库里那条也删
        assertFalse(AutoPauseSkips.treatedAsPaused(f(7, 1, 200L)));
        assertTrue(db.isEmpty());
    }
}
