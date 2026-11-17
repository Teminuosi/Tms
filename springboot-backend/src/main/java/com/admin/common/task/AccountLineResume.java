package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.AccountLimits;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号总闸放开后,把【账号总闸停掉的】转发拉起来 —— 只认 account_pause 里记着的(AccountPauseStore)。
 *
 * 【为什么只认记录】账号到期 / 停用 / 总流量跑满时会停这个人名下所有转发。以前账号恢复后没人拉;
 * 但也不能按「线路正常、转发停着」去猜:车主在转发页手动停的单条转发、老版本月初重置留下的
 * 「线路 1 / 转发 0」脏数据都长这样,猜错了就把车主停的东西拉起来了。所以停的时候记账,拉的时候只拉账上的。
 *
 * 【记录里的哪些不拉,并把记录删掉(交给别的逻辑管)】
 *   - 转发没了 / id 被复用 / 已经不是停着的(车主手动恢复过)
 *   - 协议 / 中转转发:所在线路 status=0(车主手动停或线路自己停),或按线路自己的判据该停
 *     (和 InboundServiceImpl.updateLine 第 3 步同一套,见 AccountLimits.lineMayResume)
 *   - 老的隧道权限转发:那份隧道权限停用 / 到期 / 跑满(判据照 FlowController.checkUserTunnelRelatedLimits)
 *   - 恢复时报转发自己到期、隧道停用 / 不存在这类拉不起来的
 * 节点离线这类临时失败保留记录,CheckExpiryAsync 每分钟补一次。
 *
 * 出任何错都只记日志,不能让改车友 / 定时任务失败。
 */
@Slf4j
@Component
public class AccountLineResume {

    @Resource
    @Lazy
    private UserService userService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private UserTunnelService userTunnelService;

    @Resource
    private InboundMapper inboundMapper;

    @Resource
    private InboundUserMapper inboundUserMapper;

    @Resource
    private InboundLineMapper inboundLineMapper;

    @Resource
    private AccountPauseStore accountPauseStore;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    /**
     * 定时补恢复每一轮的上限:CheckExpiryAsync 和到期巡检共用一个调度线程,一轮里最多试 maxForwards 条、
     * 最多花 maxMs,用完就停,剩下的下一轮接着来。
     */
    public static final class Budget {
        private int left;
        private final long deadline;

        public Budget(int maxForwards, long maxMs) {
            this.left = maxForwards;
            this.deadline = System.currentTimeMillis() + maxMs;
        }

        boolean take() {
            if (exhausted()) {
                return false;
            }
            left--;
            return true;
        }

        public boolean exhausted() {
            return left <= 0 || System.currentTimeMillis() > deadline;
        }
    }

    /** 车主操作触发:失败原因记 info,不限条数 */
    public int resumeIfAccountOk(Long userId) {
        return resumeIfAccountOk(userId, true, null);
    }

    public int resumeIfAccountOk(Long userId, boolean loud) {
        return resumeIfAccountOk(userId, loud, null);
    }

