package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.Map;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface ForwardService extends IService<Forward> {

    /**
     * 创建端口转发
     * @param forwardDto 转发数据
     * @return 结果
     */
    R createForward(ForwardDto forwardDto);

    /**
     * 获取端口转发列表
     * @return 结果
     */
    R getAllForwards();

    /**
     * 更新端口转发
     * @param forwardUpdateDto 更新数据
     * @return 结果
     */
    R updateForward(ForwardUpdateDto forwardUpdateDto);

    /**
     * 删除端口转发
     * @param id 转发ID
     * @return 结果
     */
    R deleteForward(Long id);

    /**
     * 强制删除端口转发
     * 跳过GOST节点验证，直接删除数据库记录
     * @param id 转发ID
     * @return 结果
     */
    R forceDeleteForward(Long id);

    /**
     * 暂停转发服务
     * @param id 转发ID
     * @return 结果
     */
    R pauseForward(Long id);

    /**
     * 恢复转发服务
     * @param id 转发ID
     * @return 结果
     */
    R resumeForward(Long id);

    /**
     * 转发诊断功能
     * @param id 转发ID
     * @return 诊断结果
     */
    R diagnoseForward(Long id);

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数
     * @return 更新结果
     */
    R updateForwardOrder(Map<String, Object> params);


    void updateForwardA(Forward forward);

    /**
     * 指定用户建端口转发,并把建好的 Forward 放进 R.data 返回(合体面板 InboundService 用)。
     * 跳过自助权限校验(管理员代建),转发归属传入用户,便于按用户汇总流量/限速/到期。
     * @param forwardDto 转发数据(tunnelId/remoteAddr/inPort/speedId/expTime 等)
     * @param userId     归属用户(子账号)ID
     * @param userName   归属用户名
     * @return R.ok(Forward) 或 R.err
     */
    R createForwardForUser(ForwardDto forwardDto, Integer userId, String userName);

    /**
     * 把一条已有的转发「分给车友」:同隧道、同目标,另建一条归他名下的转发。
     * 取消分配 = 删掉那条,走现成的 deleteForward。
     */
    R assignForwardToUser(Long forwardId, Integer userId, Integer speedId, Long expTime);
}
