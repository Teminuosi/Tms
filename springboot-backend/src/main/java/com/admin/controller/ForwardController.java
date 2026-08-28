package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.ForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardController extends BaseController {

    @Autowired
    private ForwardService forwardService;

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody ForwardDto forwardDto) {
        return forwardService.createForward(forwardDto);
    }

    /**
     * 把一条已有的转发分给车友:同隧道、同目标,另建一条归他名下的
     * (独立入口端口 / 独立限速 / 独立到期 / 独立流量统计)。
     * 取消分配就是删掉那条,走现成的 /delete。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/assign")
    public R assignToUser(@RequestBody Map<String, Object> params) {
        Object fid = params.get("forwardId");
        Object uid = params.get("userId");
        if (fid == null || uid == null) {
            return R.err("参数不完整");
        }
        Object speed = params.get("speedId");
        Object exp = params.get("expTime");
        return forwardService.assignForwardToUser(
                Long.valueOf(fid.toString()),
                Integer.valueOf(uid.toString()),
                speed == null ? null : Integer.valueOf(speed.toString()),
                exp == null ? null : Long.valueOf(exp.toString()));
    }

    /**
     * 写入该转发给车友的客户端链接(聚合订阅里会原样吐出);link 传空即清除。
     * 链接由客户端算好推上来 —— 拼链接的那套逻辑只在客户端有一份,不在两边各写一遍。
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/set-link")
    public R setClientLink(@RequestBody Map<String, Object> params) {
        Object fid = params.get("forwardId");
        if (fid == null) {
            return R.err("参数不完整");
        }
        Object link = params.get("link");
        return forwardService.setForwardClientLink(Long.valueOf(fid.toString()),
                link == null ? null : link.toString());
    }

    @LogAnnotation
    @PostMapping("/list")
    public R readAll() {
        return forwardService.getAllForwards();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody ForwardUpdateDto forwardUpdateDto) {
        return forwardService.updateForward(forwardUpdateDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.deleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/force-delete")
    public R forceDelete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.forceDeleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/pause")
    public R pause(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.pauseForward(id);
    }

    @LogAnnotation
    @PostMapping("/resume")
    public R resume(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.resumeForward(id);
    }

    /**
     * 转发诊断功能
     * @param params 包含forwardId的参数
     * @return 诊断结果
     */
    @LogAnnotation
    @PostMapping("/diagnose")
    public R diagnoseForward(@RequestBody Map<String, Object> params) {
        Long forwardId = Long.valueOf(params.get("forwardId").toString());
        return forwardService.diagnoseForward(forwardId);
    }

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数，每个元素包含id和inx
     * @return 更新结果
     */
    @LogAnnotation
    @PostMapping("/update-order")
    public R updateForwardOrder(@RequestBody Map<String, Object> params) {
        return forwardService.updateForwardOrder(params);
    }

}