    /**
     * @param loud   false = 定时补恢复,失败只打 debug
     * @param budget 定时补恢复的每轮上限;null = 不限
     * @return 实际拉起来的转发条数
     */
    public int resumeIfAccountOk(Long userId, boolean loud, Budget budget) {
        if (userId == null) {
            return 0;
        }
        try {
            User u = userService.getById(userId);
            if (u == null || AccountLimits.isAdmin(u)) {
                accountPauseStore.removeByUser(userId);
                return 0;
            }
            long now = System.currentTimeMillis();
            if (AccountLimits.accountBlocked(u, now)) {
                // 账号又被挡住了:「放开后连续失败多久」从下次放开重新算,别让 7 天兜底误删
                accountPauseStore.clearRetrying(userId);
                return 0;
            }
            List<AccountPauseStore.Rec> recs = accountPauseStore.listByUser(userId);
            if (recs.isEmpty()) {
                return 0;
            }

            // 协议 / 中转转发 → 所在线路
            Map<String, List<Forward>> byLine = lineForwards(userId);
            Map<Long, String> lineKeyOf = new HashMap<>();
            for (Map.Entry<String, List<Forward>> e : byLine.entrySet()) {
                for (Forward f : e.getValue()) {
                    lineKeyOf.put(f.getId(), e.getKey());
                }
            }
            Map<String, InboundLine> lines = new HashMap<>();
            for (InboundLine l : inboundLineMapper.selectList(new QueryWrapper<InboundLine>().eq("user_id", userId))) {
                lines.put(key(l.getNodeId(), l.getLandingId()), l);
            }

            int resumed = 0;
            for (AccountPauseStore.Rec rec : recs) {
                Forward f = forwardService.getById(rec.forwardId);
                if (!rec.matches(f) || f.getStatus() == null || f.getStatus() != 0) {
                    accountPauseStore.remove(rec.forwardId);
                    continue;
                }
                String lk = lineKeyOf.get(f.getId());
                if (lk != null) {
                    InboundLine line = lines.get(lk);
                    if (line != null && !AccountLimits.lineMayResume(line, usedBytes(byLine.get(lk)), now)) {
                        accountPauseStore.remove(rec.forwardId); // 线路自己该停,交给线路的逻辑
                        continue;
                    }
                } else if (userTunnelBlocked(f, now)) {
                    accountPauseStore.remove(rec.forwardId); // 隧道权限自己该停,交给隧道权限的逻辑
                    continue;
                }
                if (budget != null && !budget.take()) {
                    break; // 这一轮额度用完,下一轮接着
                }
                try {
                    R r = forwardService.resumeForwardAsSystem(f.getId());
                    if (r != null && r.getCode() == 0) {
                        accountPauseStore.remove(rec.forwardId);
                        resumed++;
                    } else {
                        String msg = r == null ? null : r.getMsg();
                        if (permanentFailure(msg)) {
                            accountPauseStore.remove(rec.forwardId);
                        } else {
                            accountPauseStore.markRetrying(rec.forwardId);
                        }
                        if (loud) {
                            log.info("账号放开后恢复转发[{}]没成: {}", f.getId(), msg);
                        } else {
                            log.debug("账号放开后补恢复转发[{}]没成: {}", f.getId(), msg);
                        }
                    }
                } catch (Exception e) {
                    // 节点被删时 nodeService 直接抛「节点不存在」,不走返回值 —— 一样算永久失败
                    if (permanentFailure(e.getMessage())) {
                        accountPauseStore.remove(rec.forwardId);
                    } else {
                        accountPauseStore.markRetrying(rec.forwardId);
                    }
                    if (loud) {
                        log.warn("账号放开后恢复转发[{}]失败: {}", f.getId(), e.getMessage());
                    } else {
                        log.debug("账号放开后补恢复转发[{}]失败: {}", f.getId(), e.getMessage());
                    }
                }
            }
            if (resumed > 0 && loud) {
                log.info("账号[{}]总闸放开,恢复 {} 条转发", u.getUser(), resumed);
            }
            return resumed;
        } catch (Exception e) {
            log.warn("账号放开后恢复失败(用户 {}): {}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 每月重置把「跑满停的」线路改回 1 之后,把这些线路的转发跟着拉起来(线路自己的逻辑管它们,和 updateLine 一样)。
     * 账号此时还被挡着就先不拉,记进 account_pause(line_reset),账号放开时一起拉 —— 否则这批转发不在账上、以后没人管。
     */
    public int resumeRevivedLines(Long userId, List<Long> lineIds) {
        if (userId == null || lineIds == null || lineIds.isEmpty()) {
            return 0;
        }
        try {
            User u = userService.getById(userId);
            boolean blocked = u == null || AccountLimits.accountBlocked(u, System.currentTimeMillis());
            Map<String, List<Forward>> byLine = lineForwards(userId);
            int resumed = 0;
            for (InboundLine line : inboundLineMapper.selectBatchIds(lineIds)) {
                List<Forward> fs = byLine.get(key(line.getNodeId(), line.getLandingId()));
                if (fs == null) {
                    continue;
                }
                for (Forward f : fs) {
                    if (f.getStatus() == null || f.getStatus() != 0) {
                        continue;
                    }
                    if (blocked) {
                        accountPauseStore.record(f, AccountPauseStore.REASON_LINE_RESET);
                        continue;
                    }
                    try {
                        R r = forwardService.resumeForwardAsSystem(f.getId());
                        if (r != null && r.getCode() == 0) {
                            resumed++;
                        } else {
                            log.info("每月重置恢复转发[{}]没成: {}", f.getId(), r == null ? null : r.getMsg());
                        }
                    } catch (Exception e) {
                        log.warn("每月重置恢复转发[{}]失败: {}", f.getId(), e.getMessage());
                    }
                }
            }
            return resumed;
        } catch (Exception e) {
            log.warn("每月重置恢复线路失败(用户 {}): {}", userId, e.getMessage());
            return 0;
        }
    }

    /** 老的隧道权限转发:那份权限自己是不是该停(判据照 FlowController.checkUserTunnelRelatedLimits) */
    private boolean userTunnelBlocked(Forward f, long now) {
        UserTunnel ut = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", f.getUserId()).eq("tunnel_id", f.getTunnelId()).last("limit 1"));
        if (ut == null) {
            return false; // 分给车友的裸转发:没有隧道权限
        }
        return userTunnelBlocked(ut.getStatus(), ut.getFlow(), ut.getInFlow(), ut.getOutFlow(), ut.getExpTime(), now);
    }

    static boolean userTunnelBlocked(Integer status, Long flowGb, Long in, Long out, Long expTime, long now) {
        if (status == null || status != 1) {
            return true;
        }
        if (AccountLimits.expired(expTime, now)) {
            return true;
        }
        long used = (in == null ? 0L : in) + (out == null ? 0L : out);
        long flow = flowGb == null ? 0L : flowGb;
        if (flow > Long.MAX_VALUE / BYTES_TO_GB) {
            return false;
        }
        return used >= flow * BYTES_TO_GB; // 老路径对隧道权限就是 >=,0 也算停
    }

    /**
     * 再试也拉不起来的:转发自己到期、隧道停用 / 不存在、转发不存在、入口 / 出口节点被删了、
     * 节点上找不到这条服务(入口或隧道转发的远端 not found,节点重装过之类,要车主重新保存)。
     * 节点离线 / 超时(「节点不在线」「等待响应超时」)不算,留着下一轮再试。
     */
    static boolean permanentFailure(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("已到期") || msg.contains("隧道已禁用") || msg.contains("隧道不存在") || msg.contains("转发不存在")
                || msg.contains("节点不存在") || msg.contains("not found") || msg.contains("找不到这条转发的服务");
    }

    /** 这个车友每条线路(机器 × 落地)下的协议转发,key 见 key() */
    public Map<String, List<Forward>> lineForwards(Long userId) {
        Map<String, List<Long>> ids = new LinkedHashMap<>();
        Map<Long, Inbound> cache = new HashMap<>();
        List<InboundUser> ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>().eq("user_id", userId));
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
            ids.computeIfAbsent(key(in.getNodeId(), in.getLandingId()), k -> new ArrayList<>()).add(iu.getGostForwardId());
        }
        Map<String, List<Forward>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : ids.entrySet()) {
            List<Forward> fs = forwardService.list(new QueryWrapper<Forward>().in("id", e.getValue()));
            out.put(e.getKey(), fs);
        }
        return out;
    }

    public static String key(Long nodeId, Long landingId) {
        return nodeId + "/" + (landingId == null ? "-" : landingId);
    }

    public static long usedBytes(List<Forward> fs) {
        long used = 0L;
        if (fs == null) {
            return used;
        }
        for (Forward f : fs) {
            used += (f.getInFlow() == null ? 0L : f.getInFlow()) + (f.getOutFlow() == null ? 0L : f.getOutFlow());
        }
        return used;
    }
}
