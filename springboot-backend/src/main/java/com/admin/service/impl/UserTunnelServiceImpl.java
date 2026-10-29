package com.admin.service.impl;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelQueryDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.dto.UserTunnelWithDetailDto;
import com.admin.common.lang.R;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.common.utils.GostDeleteFallback;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户隧道权限服务实现类
 * 提供用户隧道权限的分配、查询、更新和删除功能
 * 支持流量限制、数量限制、过期时间和限速规则的管理
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class UserTunnelServiceImpl extends ServiceImpl<UserTunnelMapper, UserTunnel> implements UserTunnelService {

    // ========== 常量定义 ==========
    
    /** 成功响应消息 */
    private static final String SUCCESS_ASSIGN_MSG = "用户隧道权限分配成功";
    private static final String SUCCESS_REMOVE_MSG = "用户隧道权限删除成功";
    private static final String SUCCESS_UPDATE_FLOW_MSG = "用户隧道流量限制更新成功";
    private static final String SUCCESS_UPDATE_MSG = "用户隧道权限更新成功";
    
    /** 错误响应消息 */
    private static final String ERROR_ASSIGN_FAILED = "用户隧道权限分配失败";
    private static final String ERROR_PERMISSION_EXISTS = "该用户已拥有此隧道权限";
    private static final String ERROR_PERMISSION_NOT_FOUND = "未找到对应的用户隧道权限记录";
    private static final String ERROR_USER_TUNNEL_NOT_EXISTS = "用户隧道权限不存在";
    private static final String ERROR_NOT_EXISTS = "不存在";
    private static final String ERROR_UPDATE_FAILED = "用户隧道权限更新失败";

    // ========== 依赖注入 ==========
    
    @Autowired
    @Lazy
    private ForwardService forwardService;
    
    @Autowired
    @Lazy
    private TunnelService tunnelService;
    
    @Autowired
    private NodeService nodeService;

    // ========== 公共接口实现 ==========

    /**
     * 分配用户隧道权限
     * 检查权限是否已存在，避免重复分配
     * 
     * @param userTunnelDto 用户隧道权限分配数据传输对象
     * @return 分配结果响应
     */
    @Override
    public R assignUserTunnel(UserTunnelDto userTunnelDto) {
        // 1. 检查权限是否已存在
        if (isUserTunnelPermissionExists(userTunnelDto.getUserId(), userTunnelDto.getTunnelId())) {
            return R.err(ERROR_PERMISSION_EXISTS);
        }
        
        // 2. 创建用户隧道权限实体并保存
        UserTunnel userTunnel = buildUserTunnelEntity(userTunnelDto);
        // 设置默认状态为启用
        userTunnel.setStatus(1);
        boolean success = this.save(userTunnel);
        
        if (success) {
            return R.ok(SUCCESS_ASSIGN_MSG);
        }
        
        return R.err(ERROR_ASSIGN_FAILED);
    }

    /**
     * 获取用户隧道权限列表
     * 通过连表查询获取用户隧道权限及隧道详细信息
     * 
     * @param queryDto 用户隧道权限查询数据传输对象
     * @return 用户隧道权限详情列表响应
     */
    @Override
    public R getUserTunnelList(UserTunnelQueryDto queryDto) {
        List<UserTunnelWithDetailDto> userTunnelDetails = getUserTunnelDetailsFromDatabase(queryDto.getUserId());
        return R.ok(userTunnelDetails);
    }

    /**
     * 删除用户隧道权限
     * 
     * @param id 用户隧道权限ID
     * @return 删除结果响应
     */
    @Override
    public R removeUserTunnel(Integer id) {
        // 1. 获取用户隧道权限信息
        UserTunnel userTunnel = this.getById(id);
        if (userTunnel == null) {
            return R.err(ERROR_PERMISSION_NOT_FOUND);
        }
        
        // 2. 删除该用户在该隧道下的所有转发
        try {
            removeUserTunnelForwards(userTunnel.getUserId(), userTunnel.getTunnelId());
        } catch (Exception e) {
            // 转发删除失败，记录日志但不阻止权限删除
        }

        
        // 4. 删除用户隧道权限记录
        boolean success = this.removeById(id);
        return success ? R.ok(SUCCESS_REMOVE_MSG) : R.err(ERROR_PERMISSION_NOT_FOUND);
    }


    /**
     * 更新用户隧道权限
     * 支持更新流量限制、数量限制、流量重置时间、过期时间和限速规则
     * 
     * @param updateDto 用户隧道权限更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateUserTunnel(UserTunnelUpdateDto updateDto) {
        // 1. 验证用户隧道权限是否存在
        UserTunnel existingUserTunnel = this.getById(updateDto.getId());
        if (existingUserTunnel == null) {
            return R.err(ERROR_USER_TUNNEL_NOT_EXISTS);
        }
        
        // 2. 检查是否更新了限速规则
        boolean speedChanged = hasSpeedChanged(existingUserTunnel.getSpeedId(), updateDto.getSpeedId());
        
        // 3. 更新用户隧道权限属性
        updateUserTunnelProperties(existingUserTunnel, updateDto);
        
        // 4. 保存更新
        boolean success = this.updateById(existingUserTunnel);
        
        if (success) {
            // 6. 如果限速规则发生变化，更新该用户隧道下的所有转发
            if (speedChanged) {
                updateUserTunnelForwardsSpeed(existingUserTunnel.getUserId(), existingUserTunnel.getTunnelId(), updateDto.getSpeedId());
            }
            
            return R.ok(SUCCESS_UPDATE_MSG);
        }
        
        return R.err(ERROR_UPDATE_FAILED);
    }

    // ========== 私有辅助方法 ==========

    /**
     * 检查用户隧道权限是否已存在
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @return 权限是否已存在
     */
    private boolean isUserTunnelPermissionExists(Integer userId, Integer tunnelId) {
        QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
        UserTunnel existingUserTunnel = this.getOne(queryWrapper);
        return existingUserTunnel != null;
    }

    /**
     * 构建用户隧道权限实体对象
     * 
     * @param userTunnelDto 用户隧道权限DTO
     * @return 构建完成的用户隧道权限对象
     */
    private UserTunnel buildUserTunnelEntity(UserTunnelDto userTunnelDto) {
        UserTunnel userTunnel = new UserTunnel();
        BeanUtils.copyProperties(userTunnelDto, userTunnel);
        return userTunnel;
    }

    /**
     * 从数据库获取用户隧道权限详情
     * 
     * @param userId 用户ID
     * @return 用户隧道权限详情列表
     */
    private List<UserTunnelWithDetailDto> getUserTunnelDetailsFromDatabase(Integer userId) {
        return this.baseMapper.getUserTunnelWithDetails(userId);
    }

    /**
     * 更新用户隧道权限属性
     * 
     * @param existingUserTunnel 现有的用户隧道权限对象
     * @param updateDto 更新数据传输对象
     */
    private void updateUserTunnelProperties(UserTunnel existingUserTunnel, UserTunnelUpdateDto updateDto) {
        // 更新基本属性
        existingUserTunnel.setFlow(updateDto.getFlow());
        existingUserTunnel.setNum(updateDto.getNum());
        
        // 更新可选属性（仅在非空时更新）
        updateOptionalProperty(existingUserTunnel::setFlowResetTime, updateDto.getFlowResetTime());
        updateOptionalProperty(existingUserTunnel::setExpTime, updateDto.getExpTime());
        updateOptionalProperty(existingUserTunnel::setStatus, updateDto.getStatus());
        
        // 更新限速规则ID（允许设置为null，表示不限速）
        existingUserTunnel.setSpeedId(updateDto.getSpeedId());
    }

    /**
     * 更新可选属性（仅在值非空时更新）
     * 
     * @param setter 属性设置方法
     * @param value 属性值
     * @param <T> 属性类型
     */
    private <T> void updateOptionalProperty(java.util.function.Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }
    

    
    /**
     * 删除用户在指定隧道下的所有转发
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     */
    private void removeUserTunnelForwards(Integer userId, Integer tunnelId) {
        // 查询该用户在该隧道下的所有转发
        QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);

        List<Forward> userTunnelForwards = forwardService.list(queryWrapper);

        if (!userTunnelForwards.isEmpty()) {
            // 获取用户隧道权限信息，用于构建服务名称
            UserTunnel userTunnel = getUserTunnelByUserAndTunnel(userId, tunnelId);

            for (Forward forward : userTunnelForwards) {
                try {
                    // 先调用GostUtil删除/停止服务
                    stopForwardService(forward, userId, userTunnel != null ? userTunnel.getId() : 0);

                    // 然后删除数据库记录
                    forwardService.removeById(forward.getId());

                } catch (Exception e) {
                    // 单个转发删除失败，记录错误但继续处理其他转发
                }
            }

        }
    }
    
    /**
     * 删除转发服务（按创建的反向顺序删除：主服务 -> 远端服务 -> 转发链）
     * 
     * @param forward 转发对象
     * @param userId 用户ID
     * @param userTunnelId 用户隧道ID
     */
    private void stopForwardService(Forward forward, Integer userId, Integer userTunnelId) {
        try {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) {
                return;
            }

            Node inNode = nodeService.getById(tunnel.getInNodeId());
            Node outNode = nodeService.getById(tunnel.getOutNodeId());
            
            // 先按原名删,原名 not found 才按 _0 逐个确认(GostDeleteFallback)——
            // 协议/线路/分给车友的转发建的时候恒为 _0,按 user_tunnel id 拼的名字对不上
            List<String> names = GostDeleteFallback.candidateNames(forward.getId(), userId, userTunnelId);

            // 1. 先删除主服务
            if (inNode != null) {
                try {
                    Long inNodeId = inNode.getId();
                    GostDeleteFallback.delete(names,
                            n -> GostUtil.DeleteService(inNodeId, n),
                            Arrays.asList(n -> GostUtil.DeleteOneService(inNodeId, n + "_tcp"),
                                    n -> GostUtil.DeleteOneService(inNodeId, n + "_udp")));
                } catch (Exception e) {
                    // 主服务删除失败，记录但继续
                }
            }

            // 2. 如果是隧道转发，删除远端服务
            if (tunnel.getType() == 1 && outNode != null && !outNode.getId().equals(inNode != null ? inNode.getId() : null)) {
                try {
                    GostUtil.DeleteRemoteService(outNode.getId(), names.get(0));
                } catch (Exception e) {
                    // 远端服务删除失败，记录但继续
                }
            }

            // 3. 如果是隧道转发，最后删除转发链
            if (tunnel.getType() == 1 && inNode != null) {
                try {
                    GostUtil.DeleteChains(inNode.getId(), names.get(0));
                } catch (Exception e) {
                    // 转发链删除失败，记录但继续
                }
            }
            
        } catch (Exception e) {
            // 服务删除失败，记录错误
            throw new RuntimeException("删除转发服务失败，转发ID：" + forward.getId() + "，错误：" + e.getMessage(), e);
        }
    }
    
    /**
     * 根据用户ID和隧道ID获取用户隧道权限
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @return 用户隧道权限对象
     */
    private UserTunnel getUserTunnelByUserAndTunnel(Integer userId, Integer tunnelId) {
        try {
            QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
            return this.getOne(queryWrapper);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 构建服务名称
     * 
     * @param forwardId 转发ID
     * @param userId 用户ID
     * @param userTunnelId 用户隧道ID
     * @return 服务名称
     */
    private String buildServiceName(Long forwardId, Long userId, Integer userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    /**
     * 检查用户隧道是否启用且有到期时间
     * 
     * @param userTunnel 用户隧道对象
     * @return 是否启用且有到期时间
     */
    private boolean isEnabledAndHasExpTime(UserTunnel userTunnel) {
        return userTunnel.getStatus() != null && userTunnel.getStatus() == 1 
                && userTunnel.getExpTime() != null;
    }
    
    /**
     * 检查限速规则是否发生变化
     * 
     * @param oldSpeedId 原始限速规则ID
     * @param newSpeedId 新的限速规则ID
     * @return 限速规则是否发生变化
     */
    private boolean hasSpeedChanged(Integer oldSpeedId, Integer newSpeedId) {
        if (oldSpeedId == null && newSpeedId == null) {
            return false;
        }
        if (oldSpeedId == null || newSpeedId == null) {
            return true;
        }
        return !oldSpeedId.equals(newSpeedId);
    }
    
    /**
     * 更新用户隧道下所有转发的限速规则
     * 管理员操作，不需要权限检查，直接查出该用户在该隧道下的所有转发并应用新的限速
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @param speedId 新的限速规则ID
     */
    private void updateUserTunnelForwardsSpeed(Integer userId, Integer tunnelId, Integer speedId) {
        // 1. 查询该用户在该隧道下的所有转发
        QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
        List<Forward> userTunnelForwards = forwardService.list(queryWrapper);

        if (userTunnelForwards.isEmpty()) {
            return;
        }

        // 2. 获取隧道信息
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            return;
        }

        // 3. 获取用户隧道权限信息
        UserTunnel userTunnel = getUserTunnelByUserAndTunnel(userId, tunnelId);
        if (userTunnel == null) {
            return;
        }

        // 4. 获取入口节点信息
        Node inNode = nodeService.getById(tunnel.getInNodeId());

        if (inNode == null) {
            return;
        }

        // 5. 批量更新该用户在该隧道下所有转发的限速配置（只更新入口节点）
        for (Forward forward : userTunnelForwards) {
            String serviceName = buildServiceName(forward.getId(), Long.valueOf(userId), userTunnel.getId());

            String interfaceName = null;
            // 创建主服务
            if (tunnel.getType() != 2) { // 不是隧道转发服务才会存在网络接口
                interfaceName = forward.getInterfaceName();
            }

            // 6. 更新入口节点的主服务限速配置（使用批量UpdateService接口）
            GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), speedId, forward.getRemoteAddr(), tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        }
    }
}
