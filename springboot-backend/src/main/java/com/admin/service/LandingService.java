package com.admin.service;

import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.entity.Landing;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 落地(中转出口)服务:粘贴分享链接建落地,可复用分给多台前置机。
 *
 * @author QAQ
 * @since 2026-07-23
 */
public interface LandingService extends IService<Landing> {

    /** 新建落地:解析分享链接 → 存(名称/类型/链接/出站JSON) */
    R createLanding(LandingDto dto);

    /** 落地列表 */
    R getLandings();

    /** 删除落地(被中转入站引用时拒绝) */
    /**
     * 改落地(换代理串 / 改名)。返回 { landing, nodeIds } ——
     * nodeIds 是用到这条落地的机器,调用方要拿它去重推 sing-box 配置。
     */
    R updateLanding(LandingDto dto);

    R deleteLanding(Long id);

    /** 中转:经指定前置机(nodeId)测一条落地链接能不能通,回显出口 IP + 延迟 */
    R testLanding(Long nodeId, String link);
}
