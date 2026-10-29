package com.admin.common.task;

import com.admin.common.utils.GostDeleteFallback;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.ProtocolForwards;
import com.admin.entity.Forward;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Slf4j
@Configuration
@EnableScheduling
public class ResetFlowAsync {

    @Resource
    UserService userService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ForwardService forwardService;

    @Resource
    TunnelService tunnelService;

    // 协议/中转线路的流量重置用
    @Resource
    InboundUserMapper inboundUserMapper;

    @Resource
    InboundLineMapper inboundLineMapper;

    /**
     * 每天0点执行流量重置任务
     * 查询出用户和隧道的重置流量日期是今天的数据，将上下流量重置为0
     * 考虑当月是29、30天，但是选择是31的这种边界情况
     * 
     * 并发安全说明：
     * - 使用setSql()进行原子SQL更新，只更新流量字段(in_flow, out_flow)
     * - 不会影响DelayQueueManager的到期任务对status等其他字段的更新
     * - 避免了并发修改导致的数据覆盖问题
     */
    @Scheduled(cron = "5 0 0 * * ?")
    public void reset_flow(){
        log.info("开始执行流量重置任务");
        
        try {
            // 获取当前日期信息
            LocalDate today = LocalDate.now();
            int currentDay = today.getDayOfMonth(); // 当前是几号
            int lastDayOfMonth = today.lengthOfMonth(); // 当月最后一天
            
            log.info("当前日期: {}, 当月第{}天, 当月最后一天: {}", today, currentDay, lastDayOfMonth);
            
            // 重置用户流量
            resetUserFlow(currentDay, lastDayOfMonth);
            
            // 重置用户隧道流量
            resetUserTunnelFlow(currentDay, lastDayOfMonth);
            
            log.info("流量重置任务执行完成");


            // 处理过期账号
            user();

            // 处理过期隧道
            userTunnel();

            log.info("到期任务执行完成");
            
        } catch (Exception e) {
            log.info("定时任务执行失败", e);
        }
    }

    /**
     * 单条转发到期处理(功能C):每分钟扫一次,到点即暂停。
     * user()/userTunnel() 每天0点跑、精度不够,故单独加高频扫描。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void forwardExpiry() {
        try {
            List<Forward> list = forwardService.list(new QueryWrapper<Forward>()
                    .eq("status", 1).isNotNull("exp_time").gt("exp_time", 0).lt("exp_time", new Date().getTime()));
            for (Forward forward : list) {
                UserTunnel ut = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                        .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
                pauseForwardService(forward, ut != null ? ut.getId() : null);
                forward.setStatus(0);
                forwardService.updateById(forward);
            }
        } catch (Exception e) {
            log.info("单条转发到期任务执行失败", e);
        }
    }
    
    /**
     * 重置用户流量
     * @param currentDay 当前日期（几号）
     * @param lastDayOfMonth 当月最后一天
     */
    private void resetUserFlow(int currentDay, int lastDayOfMonth) {
        try {
            // flowResetTime字段存储的是0-31的数字，0表示不重置，1-31表示每月第几号重置
            // 构建查询条件：重置日期等于今天，或者重置日期大于当月最大天数且今天是月末
            // 排除flowResetTime为0的记录（不重置）
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.ne("flow_reset_time", 0); // 排除不重置的用户
            
            if (currentDay == lastDayOfMonth) {
                // 如果今天是月末，查询重置日期等于今天或者大于当月最大天数的记录
                // 例如：当月30天，但用户设置31号重置，则在30号执行重置
                queryWrapper.and(wrapper -> wrapper.eq("flow_reset_time", currentDay)
                                                  .or().gt("flow_reset_time", lastDayOfMonth));
            } else {
                // 否则只查询重置日期等于今天的记录
                queryWrapper.eq("flow_reset_time", currentDay);
            }
            
            // 查询需要重置的用户
            List<User> usersToReset = userService.list(queryWrapper);
            
            if (usersToReset.isEmpty()) {
                log.info("没有需要重置流量的用户");
                return;
            }
            
            log.info("找到{}个需要重置流量的用户", usersToReset.size());
            
            // 批量重置用户流量 - 使用SQL原子操作避免与到期任务的并发冲突
            for (User user : usersToReset) {
                UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", user.getId())
                           .setSql("in_flow = 0, out_flow = 0"); // 使用SQL原子操作，只更新流量字段
                
                boolean success = userService.update(null, updateWrapper);
                if (success) {
                    log.info("用户[ID: {}, 用户名: {}]流量重置成功，重置日期: 每月{}号",
                           user.getId(), user.getUser(), user.getFlowResetTime());
                } else {
                    log.info("用户[ID: {}, 用户名: {}]流量重置失败", user.getId(), user.getUser());
                }
                // 协议/中转的线路用量 = 该线路各转发的流量之和,只清 user 表没用:
                // 不把转发流量一起清零,线路配额就成了"终身配额",跑满一次下个月也打不开。
                resetProtocolLineFlow(user);
            }
            
        } catch (Exception e) {
            log.info("重置用户流量失败", e);
        }
    }

