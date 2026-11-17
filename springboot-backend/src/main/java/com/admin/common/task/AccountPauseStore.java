package com.admin.common.task;

import com.admin.entity.Forward;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 「账号总闸停掉的转发」记账(account_pause 表)。
 *
 * 账号到期 / 停用 / 账号总流量跑满时,这个人名下的转发会被一起停。只有【这一次因为账号原因、
 * 从运行变成停】的转发才记进来;账号放开后只恢复这里记着的(AccountLineResume),
 * 车主手动停的、线路自己停的、老版本留下的脏状态都不在这里,一律不碰。
 *
 * 只新建这一张独立的表,@PostConstruct 里建(和 auto_pause_skip 同一个做法),不动任何已有表。
 * 建表或读写失败只记日志 —— 退化成「什么都不记、什么都不自动恢复」,和这个功能之前一样。
 *
 * fwd_created 存转发自己的 created_time:MySQL 重启后转发 id 可能被复用,对不上就不认这条记录。
 */
@Slf4j
@Component
public class AccountPauseStore {

    public static final String REASON_FLOW = "flow";
    public static final String REASON_EXPIRED = "expired";
    public static final String REASON_DISABLED = "disabled";
    /** 每月重置时线路复活了、但账号还挡着 —— 交给账号放开时一起拉 */
    public static final String REASON_LINE_RESET = "line_reset";

    @Resource
    private DataSource dataSource;

    /** 一条记录 */
    public static class Rec {
        public final long forwardId;
        public final long userId;
        public final Long fwdCreated;
        public final String reason;

        Rec(long forwardId, long userId, Long fwdCreated, String reason) {
            this.forwardId = forwardId;
            this.userId = userId;
            this.fwdCreated = fwdCreated;
            this.reason = reason;
        }

        /** 这条记录还是不是指着同一条转发(id 没被复用、没换主人) */
        public boolean matches(Forward f) {
            if (f == null || f.getUserId() == null || f.getUserId().longValue() != userId) {
                return false;
            }
            return fwdCreated == null || fwdCreated.equals(f.getCreatedTime());
        }
    }

    @PostConstruct
    public void init() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `account_pause` ("
                    + "`forward_id` BIGINT NOT NULL PRIMARY KEY, "
                    + "`user_id` BIGINT NOT NULL, "
                    + "`fwd_created` BIGINT DEFAULT NULL, "
                    + "`reason` VARCHAR(16) NOT NULL, "
                    + "`created_time` BIGINT NOT NULL, "
                    + "`retry_since` BIGINT DEFAULT NULL, "
                    + "KEY `idx_ap_user` (`user_id`)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号总闸(到期/停用/总流量)停掉的转发,账号放开后只恢复这些'");
            try {
                // 本分支早期的测试库里建过不带这一列的表
                st.executeUpdate("ALTER TABLE `account_pause` ADD COLUMN `retry_since` BIGINT DEFAULT NULL");
            } catch (Exception ignore) {
                // 已经有了
            }
            st.executeUpdate("DELETE FROM `account_pause` WHERE `forward_id` NOT IN (SELECT `id` FROM `forward`)");
        } catch (Exception e) {
            log.warn("account_pause 表不可用,账号放开后不会自动恢复转发: {}", e.getMessage());
        }
    }

    /** 记一条;已经有记录的保留最早那条(INSERT IGNORE) */
    public void record(Forward f, String reason) {
        if (f == null || f.getId() == null || f.getUserId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT IGNORE INTO `account_pause` (`forward_id`,`user_id`,`fwd_created`,`reason`,`created_time`) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, f.getId());
            ps.setLong(2, f.getUserId());
            if (f.getCreatedTime() == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, f.getCreatedTime());
            }
            ps.setString(4, reason);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("写 account_pause 失败(转发 {}): {}", f.getId(), e.getMessage());
        }
    }

    public List<Rec> listByUser(long userId) {
        List<Rec> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT `forward_id`,`user_id`,`fwd_created`,`reason` FROM `account_pause` WHERE `user_id` = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long c = rs.getLong(3);
                    out.add(new Rec(rs.getLong(1), rs.getLong(2), rs.wasNull() ? null : c, rs.getString(4)));
                }
            }
        } catch (Exception e) {
            log.warn("读 account_pause 失败(用户 {}): {}", userId, e.getMessage());
        }
        return out;
    }

    /** 有记录的车友(给定时补恢复用) */
    public List<Long> userIds() {
        List<Long> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT `user_id` FROM `account_pause`")) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        } catch (Exception e) {
            log.debug("读 account_pause 失败: {}", e.getMessage());
        }
        return out;
    }

    public void remove(long forwardId) {
        exec("DELETE FROM `account_pause` WHERE `forward_id` = ?", forwardId);
    }

    public void removeByUser(long userId) {
        exec("DELETE FROM `account_pause` WHERE `user_id` = ?", userId);
    }

    /** 账号又被挡住时清掉失败计时 */
    public void clearRetrying(long userId) {
        exec("UPDATE `account_pause` SET `retry_since` = NULL WHERE `user_id` = ? AND `retry_since` IS NOT NULL", userId);
    }

    /** 账号已放开、这条却恢复失败:记下第一次失败的时间(之后不再改) */
    public void markRetrying(long forwardId) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE `account_pause` SET `retry_since` = ? WHERE `forward_id` = ? AND `retry_since` IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, forwardId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("改 account_pause 失败({}): {}", forwardId, e.getMessage());
        }
    }

    /**
     * 兜底:账号放开以后连续恢复失败超过 maxAgeMs 的记录删掉,免得永远重试。
     * 只看 retry_since(账号放开后才开始算),账号一直被挡着的记录不受影响 —— 停了两个月再续费也照样拉得回来。
     * @return 删掉的条数
     */
    public int dropStaleRetries(long maxAgeMs) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM `account_pause` WHERE `retry_since` IS NOT NULL AND `retry_since` < ?")) {
            ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.debug("清 account_pause 失败: {}", e.getMessage());
            return 0;
        }
    }

    private void exec(String sql, long arg) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, arg);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("改 account_pause 失败({}): {}", arg, e.getMessage());
        }
    }
}
