package com.admin.monitor;

import com.admin.common.lang.R;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 公开状态页的数据,不用登录(/api/v1/open_api/** 本来就不走 JWT)。
 * 默认关闭;打开后也只给名字、在线、CPU/内存/网速、延迟 —— 不给 IP、到期、额度。
 * 结果缓存 10 秒,别让人刷这个接口刷到数据库。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/open_api/status")
public class StatusController {

    @Resource
    private MonitorSettings settings;
    @Resource
    private OverviewService overview;

    private volatile long cachedAt;
    private volatile Map<String, Object> cached;

    @GetMapping
    public R status() {
        Map<String, String> s = settings.all();
        if (!"1".equals(s.get(MonitorSettings.STATUS_ENABLED))) {
            return R.ok(Map.of("enabled", false));
        }
        long now = System.currentTimeMillis();
        Map<String, Object> c = cached;
        if (c == null || now - cachedAt > 10_000) {
            List<Map<String, Object>> nodes = overview.build(true);
            c = Map.of(
                    "enabled", true,
                    "title", s.getOrDefault(MonitorSettings.STATUS_TITLE, "节点状态"),
                    "nodes", nodes,
                    "updatedAt", now);
            cached = c;
            cachedAt = now;
        }
        return R.ok(c);
    }
}