    /**
     * 重置某车友【协议/中转线路】的用量。
     * 线路已用量是实时汇总该线路各转发的 in_flow+out_flow 算出来的,所以必须把这些
     * 转发的流量清零;同时把上个周期因为跑满配额被停掉的线路和转发恢复,否则车友
     * 续到下个月也用不了。
     */
    private void resetProtocolLineFlow(User user) {
        try {
            List<InboundUser> ius = inboundUserMapper.selectList(
                    new QueryWrapper<InboundUser>().eq("user_id", user.getId()));
            if (ius == null || ius.isEmpty()) {
                return;
            }
            int cleared = 0, resumed = 0;
            for (InboundUser iu : ius) {
                if (iu.getGostForwardId() == null) {
                    continue;
                }
                Forward f = forwardService.getById(iu.getGostForwardId());
                if (f == null) {
                    continue;
                }
                // 1) 清零该转发流量(原子 SQL,只动流量字段,避免和到期任务抢)
                UpdateWrapper<Forward> fw = new UpdateWrapper<>();
                fw.eq("id", f.getId()).setSql("in_flow = 0, out_flow = 0");
                forwardService.update(null, fw);
                cleared++;

                // 2) 上个周期被停掉的,重新拉起来
                if (f.getStatus() != null && f.getStatus() == 0) {
                    try {
                        forwardService.resumeForward(f.getId());
                        resumed++;
                    } catch (Exception e) {
                        log.info("恢复转发[{}]失败: {}", f.getId(), e.getMessage());
                    }
                }
            }
            // 3) 线路本身的停用标记也要清掉,不然界面一直显示"已停用"。
            //    但只恢复「跑满配额」停的,已经到期的线路不能跟着复活 ——
            //    否则界面显示正常、实际转发还是停的(resumeForward 有到期守卫),
            //    车友看着能用却连不上。exp_time 为 0/NULL 表示永久。
            if (cleared > 0) {
                long now = System.currentTimeMillis();
                UpdateWrapper<InboundLine> lw = new UpdateWrapper<>();
                lw.eq("user_id", user.getId()).eq("status", 0)
                  .and(w -> w.isNull("exp_time").or().le("exp_time", 0).or().gt("exp_time", now))
                  .set("status", 1)
                  .set("updated_time", now);
                inboundLineMapper.update(null, lw);
                log.info("用户[{}]协议线路流量已重置:清零 {} 条转发,恢复 {} 条", user.getUser(), cleared, resumed);
            }
        } catch (Exception e) {
            log.info("重置协议线路流量失败(用户 {}): {}", user.getUser(), e.getMessage());
        }
    }

