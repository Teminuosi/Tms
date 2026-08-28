package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.InboundMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    /** 自动分配端口时,遇到机器 OS 层被占最多顺延几次 */
    private static final int MAX_PORT_RETRY = 20;
    private static final int TUNNEL_STATUS_ACTIVE = 1;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    // 「把转发分给车友」时下发【车友专属限速器】用
    @Resource
    SpeedLimitService speedLimitService;

    // 合体面板:转发端口分配需避开协议入站占用的 sing-box 本机口
    @Resource
    private InboundMapper inboundMapper;


    @Override
    public R createForward(ForwardDto forwardDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        // 4. 分配端口(留空=自动分配,按节点级避让:该机所有转发的入/出口 + 协议的 sing-box 端口)
        boolean autoPort = forwardDto.getInPort() == null; // 自动分配时,遇到机器上被别的程序占用可自动顺延
        PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage() + suggestPortHint(tunnel));
        }

        // 5~7. 建实体 + 下发 gost;自动分配时若端口在机器 OS 层被占(gost 报 address already in use),
        //      自动换下一个可用端口重试,不让用户自己去猜哪个端口空着
        Integer tryPort = portAllocation.getInPort();
        R lastErr = null;
        for (int attempt = 0; attempt < (autoPort ? MAX_PORT_RETRY : 1); attempt++) {
            PortAllocation pa = PortAllocation.success(tryPort, portAllocation.getOutPort());
            Forward forward = createForwardEntity(forwardDto, currentUser, pa);
            if (!this.save(forward)) {
                return R.err("端口转发创建失败");
            }

            NodeInfo nodeInfo = getRequiredNodes(tunnel);
            if (nodeInfo.isHasError()) {
                this.removeById(forward.getId());
                return R.err(nodeInfo.getErrorMessage());
            }

            R gostResult = createGostServices(forward, tunnel,
                    resolveLimiter(forward, permissionResult.getLimiter()), nodeInfo, permissionResult.getUserTunnel());
            if (gostResult.getCode() == 0) {
                // 把最终落地的端口带回去:留空自动分配(或被占顺延)时,用户得知道分到了哪个口
                return R.ok(java.util.Collections.singletonMap("inPort", forward.getInPort()));
            }

            // 失败:回滚这条记录
            this.removeById(forward.getId());
            lastErr = gostResult;
            String msg = gostResult.getMsg() == null ? "" : gostResult.getMsg();
            boolean portTaken = msg.contains("already in use") || msg.contains("address already");
            if (!autoPort || !portTaken) {
                // 手动指定的端口被占 → 明确告诉用户,并建议一个可用端口
                if (portTaken) {
                    return R.err("端口 " + tryPort + " 在机器上已被其它程序占用。" + suggestPortHint(tunnel));
                }
                return gostResult;
            }
            // 自动分配 + 端口被占 → 顺延到下一个候选端口再试
            Integer next = allocateInPortAfter(tunnel, tryPort);
            if (next == null) {
                break;
            }
            tryPort = next;
        }
        return lastErr != null ? lastErr : R.err("没有可用端口");
    }

    @Override
    public R createForwardForUser(ForwardDto forwardDto, Integer userId, String userName) {
        // 1. 校验隧道(该端口转发隧道由 InboundService 保证入口机=入站节点)
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        // 2. 分配端口(留空则自动排下一个可用)
        PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage());
        }

        // 3. 建 Forward,归属【指定用户】(而非当前登录管理员),便于按用户汇总流量/到期
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardDto, forward);
        if (forward.getStrategy() == null || forward.getStrategy().isEmpty()) {
            forward.setStrategy("fifo");
        }
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(userId);
        forward.setUserName(userName);
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        if (!this.save(forward)) {
            return R.err("端口转发创建失败");
        }

        // 4. 节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            this.removeById(forward.getId());
            return R.err(nodeInfo.getErrorMessage());
        }

        // 5. 下发 gost 服务(限速取 forward.speedId;管理员代建不走自助权限,userTunnel 传 null)
        R gostResult = createGostServices(forward, tunnel, resolveLimiter(forward, null), nodeInfo, null);
        if (gostResult.getCode() != 0) {
            this.removeById(forward.getId());
            return gostResult;
        }

        return R.ok(forward);
    }

    /**
     * 把一条已有的转发「分给车友」:在同一条隧道上、用同一个目标,另建一条归他名下的转发。
     *
     * 【为什么不能让车友直接用管理员那条】gost 的限速器、到期、流量统计全挂在「转发」上,
     * 共用一个入口端口就没法按人计费、也没法单独到期 —— 所以必须一人一条、一人一个端口。
     * 这正是 InboundServiceImpl.assignUser 第 3 步给车友分配协议时在做的事,
     * 这里只是把同一套机制用到裸端口转发/隧道转发上。
     *
     * 【为什么这条一定能用】路径和管理员自己那条完全一致(同隧道、同目标、同一台入口机),
     * 只有入口端口不同。之前那套「建落地 + 在入口机建中转协议」是入口机直连落地、
     * 隧道压根没参与,所以自己能用、车友不能用 —— 那才是上一轮的病根。
     */
    @Override
    public R assignForwardToUser(Long forwardId, Integer userId, Integer speedId, Long expTime) {
        if (forwardId == null || userId == null) {
            return R.err("参数不完整");
        }
        Forward src = this.getById(forwardId);
        if (src == null) {
            return R.err("转发不存在");
        }
        User user = userService.getById(userId.longValue());
        if (user == null) {
            return R.err("车友不存在");
        }
        Tunnel tunnel = validateTunnel(src.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法分配");
        }

        // 查重:分过就把现有那条原样还回去。前端双击/重复提交也不会多出一条来占端口
        String name = "fwd-" + src.getId() + "-user-" + userId;
        Forward existed = this.getOne(new QueryWrapper<Forward>().eq("name", name).last("limit 1"));
        if (existed != null) {
            return R.ok(existed);
        }

        // 限速走【车友专属限速器】,和分配协议同一套:
        // gost 的 UDP 转发只认服务级 `$`、不认 per-IP,每车友独立一个才能既限住 UDP、彼此又不影响
        Integer limiter = null;
        if (speedId != null) {
            limiter = (int) (com.admin.common.task.CheckGostConfigAsync.PER_USER_LIMITER_BASE + userId);
            R lr = speedLimitService.pushUserLimiter(speedId, limiter.longValue(), tunnel.getInNodeId());
            if (lr.getCode() != 0) {
                return R.err("下发限速器失败:" + lr.getMsg());
            }
        }

        ForwardDto dto = new ForwardDto();
        dto.setName(name);
        dto.setTunnelId(src.getTunnelId());
        dto.setRemoteAddr(src.getRemoteAddr());
        dto.setStrategy(src.getStrategy());
        dto.setInterfaceName(src.getInterfaceName());
        dto.setSpeedId(limiter);
        dto.setExpTime(expTime);

        // 端口顺延:createForwardForUser 只避得开数据库里已占的口,
        // 机器 OS 层被别的程序占着它看不见 —— 那种要等 gost 下发时才报出来,得换个口重试
        PortAllocation pa = allocatePorts(tunnel, null);
        if (pa.isHasError()) {
            return R.err(pa.getErrorMessage() + suggestPortHint(tunnel));
        }
        Integer tryPort = pa.getInPort();
        R lastErr = null;
        for (int attempt = 0; attempt < MAX_PORT_RETRY && tryPort != null; attempt++) {
            dto.setInPort(tryPort);
            R r = createForwardForUser(dto, userId, user.getUser());
            if (r.getCode() == 0) {
                return r;
            }
            lastErr = r;
            String msg = r.getMsg() == null ? "" : r.getMsg();
            if (!msg.contains("already in use") && !msg.contains("address already") && !msg.contains("已被占用")) {
                return r; // 不是端口被占,换端口也没用,把真实原因抛给用户
            }
            tryPort = allocateInPortAfter(tunnel, tryPort);
        }
        return lastErr != null ? lastErr : R.err("没有可用端口。" + suggestPortHint(tunnel));
    }

    /**
     * 写入/清空某条转发给车友的客户端链接。
     *
     * 【为什么由客户端算好再推上来,而不是面板自己拼】拼这条链接要做的事不少:
     * 换 host:port、TLS 类协议补回隐式 SNI、socks 账号密码转 base64url(V2rayN 认的格式)。
     * 这套逻辑客户端已经有一份(reviveLink)且踩过坑修好了,在 Java 里再实现一遍
     * 迟早两边对不上 —— 到时候"面板测能通、V2rayN 里 -1"这种事又要来一回。
     *
     * 用 UpdateWrapper 而不是 updateById:MyBatis-Plus 默认不更新 null 字段,
     * 走 updateById 的话这一列永远清不掉。
     */
    @Override
    public R setForwardClientLink(Long forwardId, String link) {
        if (forwardId == null) {
            return R.err("参数不完整");
        }
        Forward f = this.getById(forwardId);
        if (f == null) {
            return R.err("转发不存在");
        }
        String v = (link == null || link.trim().isEmpty()) ? null : link.trim();
        this.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Forward>()
                .eq("id", forwardId)
                .set("client_link", v)
                .set("updated_time", System.currentTimeMillis()));
        return R.ok();
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        // 打标:哪些是搭协议/搭中转自动生成的管道,好让前端默认收起来
        forwardList.forEach(f -> f.setProtocolManaged(isProtocolManaged(f.getName(), f.getTunnelName())));

        return R.ok(forwardList);
    }

    /**
     * 协议转发命名固定 inbound-{入站id}-user-{用户id},隧道固定 inbound-tunnel-node{节点id};
     * 「把转发分给车友」生成的固定 fwd-{源转发id}-user-{用户id} —— 三月说过隧道页里
     * 不要混进车友的东西,所以这类也一并打标,前端默认收起。
     */
    private static boolean isProtocolManaged(String forwardName, String tunnelName) {
        if (forwardName != null && forwardName.matches("^(inbound|fwd)-\\d+-user-\\d+$")) {
            return true;
        }
        return tunnelName != null && tunnelName.startsWith("inbound-tunnel-node");
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        // 3. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardUpdateDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法更新转发");
        }
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);
        // 4. 检查权限和限制
        UserPermissionResult permissionResult = null;
        if (tunnelChanged) {
            if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
                // 管理员操作自己的转发时，不需要检查权限限制
                if (Objects.equals(currentUser.getUserId(), existForward.getUserId())) {
                    permissionResult = UserPermissionResult.success(null, null);
                } else {
                    // 管理员操作用户转发时，需要检查原用户是否有新隧道权限
                    // 获取原转发用户的信息
                    User originalUser = userService.getById(existForward.getUserId());
                    if (originalUser == null) {
                        return R.err("用户不存在");
                    }

                    // 检查原用户是否有新隧道权限
                    UserTunnel userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
                    if (userTunnel == null) {
                        return R.err("用户没有该隧道权限");
                    }

                    if (userTunnel.getStatus() != 1) {
                        return R.err("隧道被禁用");
                    }

                    // 检查隧道权限到期时间
                    if (userTunnel.getExpTime() != null && userTunnel.getExpTime() > 0 && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                        return R.err("用户的该隧道权限已到期");
                    }

                    // 检查原用户的流量和转发数量限制
                    R quotaCheckResult = checkForwardQuota(existForward.getUserId(), tunnel.getId().intValue(), userTunnel, originalUser, forwardUpdateDto.getId());
                    if (quotaCheckResult.getCode() != 0) {
                        return R.err("用户" + quotaCheckResult.getMsg());
                    }

                    permissionResult = UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
                }
            } else {
                // 普通用户检查自己的权限
                permissionResult = checkUserPermissions(currentUser, tunnel, forwardUpdateDto.getId());
                if (permissionResult.isHasError()) {
                    return R.err(permissionResult.getErrorMessage());
                }
            }
        }

        // 5. 获取UserTunnel（即使隧道未变化也需要获取，用于构建服务名称）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        // 6. 更新Forward对象
        Forward updatedForward = updateForwardEntity(forwardUpdateDto, existForward, tunnel);

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务更新转发
        R gostResult;
        if (tunnelChanged) {
            // 隧道变化时：先删除原配置，再创建新配置
            gostResult = updateGostServicesWithTunnelChange(existForward, updatedForward, tunnel, resolveLimiter(updatedForward, permissionResult != null ? permissionResult.getLimiter() : null), nodeInfo, userTunnel);
        } else {
            // 隧道未变化时：直接更新配置
            gostResult = updateGostServices(updatedForward, tunnel, resolveLimiter(updatedForward, permissionResult != null ? permissionResult.getLimiter() : null), nodeInfo, userTunnel);
        }

        if (gostResult.getCode() != 0) {
            return gostResult;
        }
        updatedForward.setStatus(1);
        // 9. 保存更新
        boolean result = this.updateById(updatedForward);
        return result ? R.ok("端口转发更新成功") : R.err("端口转发更新失败");
    }

    @Override
    public R deleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 权限检查（仅普通用户需要）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员删除用户记录时，需要获取对应的UserTunnel用于构建正确的服务名称
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 5. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 6. 调用Gost服务删除转发
        R gostResult = deleteGostServices(forward, tunnel, nodeInfo, userTunnel);
        if (gostResult.getCode() != 0) {
            return gostResult;
        }

        // 7. 删除转发记录
        boolean result = this.removeById(id);
        if (result) {
            return R.ok("端口转发删除成功");
        } else {
            return R.err("端口转发删除失败");
        }
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_PAUSED, "暂停", "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        Forward f = this.getById(id);
        // exp_time = 0 是「永不过期」,不是 1970 年过期的。少了 > 0 这个守卫,
        // 不设到期的转发一旦被暂停就再也恢复不了,月初流量重置那次批量 resume 也会全军覆没
        if (f != null && f.getExpTime() != null && f.getExpTime() > 0
                && f.getExpTime() < System.currentTimeMillis()) {
            return R.err("该转发已到期,无法恢复");
        }
        return changeForwardStatus(id, FORWARD_STATUS_ACTIVE, "恢复", "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限操作
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 直接删除转发记录，跳过GOST服务删除
        boolean result = this.removeById(id);
        if (result) {
            return R.ok("端口转发强制删除成功");
        } else {
            return R.err("端口转发强制删除失败");
        }
    }

    /**
     * 改变转发状态（暂停/恢复）
     */
    private R changeForwardStatus(Long id, int targetStatus, String operation, String gostMethod) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 恢复服务时需要额外检查
        UserTunnel userTunnel = null;
        if (targetStatus == FORWARD_STATUS_ACTIVE) {
            if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
                return R.err("隧道已禁用，无法恢复服务");
            }

            // 普通用户需要检查流量和账户状态
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }

                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("你没有该隧道权限");
                }

                if (userTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }

        // 5. 权限检查（仅普通用户需要）
        if (currentUser.getRoleId() != ADMIN_ROLE_ID && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        }

        // 6. 确保获取UserTunnel用于构建服务名称（包括管理员用户）
        if (userTunnel == null) {
            // 通过forward记录获取原始的用户ID来查找UserTunnel
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        GostDto gostResult;

        if ("PauseService".equals(gostMethod)) {
            gostResult = GostUtil.PauseService(nodeInfo.getInNode().getId(), serviceName);

            // 隧道转发需要同时暂停远端服务
            if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.PauseRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(operation + "远端服务失败：" + remoteResult.getMsg());
                }
            }
        } else {
            gostResult = GostUtil.ResumeService(nodeInfo.getInNode().getId(), serviceName);

            // 隧道转发需要同时恢复远端服务
            if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.ResumeRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(operation + "远端服务失败：" + remoteResult.getMsg());
                }
            }
        }

        if (!isGostOperationSuccess(gostResult)) {
            return R.err(operation + "服务失败：" + gostResult.getMsg());
        }

        // 9. 更新转发状态
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        boolean result = this.updateById(forward);

        return result ? R.ok("服务已" + operation) : R.err("更新状态失败");
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return R.err("入口节点不存在");
        }


        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");
        // 6. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }

                DiagnosisResult result = performTcpPingDiagnosis(inNode, targetIp, targetPort, "转发->目标");
                results.add(result);
            }
        } else {
            // 隧道转发：入口TCP ping出口，出口TCP ping目标
            Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return R.err("出口节点不存在");
            }

            // 入口TCP ping出口（使用转发的出口端口）
            DiagnosisResult inToOutResult = performTcpPingDiagnosis(inNode, outNode.getServerIp(), forward.getOutPort(), "入口->出口");
            results.add(inToOutResult);

            // 出口TCP ping目标
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }
                DiagnosisResult outToTargetResult = performTcpPingDiagnosis(outNode, targetIp, targetPort, "出口->目标");
                results.add(outToTargetResult);
            }

        }

        // 7. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    public R updateForwardOrder(Map<String, Object> params) {
        try {
            // 1. 获取当前用户信息
            UserInfo currentUser = getCurrentUserInfo();

            // 2. 验证参数
            if (!params.containsKey("forwards")) {
                return R.err("缺少forwards参数");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
            if (forwardsList == null || forwardsList.isEmpty()) {
                return R.err("forwards参数不能为空");
            }

            // 3. 验证用户权限（只能更新自己的转发）
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                // 普通用户只能更新自己的转发
                List<Long> forwardIds = forwardsList.stream()
                        .map(item -> Long.valueOf(item.get("id").toString()))
                        .collect(Collectors.toList());

                // 检查所有转发是否属于当前用户
                QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("id", forwardIds);
                queryWrapper.eq("user_id", currentUser.getUserId());

                long count = this.count(queryWrapper);
                if (count != forwardIds.size()) {
                    return R.err("只能更新自己的转发排序");
                }
            }

            // 4. 批量更新排序
            List<Forward> forwardsToUpdate = new ArrayList<>();
            for (Map<String, Object> forwardData : forwardsList) {
                Long id = Long.valueOf(forwardData.get("id").toString());
                Integer inx = Integer.valueOf(forwardData.get("inx").toString());

                Forward forward = new Forward();
                forward.setId(id);
                forward.setInx(inx);
                forwardsToUpdate.add(forward);
            }

            // 5. 执行批量更新
            boolean success = this.updateBatchById(forwardsToUpdate);
            if (success) {
                log.info("用户 {} 更新了 {} 个转发的排序", currentUser.getUserName(), forwardsToUpdate.size());
                return R.ok("排序更新成功");
            } else {
                return R.err("排序更新失败");
            }

        } catch (Exception e) {
            log.error("更新转发排序失败", e);
            return R.err("更新排序时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从地址字符串中提取IP地址
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    /**
     * 从地址字符串中提取端口号
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    /**
     * 执行TCP ping诊断
     *
     * @param node        执行TCP ping的节点
     * @param targetIp    目标IP地址
     * @param port        目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 2);
            tcpPingData.put("timeout", 3000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    /**
     * 获取当前用户信息
     */
    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    /**
     * 验证隧道是否存在
     */
    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    /**
     * 验证转发是否存在且用户有权限访问
     */
    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != ADMIN_ROLE_ID &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    /**
     * 获取所需的节点信息
     */
    private NodeInfo getRequiredNodes(Tunnel tunnel) {
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return NodeInfo.error("入口节点不存在");
        }

        Node outNode = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return NodeInfo.error("出口节点不存在");
            }
        }

        return NodeInfo.success(inNode, outNode);
    }

    /**
     * 检查用户权限和限制
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() > 0 && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() > 0 && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // 流量限制检查
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    /**
     * 检查用户转发数量限制
     */
    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        long userForwardCount = this.count(new QueryWrapper<Forward>().eq("user_id", userId));
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    /**
     * 检查用户流量限制
     */
    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() > 0 && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() > 0 && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort) {
        return allocatePorts(tunnel, specifiedInPort, null);
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort, Long excludeForwardId) {
        Integer inPort;

        if (specifiedInPort != null) {
            // 用户指定了入口端口，需要检查是否可用
            if (!isInPortAvailable(tunnel, specifiedInPort, excludeForwardId)) {
                return PortAllocation.error("指定的入口端口 " + specifiedInPort + " 已被占用或不在允许范围内");
            }
            inPort = specifiedInPort;
        } else {
            // 用户未指定端口时自动分配
            inPort = allocateInPort(tunnel, excludeForwardId);
            if (inPort == null) {
                return PortAllocation.error("隧道入口端口已满，无法分配新端口");
            }
        }

        Integer outPort = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outPort = allocateOutPort(tunnel, excludeForwardId);
            if (outPort == null) {
                return PortAllocation.error("隧道出口端口已满，无法分配新端口");
            }
        }

        return PortAllocation.success(inPort, outPort);
    }

    /**
     * 创建Forward实体对象
     */
    private Forward createForwardEntity(ForwardDto forwardDto, UserInfo currentUser, PortAllocation portAllocation) {
        Forward forward = new Forward();
        // 先复制DTO的属性，再设置其他属性，避免被覆盖
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 更新Forward实体对象
     */
    private Forward updateForwardEntity(ForwardUpdateDto forwardUpdateDto, Forward existForward, Tunnel tunnel) {
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardUpdateDto, forward);

        // 处理端口分配逻辑
        boolean tunnelChanged = !existForward.getTunnelId().equals(forwardUpdateDto.getTunnelId());
        boolean inPortChanged = forwardUpdateDto.getInPort() != null &&
                !Objects.equals(forwardUpdateDto.getInPort(), existForward.getInPort());

        if (tunnelChanged || inPortChanged) {
            // 隧道变化或入口端口变化时需要重新分配
            Integer specifiedInPort = forwardUpdateDto.getInPort();
            // 如果没有指定新端口但隧道未变化，保持原端口
            if (specifiedInPort == null && !tunnelChanged) {
                specifiedInPort = existForward.getInPort();
            }

            PortAllocation portAllocation = allocatePorts(tunnel, specifiedInPort, forwardUpdateDto.getId());
            if (portAllocation.isHasError()) {
                throw new RuntimeException(portAllocation.getErrorMessage());
            }
            forward.setInPort(portAllocation.getInPort());
            forward.setOutPort(portAllocation.getOutPort());
        } else {
            // 隧道和端口都未变化，保持原端口
            forward.setInPort(existForward.getInPort());
            forward.setOutPort(existForward.getOutPort());
        }

        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 单条转发自带限速规则优先,否则回退到用户隧道的默认规则(功能B)。
     */
    private Integer resolveLimiter(Forward forward, Integer fallback) {
        return forward != null && forward.getSpeedId() != null ? forward.getSpeedId() : fallback;
    }

    /**
     * 创建Gost服务
     */
    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 隧道转发需要创建链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            R chainResult = createChainService(nodeInfo.getInNode(), serviceName, tunnel.getOutIp(), forward.getOutPort(), tunnel.getProtocol(), tunnel.getInterfaceName());
            if (chainResult.getCode() != 0) {
                GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
                return chainResult;
            }

            R remoteResult = createRemoteService(nodeInfo.getOutNode(), serviceName, forward, tunnel.getProtocol(), forward.getInterfaceName());
            if (remoteResult.getCode() != 0) {
                GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
                GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                return remoteResult;
            }
        }

        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }


        R serviceResult = createMainService(nodeInfo.getInNode(), serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            if (nodeInfo.getOutNode() != null) {
                GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
            }
            return serviceResult;
        }
        return R.ok();
    }

    /**
     * 更新Gost服务
     */
    private R updateGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 隧道转发需要更新链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            R chainResult = updateChainService(nodeInfo.getInNode(), serviceName, tunnel.getOutIp(), forward.getOutPort(), tunnel.getProtocol(), tunnel.getInterfaceName());
            if (chainResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return chainResult;
            }

            R remoteResult = updateRemoteService(nodeInfo.getOutNode(), serviceName, forward, tunnel.getProtocol(), forward.getInterfaceName());
            if (remoteResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return remoteResult;
            }
        }
        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }
        // 更新主服务
        R serviceResult = updateMainService(nodeInfo.getInNode(), serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            updateForwardStatusToError(forward);
            return serviceResult;
        }

        return R.ok();
    }

    /**
     * 隧道变化时更新Gost服务：先删除原配置，再创建新配置
     */
    private R updateGostServicesWithTunnelChange(Forward existForward, Forward updatedForward, Tunnel newTunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        // 1. 获取原隧道信息
        Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
        if (oldTunnel == null) {
            return R.err("原隧道不存在，无法删除旧配置");
        }

        // 2. 删除原有的Gost服务配置
        R deleteResult = deleteOldGostServices(existForward, oldTunnel);
        if (deleteResult.getCode() != 0) {
            // 删除失败时记录日志，但不影响后续创建（可能原配置已不存在）
            log.info("删除原隧道{}的Gost配置失败: {}", oldTunnel.getId(), deleteResult.getMsg());
        }

        // 3. 创建新的Gost服务配置
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel);
        if (createResult.getCode() != 0) {
            updateForwardStatusToError(updatedForward);
            return R.err("创建新隧道配置失败: " + createResult.getMsg());
        }

        return R.ok();
    }

    /**
     * 删除原有的Gost服务（隧道变化时专用）
     */
    private R deleteOldGostServices(Forward forward, Tunnel oldTunnel) {
        // 获取原隧道的用户隧道关系
        UserTunnel oldUserTunnel = getUserTunnel(forward.getUserId(), oldTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), oldUserTunnel);

        // 获取原隧道的节点信息
        NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);

        // 删除主服务（使用原隧道的入口节点）
        if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
            GostDto serviceResult = GostUtil.DeleteService(oldNodeInfo.getInNode().getId(), serviceName);
            if (!isGostOperationSuccess(serviceResult)) {
                log.info("删除主服务失败: {}", serviceResult.getMsg());
            }
        }

        // 如果原隧道是隧道转发类型，需要删除链和远程服务
        if (oldTunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            // 删除链服务
            if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
                GostDto chainResult = GostUtil.DeleteChains(oldNodeInfo.getInNode().getId(), serviceName);
                if (!isGostOperationSuccess(chainResult)) {
                    log.info("删除链服务失败: {}", chainResult.getMsg());
                }
            }

            // 删除远程服务（即使节点信息获取失败，也要尝试删除）
            Node outNode = null;
            if (!oldNodeInfo.isHasError()) {
                outNode = oldNodeInfo.getOutNode();
            } else {
                // 即使获取节点信息失败，也尝试直接获取出口节点来删除远程服务
                outNode = nodeService.getNodeById(oldTunnel.getOutNodeId());
            }

            if (outNode != null) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(outNode.getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    log.info("删除远程服务失败: {}", remoteResult.getMsg());
                }
            }
        }

        return R.ok();
    }

    /**
     * 删除Gost服务
     */
    private R deleteGostServices(Forward forward, Tunnel tunnel, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 删除主服务
        GostDto serviceResult = GostUtil.DeleteService(nodeInfo.getInNode().getId(), serviceName);
        if (!isGostOperationSuccess(serviceResult)) {
            return R.err(serviceResult.getMsg());
        }

        // 隧道转发需要删除链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            GostDto chainResult = GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            if (!isGostOperationSuccess(chainResult)) {
                return R.err(chainResult.getMsg());
            }

            if (nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(remoteResult.getMsg());
                }
            }
        }

        return R.ok();
    }

    /**
     * 创建链服务
     */
    private R createChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto result = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建远程服务
     */
    private R createRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建主服务
     */
    private R createMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新链服务
     */
    private R updateChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        // 创建新链
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto createResult = GostUtil.UpdateChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新远程服务
     */
    private R updateRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        // 创建新远程服务
        GostDto createResult = GostUtil.UpdateRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新主服务
     */
    private R updateMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);

        if (result.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        }

        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新转发状态为错误
     */
    private void updateForwardStatusToError(Forward forward) {
        forward.setStatus(FORWARD_STATUS_ERROR);
        this.updateById(forward);
    }

    /**
     * 获取用户隧道关系
     */
    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    /**
     * 检查隧道是否发生变化
     */
    private boolean isTunnelChanged(Forward existForward, ForwardUpdateDto updateDto) {
        return !existForward.getTunnelId().equals(updateDto.getTunnelId());
    }

    /**
     * 检查Gost操作是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }


    /**
     * 检查指定的入口端口是否可用（可排除指定的转发ID）
     */
    private boolean isInPortAvailable(Tunnel tunnel, Integer port, Long excludeForwardId) {
        // 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return false;
        }

        // 检查端口是否在节点允许的范围内
        if (port < inNode.getPortSta() || port > inNode.getPortEnd()) {
            return false;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(tunnel.getInNodeId(), excludeForwardId);

        // 检查端口是否已被占用（在节点级别检查，考虑入口和出口端口）
        return !usedPorts.contains(port);
    }

    /**
     * 为隧道分配一个可用的入口端口（可排除指定的转发ID）
     */
    private Integer allocateInPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getInNodeId(), excludeForwardId);
    }

    /**
     * 在 after 之后找下一个可用入口端口(自动分配时,某个端口在机器 OS 层被占就顺延)。
     * 没有可用返回 null。
     */
    private Integer allocateInPortAfter(Tunnel tunnel, int after) {
        Node node = nodeService.getNodeById(tunnel.getInNodeId());
        if (node == null) {
            return null;
        }
        Set<Integer> usedPorts = getAllUsedPortsOnNode(tunnel.getInNodeId(), null);
        for (int port = after + 1; port <= node.getPortEnd(); port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }
        return null;
    }

    /** 端口冲突时给用户一个可用端口的建议,免得他自己一个个试 */
    private String suggestPortHint(Tunnel tunnel) {
        try {
            Integer free = allocateInPort(tunnel, null);
            if (free != null) {
                return "(建议用 " + free + ",或把入口端口留空由系统自动分配)";
            }
        } catch (Exception ignored) {
        }
        return "(建议把入口端口留空,由系统自动分配)";
    }

    /**
     * 为隧道分配一个可用的出口端口（可排除指定的转发ID）
     */
    private Integer allocateOutPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getOutNodeId(), excludeForwardId);
    }

    /**
     * 为指定节点分配一个可用端口（通用方法）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 可用端口号，如果没有可用端口则返回null
     */
    private Integer allocatePortForNode(Long nodeId, Long excludeForwardId) {
        // 获取节点信息
        Node node = nodeService.getNodeById(nodeId);
        if (node == null) {
            return null;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(nodeId, excludeForwardId);

        // 在节点端口范围内寻找未使用的端口
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }
        return null;
    }

    /**
     * 获取指定节点上所有已被占用的端口（包括入口和出口端口）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 已占用的端口集合
     */
    private Set<Integer> getAllUsedPortsOnNode(Long nodeId, Long excludeForwardId) {
        Set<Integer> usedPorts = new HashSet<>();

        // 1. 收集该节点作为入口时占用的端口
        List<Tunnel> inTunnels = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", nodeId));
        if (!inTunnels.isEmpty()) {
            Set<Long> inTunnelIds = inTunnels.stream()
                    .map(Tunnel::getId)
                    .collect(Collectors.toSet());

            QueryWrapper<Forward> inQueryWrapper = new QueryWrapper<Forward>().in("tunnel_id", inTunnelIds);
            if (excludeForwardId != null) {
                inQueryWrapper.ne("id", excludeForwardId);
            }

            List<Forward> inForwards = this.list(inQueryWrapper);
            for (Forward forward : inForwards) {
                if (forward.getInPort() != null) {
                    usedPorts.add(forward.getInPort());
                }
            }
        }

        // 2. 收集该节点作为出口时占用的端口
        List<Tunnel> outTunnels = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", nodeId));
        if (!outTunnels.isEmpty()) {
            Set<Long> outTunnelIds = outTunnels.stream()
                    .map(Tunnel::getId)
                    .collect(Collectors.toSet());

            QueryWrapper<Forward> outQueryWrapper = new QueryWrapper<Forward>().in("tunnel_id", outTunnelIds);
            if (excludeForwardId != null) {
                outQueryWrapper.ne("id", excludeForwardId);
            }

            List<Forward> outForwards = this.list(outQueryWrapper);
            for (Forward forward : outForwards) {
                if (forward.getOutPort() != null) {
                    usedPorts.add(forward.getOutPort());
                }
            }
        }

        // 3. 合体面板:该节点上协议入站的 sing-box 本机端口也算占用
        //    (转发公网口 0.0.0.0:X 会和 sing-box 127.0.0.1:X 同机 OS 级冲突)
        List<Inbound> inbounds = inboundMapper.selectList(new QueryWrapper<Inbound>().eq("node_id", nodeId));
        for (Inbound inbound : inbounds) {
            if (inbound.getListenPort() != null) {
                usedPorts.add(inbound.getListenPort());
            }
        }

        return usedPorts;
    }


    /**
     * 构建服务名称，优化后减少重复查询
     */
    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    public void updateForwardA(Forward forward) {
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return;
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return;
        }
        Integer limiter;
        if (userTunnel == null) {
            limiter = null;
        } else {
            limiter = userTunnel.getSpeedId();
        }
        updateGostServices(forward, tunnel, resolveLimiter(forward, limiter), nodeInfo, userTunnel);
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;
    }

    /**
     * 用户权限检查结果
     */
    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    /**
     * 端口分配结果
     */
    @Data
    private static class PortAllocation {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer inPort;
        private final Integer outPort;

        private PortAllocation(boolean hasError, String errorMessage, Integer inPort, Integer outPort) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inPort = inPort;
            this.outPort = outPort;
        }

        public static PortAllocation success(Integer inPort, Integer outPort) {
            return new PortAllocation(false, null, inPort, outPort);
        }

        public static PortAllocation error(String errorMessage) {
            return new PortAllocation(true, errorMessage, null, null);
        }
    }

    /**
     * 节点信息封装类
     */
    @Data
    private static class NodeInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final Node inNode;
        private final Node outNode;

        private NodeInfo(boolean hasError, String errorMessage, Node inNode, Node outNode) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inNode = inNode;
            this.outNode = outNode;
        }

        public static NodeInfo success(Node inNode, Node outNode) {
            return new NodeInfo(false, null, inNode, outNode);
        }

        public static NodeInfo error(String errorMessage) {
            return new NodeInfo(true, errorMessage, null, null);
        }
    }

    /**
     * 诊断结果数据类
     */
    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;
    }
}
