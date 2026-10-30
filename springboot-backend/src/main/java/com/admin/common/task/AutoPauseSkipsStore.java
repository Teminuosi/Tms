package com.admin.common.task;

import com.admin.common.utils.AutoPauseSkips;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * AutoPauseSkips 的持久化:记录写进 auto_pause_skip 表,面板重启后读回来。
 *
 * 老版本在「自动暂停按原名 not found」时把转发写成 status=0(节点上还在跑),这个脏 0 在库里、重启也在;
 * 新版本不写脏 0,改记在这里。只放内存的话,重启后 CheckExpiryAsync 会按 _0 把老版本一直没停的转发真停掉。
 *
 * 只新建这一张独立的表,不碰任何已有表。在 @PostConstruct 里建表、装入,早于所有定时任务。
 * 建表或读写失败只记日志,退回只在内存(和没有这张表之前一样),不影响启动。
 */
@Slf4j
@Component
public class AutoPauseSkipsStore implements AutoPauseSkips.Persist {

    @Resource
    private DataSource dataSource;

    @PostConstruct
    public void init() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `auto_pause_skip` ("
                    + "`forward_id` BIGINT NOT NULL PRIMARY KEY, "
                    + "`stamp` VARCHAR(64) NOT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动暂停原名 not found、没写 status=0 的转发(老版本此时写成 0)'");
            // 转发已经删掉的记录没用了
            st.executeUpdate("DELETE FROM `auto_pause_skip` WHERE `forward_id` NOT IN (SELECT `id` FROM `forward`)");
            Map<Long, String> loaded = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT `forward_id`, `stamp` FROM `auto_pause_skip`")) {
                while (rs.next()) {
                    loaded.put(rs.getLong(1), rs.getString(2));
                }
            }
            AutoPauseSkips.attach(this, loaded);
            if (!loaded.isEmpty()) {
                log.info("自动暂停跳过记录已装入 {} 条", loaded.size());
            }
        } catch (Exception e) {
            log.warn("auto_pause_skip 表不可用,自动暂停跳过记录只保存在内存: {}", e.getMessage());
        }
    }

    @Override
    public void save(long forwardId, String stamp) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO `auto_pause_skip` (`forward_id`, `stamp`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `stamp` = VALUES(`stamp`)")) {
            ps.setLong(1, forwardId);
            ps.setString(2, stamp);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("写 auto_pause_skip 失败(转发 {}): {}", forwardId, e.getMessage());
        }
    }

    @Override
    public void delete(long forwardId, String stamp) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM `auto_pause_skip` WHERE `forward_id` = ? AND `stamp` = ?")) {
            ps.setLong(1, forwardId);
            ps.setString(2, stamp);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("删 auto_pause_skip 失败(转发 {}): {}", forwardId, e.getMessage());
        }
    }
}
