package com.admin.common.task;

import com.admin.common.utils.AutoPauseSkips;
import com.admin.common.utils.GostDeleteFallback;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 到期巡检。
 *
 * 为什么需要它:账号到期、线路到期、线路流量跑满,这三件事原来【只在节点上报流量时
 * 顺带检查】(FlowController.checkUserAccountLimits / checkLineRelatedLimits)。
 * 那是被动的、事后的 —— 到期那一刻没有任何东西去停他,必须等这个人再产生流量、
 * 节点把流量报上来才会被发现。用得少的用户可能很久都停不掉,而卖节点最怕的就是这个。
 *
 * 这个任务把它变成主动的:每分钟扫一遍,该停的当场停。
 * 原来那套被动检查保留不动,继续当兜底。
 *
 * 只处理「状态需要变化」的记录 —— 已经 status=0 的转发不会被反复下发暂停指令,
 * 否则每分钟都要给节点推一轮,机器多了就是无谓的压力。
 */
@Slf4j
@Service
public class CheckExpiryAsync {

    @Resource
    @Lazy
    private UserService userService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    private InboundMapper inboundMapper;

    @Resource
    private InboundUserMapper inboundUserMapper;

    @Resource
    private InboundLineMapper inboundLineMapper;

    @Resource
    private AccountPauseStore accountPauseStore;

    @Resource
    private AccountLineResume accountLineResume;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    /**
     * 每分钟一次。纯数据库查询 + 只对需要变化的记录下发指令,不变化的不动,
     * 所以频率高一点也不会给节点造成压力。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        try {
            pauseExpiredAccounts();
        } catch (Exception e) {
            log.error("账号到期巡检失败: {}", e.getMessage(), e);
        }
        try {
            pauseExpiredOrDepletedLines();
        } catch (Exception e) {
            log.error("线路到期/超额巡检失败: {}", e.getMessage(), e);
        }
        try {
            retryAccountResume();
        } catch (Exception e) {
            log.warn("账号放开后补恢复失败: {}", e.getMessage());
        }
    }

    /**
     * 补恢复:账号已经放开、但 account_pause 里还有记录的(改车友那一刻节点离线、恢复没成),每分钟再试一次。
     * 只动记录里的转发;账号仍被挡的跳过。成功了才打日志,失败只在 debug 里,免得每分钟刷屏。
     */
    private void retryAccountResume() {
        // 兜底:账号放开后连续 7 天都没拉起来的记录不再重试
        int dropped = accountPauseStore.dropStaleRetries(7L * 24 * 3600 * 1000);
        if (dropped > 0) {
            log.info("账号放开后补恢复:{} 条连续 7 天都没拉起来,不再重试(转发保持暂停,需要车主手动处理)", dropped);
        }
        // 和到期巡检共用一个调度线程:每轮最多试 20 条、最多 30 秒,剩下的下一轮;打乱顺序免得总卡在同几个人身上
        AccountLineResume.Budget budget = new AccountLineResume.Budget(20, 30_000L);
        List<Long> uids = new ArrayList<>(accountPauseStore.userIds());
        java.util.Collections.shuffle(uids);
        for (Long uid : uids) {
            if (budget.exhausted()) {
                break;
            }
            int n = accountLineResume.resumeIfAccountOk(uid, false, budget);
            if (n > 0) {
                log.info("账号放开后补恢复:用户 {} 恢复 {} 条转发", uid, n);
            }
        }
    }

    /** 账号总闸:到期或被停用 → 这个人名下所有转发一起停 */
    private void pauseExpiredAccounts() {
        long now = System.currentTimeMillis();
        List<User> users = userService.list();
        for (User u : users) {
            if (u.getId() == null) {
                continue;
            }
            boolean expired = u.getExpTime() != null && u.getExpTime() > 0 && u.getExpTime() <= now;
            boolean disabled = u.getStatus() != null && u.getStatus() != 1;
            if (!expired && !disabled) {
                continue;
            }
            // 只挑还在跑的:已经停掉的不再重复下发
            List<Forward> live = AutoPauseSkips.withoutTreatedAsPaused(forwardService.list(new QueryWrapper<Forward>()
                    .eq("user_id", u.getId())
                    .ne("status", 0)));
            if (live.isEmpty()) {
                continue;
            }
            for (Forward f : pauseForwards(live)) {
                accountPauseStore.record(f, expired ? AccountPauseStore.REASON_EXPIRED : AccountPauseStore.REASON_DISABLED);
            }
            log.info("账号[{}] {} → 已停 {} 条转发", u.getUser(),
                    expired ? "已到期" : "被停用", live.size());
        }
    }

