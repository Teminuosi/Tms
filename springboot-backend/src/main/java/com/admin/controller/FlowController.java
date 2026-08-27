package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    // 协议/中转的线路级配额检查用
    @Resource
    com.admin.mapper.InboundMapper inboundMapper;
    @Resource
    com.admin.mapper.InboundUserMapper inboundUserMapper;
    @Resource
    com.admin.mapper.InboundLineMapper inboundLineMapper;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        // 1. 验证节点权限
        if (!isValidNode(secret)) {
            return SUCCESS_RESPONSE;
        }

        // 2. 尝试解密数据
        String decryptedData = decryptIfNeeded(rawData, secret);

        // 3. 解析为FlowDto列表
        FlowDto flowDataList = JSONObject.parseObject(decryptedData, FlowDto.class);
        if (Objects.equals(flowDataList.getN(), "web_api")) {
            return SUCCESS_RESPONSE;
        }

        // 记录日志
        log.info("节点上报流量数据{}", flowDataList);
        // 4. 处理流量数据
        return processFlowData(flowDataList);
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(FlowDto flowDataList) {
        String[] serviceIds = parseServiceName(flowDataList.getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = forwardService.getById(forwardId);

        // 获取流量计费类型
        int flowType = getFlowType(forward);

        //  处理流量倍率及单双向计算
        FlowDto flowStats = filterFlowData(flowDataList, forward, flowType);

        // 先更新所有流量统计 - 确保流量数据的一致性
        updateForwardFlow(forwardId, flowStats);
        updateUserFlow(userId, flowStats);
        updateUserTunnelFlow(userTunnelId, flowStats);

        // 7. 检查和服务暂停操作
        String name = buildServiceName(forwardId, userId, userTunnelId);
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) { // 走隧道权限的转发(老转发业务)
            checkUserRelatedLimits(userId, name);
            checkUserTunnelRelatedLimits(userTunnelId, name, userId);
        } else if (forward != null && forward.getUserId() != null && forward.getUserId() != 0) {
            // 协议/中转的转发:userTunnelId 恒为 0(没有 user_tunnel),以前整个被跳过 → 配额从未生效。
            // 改为走【线路】检查:一条线路 = 一个套餐,流量/到期各算各的,超了只停这条。
            // 注意:这里【不】查账号总流量——协议/中转按线路卖,不存在"账号总额度"这个概念;
            // 但账号被停用/账号整体到期仍然全停(那是账号级开关)。
            checkLineRelatedLimits(forward, userId);
            checkUserAccountLimits(userId, name);
        }

        return SUCCESS_RESPONSE;
    }

    /**
     * 协议/中转的账号级检查:只看账号是否被停用、账号是否整体到期(那是账号级开关,到了就全停)。
     * 【不查账号总流量】——协议/中转按线路卖,流量由每条线路自己的配额管。
     */
    private void checkUserAccountLimits(String userId, String name) {
        User u = userService.getById(userId);
        if (u == null) {
            return;
        }
        if (u.getExpTime() != null && u.getExpTime() > 0 && u.getExpTime() <= System.currentTimeMillis()) {
            pauseAllUserServices(userId, name);
            return;
        }
        if (u.getStatus() != null && u.getStatus() != 1) {
            pauseAllUserServices(userId, name);
        }
    }

    /**
     * 协议/中转的线路级检查:该转发属于哪条线路(车友×机器×落地组)→ 汇总该线路已用流量,
     * 超过线路配额、或线路到期 → 暂停这条线路的所有转发(不影响车友其它线路)。
     */
    private void checkLineRelatedLimits(Forward forward, String userId) {
        try {
            InboundUser iu = inboundUserMapper.selectOne(new QueryWrapper<InboundUser>()
                    .eq("gost_forward_id", forward.getId()).last("limit 1"));
            if (iu == null) {
                return; // 不是协议/中转的转发
            }
            Inbound in = inboundMapper.selectById(iu.getInboundId());
            if (in == null) {
                return;
            }
            QueryWrapper<InboundLine> lw = new QueryWrapper<InboundLine>()
                    .eq("user_id", iu.getUserId()).eq("node_id", in.getNodeId());
            if (in.getLandingId() != null) {
                lw.eq("landing_id", in.getLandingId());
            } else {
                lw.isNull("landing_id");
            }
            InboundLine line = inboundLineMapper.selectOne(lw.last("limit 1"));
            if (line == null) {
                return; // 老数据没有线路记录 → 只受账号总量约束
            }

            boolean overFlow = false;
            if (line.getFlow() != null && line.getFlow() > 0) {
                long used = sumLineFlow(iu.getUserId(), in.getNodeId(), in.getLandingId());
                overFlow = used >= line.getFlow() * BYTES_TO_GB;
            }
            boolean expired = line.getExpTime() != null && line.getExpTime() > 0 && line.getExpTime() <= System.currentTimeMillis();
            if (!overFlow && !expired) {
                return;
            }
            pauseLineForwards(iu.getUserId(), in.getNodeId(), in.getLandingId());
            line.setStatus(0);
            line.setUpdatedTime(System.currentTimeMillis());
            inboundLineMapper.updateById(line);
            log.info("线路已停:user={} node={} landing={} 超额={} 到期={}",
                    iu.getUserId(), in.getNodeId(), in.getLandingId(), overFlow, expired);
        } catch (Exception e) {
            log.warn("线路流量检查失败", e);
        }
    }

    /** 汇总某条线路已用流量(该线路各协议对应转发的上下行之和) */
    private long sumLineFlow(Long userId, Long nodeId, Long landingId) {
        long total = 0L;
        for (Forward f : lineForwards(userId, nodeId, landingId)) {
            total += (f.getInFlow() == null ? 0L : f.getInFlow())
                    + (f.getOutFlow() == null ? 0L : f.getOutFlow());
        }
        return total;
    }

    /** 暂停某条线路的所有转发 */
    private void pauseLineForwards(Long userId, Long nodeId, Long landingId) {
        for (Forward f : lineForwards(userId, nodeId, landingId)) {
            Tunnel tunnel = tunnelService.getById(f.getTunnelId());
            if (tunnel != null) {
                GostUtil.PauseService(tunnel.getInNodeId(),
                        buildServiceName(String.valueOf(f.getId()), String.valueOf(f.getUserId()), DEFAULT_USER_TUNNEL_ID));
            }
            f.setStatus(0);
            forwardService.updateById(f);
        }
    }

    /** 找出某条线路(车友×机器×落地组)下的所有转发 */
    private List<Forward> lineForwards(Long userId, Long nodeId, Long landingId) {
        List<Forward> result = new java.util.ArrayList<>();
        QueryWrapper<Inbound> iw = new QueryWrapper<Inbound>().eq("node_id", nodeId);
        if (landingId != null) {
            iw.eq("landing_id", landingId);
        } else {
            iw.isNull("landing_id");
        }
        List<Inbound> inbounds = inboundMapper.selectList(iw);
        if (inbounds.isEmpty()) {
            return result;
        }
        List<Long> inboundIds = new java.util.ArrayList<>();
        for (Inbound in : inbounds) {
            inboundIds.add(in.getId());
        }
        List<InboundUser> ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>()
                .eq("user_id", userId).in("inbound_id", inboundIds));
        for (InboundUser iu : ius) {
            if (iu.getGostForwardId() == null) {
                continue;
            }
            Forward f = forwardService.getById(iu.getGostForwardId());
            if (f != null) {
                result.add(f);
            }
        }
        return result;
    }

    private void checkUserRelatedLimits(String userId, String name) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() > 0 && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId, name);
        }
    }

    public void pauseAllUserServices(String userId, String name) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String name, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() > 0 && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String name, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList, name);
    }

    /**
     * 批量暂停转发。
     *
     * name 参数保留只为不动上层调用签名,【已不再用于停服务】——
     * 服务名是 forwardId_userId_userTunnelId,每条转发各不相同,而这里是对一批
     * 转发循环。以前整批都用调用方传进来的那一个 name,结果只有那一条真在 gost 上
     * 停掉,其余的仅仅把数据库 status 改成 0、实际还在跑。
     * 表现就是「账号到期了,里面的线路照样能用」,而单独停某条线路反倒有效
     * (那条路径 pauseLineForwards 一直是按每条转发算名字的)。
     */
    public void pauseService(List<Forward> forwardList, String name) {
        for (Forward forward : forwardList) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null){
                String svc = serviceNameOf(forward);
                GostUtil.PauseService(tunnel.getInNodeId(), svc);
                if (tunnel.getType() == 2){
                    GostUtil.PauseRemoteService(tunnel.getOutNodeId(), svc);
                }
            }
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    /**
     * 这条转发在 gost 上的服务名。
     * 协议/中转的转发没有 user_tunnel,userTunnelId 恒为 0;老隧道业务要按
     * (userId, tunnelId) 反查 UserTunnel 拿 id —— Forward 表本身不存这个字段。
     */
    private String serviceNameOf(Forward forward) {
        int userTunnelId = 0;
        try {
            UserTunnel ut = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", forward.getUserId())
                    .eq("tunnel_id", forward.getTunnelId())
                    .last("limit 1"));
            if (ut != null && ut.getId() != null) {
                userTunnelId = ut.getId();
            }
        } catch (Exception e) {
            // 查不到就按 0 处理:协议/中转的转发本来就没有 user_tunnel,
            // 这里失败也不能让整批暂停中断。
            log.warn("查 user_tunnel 失败,转发[{}]按 userTunnelId=0 处理: {}", forward.getId(), e.getMessage());
        }
        return forward.getId() + "_" + forward.getUserId() + "_" + userTunnelId;
    }

    private FlowDto filterFlowData(FlowDto flowDto, Forward forward, int flowType) {
        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                BigDecimal trafficRatio = tunnel.getTrafficRatio();

                BigDecimal originalD = BigDecimal.valueOf(flowDto.getD());
                BigDecimal originalU = BigDecimal.valueOf(flowDto.getU());

                BigDecimal newD = originalD.multiply(trafficRatio);
                BigDecimal newU = originalU.multiply(trafficRatio);

                flowDto.setD(newD.longValue() * flowType);
                flowDto.setU(newU.longValue() * flowType);
            }
        }
        return flowDto;
    }

    private int getFlowType(Forward forward) {
        int defaultFlowType = 2;
        if (forward == null) return defaultFlowType;
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return defaultFlowType;
        return tunnel.getFlow();
    }

    private void updateForwardFlow(String forwardId, FlowDto flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            forwardService.update(null, updateWrapper);
        }
    }

    private void updateUserFlow(String userId, FlowDto flowStats) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            userService.update(null, updateWrapper);
        }
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
            userTunnelService.update(null, updateWrapper);
        }
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    private boolean isValidNode(String secret) {
        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", secret));
        return nodeCount > 0;
    }

    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}