    /**
     * 重置用户隧道流量
     * @param currentDay 当前日期（几号）
     * @param lastDayOfMonth 当月最后一天
     */
    private void resetUserTunnelFlow(int currentDay, int lastDayOfMonth) {
        try {
            // flowResetTime字段存储的是0-31的数字，0表示不重置，1-31表示每月第几号重置
            // 构建查询条件：重置日期等于今天，或者重置日期大于当月最大天数且今天是月末
            // 排除flowResetTime为0的记录（不重置）
            QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
            queryWrapper.ne("flow_reset_time", 0); // 排除不重置的用户隧道
            
            if (currentDay == lastDayOfMonth) {
                // 如果今天是月末，查询重置日期等于今天或者大于当月最大天数的记录
                // 例如：当月30天，但用户设置31号重置，则在30号执行重置
                queryWrapper.and(wrapper -> wrapper.eq("flow_reset_time", currentDay)
                                                  .or().gt("flow_reset_time", lastDayOfMonth));
            } else {
                // 否则只查询重置日期等于今天的记录
                queryWrapper.eq("flow_reset_time", currentDay);
            }
            
            // 查询需要重置的用户隧道
            List<UserTunnel> userTunnelsToReset = userTunnelService.list(queryWrapper);
            
            if (userTunnelsToReset.isEmpty()) {
                log.info("没有需要重置流量的用户隧道");
                return;
            }
            
            log.info("找到{}个需要重置流量的用户隧道", userTunnelsToReset.size());
            
            // 批量重置用户隧道流量 - 使用SQL原子操作避免与到期任务的并发冲突
            for (UserTunnel userTunnel : userTunnelsToReset) {
                UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
                updateWrapper.eq("id", userTunnel.getId())
                           .setSql("in_flow = 0, out_flow = 0"); // 使用SQL原子操作，只更新流量字段
                
                boolean success = userTunnelService.update(null, updateWrapper);
                if (success) {
                    log.info("用户隧道[ID: {}, 用户ID: {}, 隧道ID: {}]流量重置成功，重置日期: 每月{}号", 
                           userTunnel.getId(), userTunnel.getUserId(), userTunnel.getTunnelId(), userTunnel.getFlowResetTime());
                } else {
                    log.info("用户隧道[ID: {}, 用户ID: {}, 隧道ID: {}]流量重置失败",
                            userTunnel.getId(), userTunnel.getUserId(), userTunnel.getTunnelId());
                }
            }
            
        } catch (Exception e) {
            log.info("重置用户隧道流量失败", e);
        }
    }


    public void user(){
        // 查询过期用户
        List<User> user_list = userService.list(new QueryWrapper<User>().ne("role_id", 0).eq("status", 1).isNotNull("exp_time").gt("exp_time", 0).lt("exp_time", new Date().getTime()));
        for (User user : user_list) {
            // 查询对应转发
            List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", user.getId()).eq("status", 1));
            for (Forward forward : forwardList) {
                UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>().eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
                if (userTunnel != null) {
                    pauseForwardService(forward, userTunnel.getId());
                    forward.setStatus(0);
                    forwardService.updateById(forward);
                }
            }
            user.setStatus(0);
            userService.updateById(user);
        }
    }


    public void userTunnel(){
        // 查询过期隧道
        List<UserTunnel> user_tunnel_list = userTunnelService.list(new QueryWrapper<UserTunnel>().eq("status", 1).isNotNull("exp_time").gt("exp_time", 0).lt("exp_time", new Date().getTime()));
        // 查询对应转发
        for (UserTunnel userTunnel : user_tunnel_list) {
            // 协议/线路转发不归隧道权限管(只看线路自己的状态和到期),见 ProtocolForwards
            List<Forward> forwardList = ProtocolForwards.listExcluding(forwardService::list, () -> new QueryWrapper<Forward>().eq("tunnel_id", userTunnel.getTunnelId()).eq("user_id", userTunnel.getUserId()).eq("status", 1));
            for (Forward forward : forwardList) {
                pauseForwardService(forward, userTunnel.getId());
                forward.setStatus(0);
                forwardService.updateById(forward);
            }
            userTunnel.setStatus(0);
            userTunnelService.updateById(userTunnel);
        }
    }


    private void pauseForwardService(Forward forward, Integer userTunnelId) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return;

        // 先按原名停,节点回「原名 not found」才按 _0 再停(协议/线路转发建的时候恒为 _0)。
        // userTunnelId 为 null 时以前拼成 "x_y_null",节点上根本没有这个名字 —— 现在按 0 算。
        List<String> names = GostDeleteFallback.candidateNames(forward.getId(), forward.getUserId(), userTunnelId);
        GostDeleteFallback.firstFound(names, n -> GostUtil.PauseService(tunnel.getInNodeId(), n));
        if (tunnel.getType() == 2){
            GostDeleteFallback.firstFound(names, n -> GostUtil.PauseRemoteService(tunnel.getOutNodeId(), n));
        }
    }
}
