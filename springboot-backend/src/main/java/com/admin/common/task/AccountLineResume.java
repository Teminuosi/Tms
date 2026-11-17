package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.AccountLimits;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.User;
import com.admin.mapper.InboundLineMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
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
import java.util.Objects;

/**
 * 账号总闸放开后,把「只因为账号被停」的协议 / 中转线路拉起来。
 *
 * 【为什么要有】账号到期 / 停用 / 账号总流量跑满时,FlowController.checkUserAccountLimits 和
 * CheckExpiryAsync 会把这个人名下所有转发停掉,但线路记录(inbound_line.status)不动。
 * 以前没有任何地方在账号恢复后把它们拉起来 —— 车主续了账号、加了总流量,车友照样连不上。
 *
 * 【只拉哪些】
 *   - 账号现在必须不再被总闸挡着(未到期、未停用、总流量没跑满);
 *   - 线路本身 status=1(车主手动停的、线路自己跑满 / 到期被停的都是 0,一律不碰);
 *   - 线路按自己的判据也不该停(和 InboundServiceImpl.updateLine 第 3 步同一套,见 AccountLimits.lineMayResume);
 *   - 转发 status=0 的才拉,拉法走 ForwardService.resumeForwardAsSystem(带转发自己的到期守卫)。
 * 只碰协议 / 中转线路的转发(inbound_user.gost_forward_id 指向的);老的隧道权限转发、分给车友的裸转发不动。
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
    private InboundMapper inboundMapper;

    @Resource
    private InboundUserMapper inboundUserMapper;

    @Resource
    private InboundLineMapper inboundLineMapper;

    /** @return 实际拉起来的转发条数 */
    public int resumeIfAccountOk(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            User u = userService.getById(userId);
            if (u == null || AccountLimits.isAdmin(u)) {
                return 0;
            }
            long now = System.currentTimeMillis();
            if (AccountLimits.accountBlocked(u, now)) {
                return 0;
            }
            List<InboundLine> lines = inboundLineMapper.selectList(new QueryWrapper<InboundLine>().eq("user_id", userId));
            if (lines == null || lines.isEmpty()) {
                return 0;
            }
            Map<String, List<Forward>> byLine = lineForwards(userId);
            int resumed = 0;
            for (InboundLine line : lines) {
                List<Forward> fs = byLine.get(key(line.getNodeId(), line.getLandingId()));
                if (fs == null || fs.isEmpty()) {
                    continue;
                }
                if (!AccountLimits.lineMayResume(line, usedBytes(fs), now)) {
                    continue;
                }
                for (Forward f : fs) {
                    if (f.getStatus() == null || f.getStatus() != 0) {
                        continue;
                    }
                    try {
                        R r = forwardService.resumeForwardAsSystem(f.getId());
                        if (r != null && r.getCode() == 0) {
                            resumed++;
                        } else {
                            log.info("账号放开后恢复转发[{}]没成: {}", f.getId(), r == null ? "null" : r.getMsg());
                        }
                    } catch (Exception e) {
                        log.warn("账号放开后恢复转发[{}]失败: {}", f.getId(), e.getMessage());
                    }
                }
            }
            if (resumed > 0) {
                log.info("账号[{}]总闸放开,恢复 {} 条协议/中转转发", u.getUser(), resumed);
            }
            return resumed;
        } catch (Exception e) {
            log.warn("账号放开后恢复线路失败(用户 {}): {}", userId, e.getMessage());
            return 0;
        }
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
        for (Forward f : fs) {
            used += (f.getInFlow() == null ? 0L : f.getInFlow()) + (f.getOutFlow() == null ? 0L : f.getOutFlow());
        }
        return used;
    }

    /** 车主改车友 / 清零前后:改之前账号被总闸挡着,才需要去拉(只是改个名字、密码时什么都不动) */
    public static boolean shouldTryResume(User before, User after, long now) {
        return before != null && after != null
                && AccountLimits.accountBlocked(before, now)
                && !AccountLimits.accountBlocked(after, now)
                && Objects.equals(before.getId(), after.getId());
    }
}
