package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.service.LandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 * 落地(中转出口)前端控制器。粘贴分享链接建落地,可复用分给多台前置机。
 * </p>
 *
 * @author QAQ
 * @since 2026-07-23
 */
@RestController
@RequestMapping("/api/v1/landing")
@CrossOrigin
public class LandingController extends BaseController {

    @Autowired
    private LandingService landingService;

    // 改完落地要重推配置,所以这里也要 InboundService。
    // 放 Controller 而不是 LandingServiceImpl:InboundServiceImpl 已经注入了 LandingService,
    // 反向注入会形成循环依赖,而 Spring Boot 2.6+ 默认禁止循环引用。
    @Autowired
    private com.admin.service.InboundService inboundService;

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody LandingDto dto) {
        return landingService.createLanding(dto);
    }

    @RequireRole
    @PostMapping("/list")
    public R list() {
        return landingService.getLandings();
    }

    /**
     * 改落地:换代理串,或只改个名字。
     *
     * 粉丝反馈原话:「创建完成后,落地出口(socks5)配置无法二次更改……只能删除这个中转协议,
     * 重新再次创建,然后又要去指挥舱删除不需要的协议,逻辑上来说就有点复杂了」。
     *
     * 改完必须把用到它的机器重推一遍 sing-box 配置 —— 否则库里是新出口、机器上跑的还是旧的,
     * 用户会以为「改了没生效」,比不让改更糟。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody LandingDto dto) {
        R res = landingService.updateLanding(dto);
        if (res.getCode() != 0) {
            return res;
        }
        Object nodeIds = null;
        if (res.getData() instanceof com.alibaba.fastjson.JSONObject) {
            nodeIds = ((com.alibaba.fastjson.JSONObject) res.getData()).get("nodeIds");
        }
        StringBuilder failed = new StringBuilder();
        if (nodeIds instanceof java.util.Collection) {
            for (Object one : (java.util.Collection<?>) nodeIds) {
                Long nid;
                try {
                    nid = Long.valueOf(String.valueOf(one));
                } catch (NumberFormatException e) {
                    continue;
                }
                R push = inboundService.pushNodeConfig(nid);
                if (push.getCode() != 0) {
                    failed.append(nid).append(" ");
                }
            }
        }
        if (failed.length() > 0) {
            // 库已经改了,只是有机器没推上。说清楚是哪台,别让人以为整件事失败了。
            return R.err("落地已改,但这些机器的配置没推成功:" + failed.toString().trim()
                    + " —— 机器可能离线,恢复后在「中转」页重新搭建一次即可");
        }
        return R.ok();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) {
        return landingService.deleteLanding(Long.valueOf(String.valueOf(body.get("id"))));
    }

    /** 中转:经前置机测一条落地链接能不能通,回显出口 IP */
    @RequireRole
    @PostMapping("/test")
    public R test(@RequestBody Map<String, Object> body) {
        return landingService.testLanding(
                Long.valueOf(String.valueOf(body.get("nodeId"))),
                String.valueOf(body.get("link")));
    }
}