    /**
     * 线路级:线路自己的到期时间到了,或者该线路用量超过它自己的配额 → 停这条线路的转发。
     * 不动这个人的其它线路 —— 协议/中转是按线路卖的,一条线路一个套餐。
     */
    private void pauseExpiredOrDepletedLines() {
        long now = System.currentTimeMillis();
        List<InboundLine> lines = inboundLineMapper.selectList(new QueryWrapper<InboundLine>());
        for (InboundLine line : lines) {
            if (line.getUserId() == null || line.getNodeId() == null) {
                continue;
            }
            // 已经被标记停用的线路不用再处理:要么早停过了,要么是车主手动停的
            if (line.getStatus() != null && line.getStatus() == 0) {
                continue;
            }

            List<Forward> forwards = lineForwards(line.getUserId(), line.getNodeId(), line.getLandingId());
            if (forwards.isEmpty()) {
                continue;
            }

            boolean expired = line.getExpTime() != null && line.getExpTime() > 0 && line.getExpTime() <= now;
            boolean depleted = false;
            if (!expired && line.getFlow() != null && line.getFlow() > 0) {
                long used = 0L;
                for (Forward f : forwards) {
                    used += (f.getInFlow() == null ? 0L : f.getInFlow())
                            + (f.getOutFlow() == null ? 0L : f.getOutFlow());
                }
                depleted = used >= line.getFlow() * BYTES_TO_GB;
            }
            if (!expired && !depleted) {
                continue;
            }

            List<Forward> live = new ArrayList<>();
            for (Forward f : forwards) {
                if ((f.getStatus() == null || f.getStatus() != 0) && !AutoPauseSkips.treatedAsPaused(f)) {
                    live.add(f);
                }
            }
            if (!live.isEmpty()) {
                pauseForwards(live);
            }
            // 线路标记成停用:订阅里不再出现,面板上也能看出来是哪条被停了。
            // 注意「跑满」和「到期」都置 0,但 ResetFlowAsync 每月只恢复跑满的那一批,
            // 到期的不会跟着复活 —— 那个边界在 ResetFlowAsync 里,别改坏。
            line.setStatus(0);
            inboundLineMapper.updateById(line);
            log.info("线路[user={} node={} landing={}] {} → 已停 {} 条转发",
                    line.getUserId(), line.getNodeId(), line.getLandingId(),
                    expired ? "已到期" : "流量跑满", live.size());
        }
    }

    /** 该线路(车友 × 机器 × 落地)下的所有转发 */
    private List<Forward> lineForwards(Long userId, Long nodeId, Long landingId) {
        List<InboundUser> ius = inboundUserMapper.selectList(
                new QueryWrapper<InboundUser>().eq("user_id", userId));
        List<Long> forwardIds = new ArrayList<>();
        Map<Long, Inbound> cache = new HashMap<>();
        for (InboundUser iu : ius) {
            if (iu.getGostForwardId() == null) {
                continue;
            }
            Inbound in = cache.get(iu.getInboundId());
            if (in == null) {
                in = inboundMapper.selectById(iu.getInboundId());
                if (in == null) {
                    continue;
                }
                cache.put(iu.getInboundId(), in);
            }
            if (!nodeId.equals(in.getNodeId())) {
                continue;
            }
            Long lid = in.getLandingId();
            boolean sameLine = (landingId == null) ? (lid == null) : landingId.equals(lid);
            if (sameLine) {
                forwardIds.add(iu.getGostForwardId());
            }
        }
        if (forwardIds.isEmpty()) {
            return new ArrayList<>();
        }
        return forwardService.list(new QueryWrapper<Forward>().in("id", forwardIds));
    }

    /**
     * 真正去停。服务名必须按每条转发单独算(forwardId_userId_userTunnelId),
     * 整批共用一个名字的话只有一条会真的停下来 —— FlowController.pauseService
     * 以前就栽在这。这里的转发都来自协议/中转,没有 user_tunnel,所以恒为 0。
     */
    private List<Forward> pauseForwards(List<Forward> forwards) {
        // 这一次真从运行(status=1)停下来的,给账号总闸记账用
        List<Forward> stopped = new ArrayList<>();
        for (Forward f : forwards) {
            boolean wasRunning = f.getStatus() != null && f.getStatus() == 1;
            boolean found = true;
            try {
                Tunnel tunnel = tunnelService.getById(f.getTunnelId());
                if (tunnel != null) {
                    // 自动批量停:和老版本一样按 _0 发(入口 + 隧道转发的远端);节点回 not found 就跳过,库里不改
                    String svc = f.getId() + "_" + f.getUserId() + "_0";
                    found = GostDeleteFallback.pauseByOriginalName(svc,
                            n -> GostUtil.PauseService(tunnel.getInNodeId(), n),
                            tunnel.getType() != null && tunnel.getType() == 2 ? n -> GostUtil.PauseRemoteService(tunnel.getOutNodeId(), n) : null);
                }
            } catch (Exception e) {
                // 单条失败不能中断整批:节点可能临时离线,下一轮巡检会再试
                log.warn("暂停转发[{}]失败: {}", f.getId(), e.getMessage());
            }
            if (!found) {
                AutoPauseSkips.remember(f);
                continue;
            }
            f.setStatus(0);
            forwardService.updateById(f);
            if (wasRunning) {
                stopped.add(f);
            }
        }
        return stopped;
    }
}
