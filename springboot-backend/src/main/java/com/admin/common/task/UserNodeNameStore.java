package com.admin.common.task;

import com.admin.common.utils.SubNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 车友节点名(按车友给线路 / 协议 / 转发起的名字,只进这个车友的订阅)。
 *
 * 只新建这一张独立的表 user_node_name,不碰任何已有表。照 AutoPauseSkipsStore:
 * 启动时 CREATE TABLE IF NOT EXISTS;建不成只记日志、这个功能暂时不可用,订阅照常(和没这功能时一样)。
 *
 * 【建不成之后会自己再试】面板机重启时 MySQL 可能比后端起得晚 —— 只在启动时判一次的话,
 * 功能会一直关着直到有人手动重启面板,而订阅悄悄退回默认名,谁也看不出来。
 * 所以不可用时,读 / 写 / 查可用性都会顺手再建一次表,最多 30 秒一次;
 * 运行中表被删了(读写报 doesn't exist)也标成不可用,走同一条重建路径。
 *
 * scope / ref_key:
 *   line    「机器id|落地id」(直连落地记 0)
 *   inbound 入站 id
 *   forward 转发 id(分到车友名下的那条)
 *
 * 建表成功时清掉指向已不存在对象的名字:MySQL 5.7 重启后自增 id 会回落到 max+1,
 * 删掉的协议 / 转发编号可能被新建的复用,不清的话新的会继承旧名字。
 */
@Slf4j
@Component
public class UserNodeNameStore {

    public static final String SCOPE_LINE = "line";
    public static final String SCOPE_INBOUND = "inbound";
    public static final String SCOPE_FORWARD = "forward";

    /** 不可用时两次重试建表之间至少隔多久 */
    static final long RETRY_INTERVAL_MS = 30_000L;

    @Resource
    private JdbcTemplate jdbc;

    @Resource
    private PlatformTransactionManager txManager;

    private volatile boolean available = false;
    private volatile long lastTry = 0L;

    private static final String DDL = "CREATE TABLE IF NOT EXISTS `user_node_name` ("
            + "`user_id` INT NOT NULL, "
            + "`scope` VARCHAR(8) NOT NULL COMMENT 'line / inbound / forward', "
            + "`ref_key` VARCHAR(40) NOT NULL COMMENT 'line: 机器id|落地id(直连0); inbound: 入站id; forward: 转发id', "
            + "`name` VARCHAR(64) NOT NULL, "
            + "`updated_time` BIGINT NOT NULL DEFAULT 0, "
            + "PRIMARY KEY (`user_id`, `scope`, `ref_key`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车友节点名:只进该车友订阅的线路别名/协议名/转发名'";

    private static final String[] CLEANUP = {
            // 先删格式不对的行:MySQL 5.7 严格模式下,DELETE 里 CAST 一个非数字串会报错,整条清理就不执行了
            "DELETE FROM `user_node_name` WHERE `scope` NOT IN ('line', 'inbound', 'forward')",
            "DELETE FROM `user_node_name` WHERE `scope` IN ('inbound', 'forward') AND `ref_key` NOT REGEXP '^[0-9]+$'",
            "DELETE FROM `user_node_name` WHERE `scope` = 'line' AND `ref_key` NOT REGEXP '^[0-9]+[|][0-9]+$'",
            "DELETE FROM `user_node_name` WHERE `user_id` NOT IN (SELECT `id` FROM `user`)",
            "DELETE FROM `user_node_name` WHERE `scope` = 'inbound' AND NOT EXISTS ("
                    + "SELECT 1 FROM `inbound_user` iu WHERE iu.`user_id` = `user_node_name`.`user_id` "
                    + "AND iu.`inbound_id` = CAST(`user_node_name`.`ref_key` AS UNSIGNED))",
            // 一律按数字比,不拿字符串比:连接字符集和表的 utf8mb4 排序规则不同时会报 Illegal mix of collations
            "DELETE FROM `user_node_name` WHERE `scope` = 'line' AND NOT EXISTS ("
                    + "SELECT 1 FROM `inbound_user` iu JOIN `inbound` i ON i.`id` = iu.`inbound_id` "
                    + "WHERE iu.`user_id` = `user_node_name`.`user_id` "
                    + "AND i.`node_id` = CAST(SUBSTRING_INDEX(`user_node_name`.`ref_key`, '|', 1) AS UNSIGNED) "
                    + "AND IFNULL(i.`landing_id`, 0) = CAST(SUBSTRING_INDEX(`user_node_name`.`ref_key`, '|', -1) AS UNSIGNED))",
            "DELETE FROM `user_node_name` WHERE `scope` = 'forward' AND NOT EXISTS ("
                    + "SELECT 1 FROM `forward` f WHERE f.`user_id` = `user_node_name`.`user_id` "
                    + "AND f.`id` = CAST(`user_node_name`.`ref_key` AS UNSIGNED))",
    };

    @PostConstruct
    public void init() {
        tryCreate();
    }

    /** 建表 + 清理失效名字。成功返回 true。 */
    private synchronized boolean tryCreate() {
        if (available) {
            return true;
        }
        lastTry = System.currentTimeMillis();
        try {
            jdbc.execute(DDL);
        } catch (Exception e) {
            log.warn("user_node_name 表不可用,车友节点名暂时关闭(订阅照常,和没有这功能时一样;30 秒后再试): {}", e.getMessage());
            return false;
        }
        int removed = 0;
        for (String sql : CLEANUP) {
            try {
                removed += jdbc.update(sql);
            } catch (Exception e) {
                log.warn("清理失效的车友节点名失败(不影响使用): {}", e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("清掉 {} 条指向已不存在线路/协议/转发的车友节点名", removed);
        }
        available = true;
        return true;
    }

    /**
     * 可用就直接 true;不可用时距上次尝试超过 30 秒就再建一次表。
     * 【不能在别人的事务里调】MySQL 的 CREATE TABLE 会隐式提交当前事务 ——
     * 所以级联清理(都在删线路 / 删车友的事务里)不走这里,只在读、保存、查可用性时走。
     */
    private boolean ensure() {
        if (available) {
            return true;
        }
        if (System.currentTimeMillis() - lastTry < RETRY_INTERVAL_MS) {
            return false;
        }
        return tryCreate();
    }

    /** 运行中表被删了:标成不可用,下次 ensure 会(限频地)重建 */
    private void noteFailure(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("doesn't exist")) {
            available = false;
            lastTry = System.currentTimeMillis();
        }
    }

    public boolean isAvailable() {
        return ensure();
    }

    /** 一次查出这个车友的全部名字。表不可用 / 出任何错 → 空(订阅照旧规则出) */
    public SubNames.UserNames load(Long userId) {
        if (userId == null || !ensure()) {
            return SubNames.UserNames.EMPTY;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT `scope`, `ref_key`, `name` FROM `user_node_name` WHERE `user_id` = ?", userId);
            if (rows.isEmpty()) {
                return SubNames.UserNames.EMPTY;
            }
            SubNames.UserNames n = SubNames.UserNames.mutable();
            for (Map<String, Object> r : rows) {
                String scope = String.valueOf(r.get("scope"));
                String key = String.valueOf(r.get("ref_key"));
                String name = r.get("name") == null ? null : String.valueOf(r.get("name"));
                if (name == null || name.isEmpty()) {
                    continue;
                }
                try {
                    if (SCOPE_LINE.equals(scope)) {
                        n.lines.put(key, name);
                    } else if (SCOPE_INBOUND.equals(scope)) {
                        n.inbounds.put(Long.valueOf(key), name);
                    } else if (SCOPE_FORWARD.equals(scope)) {
                        n.forwards.put(Long.valueOf(key), name);
                    }
                } catch (NumberFormatException ignore) {
                    // 坏行跳过
                }
            }
            return n;
        } catch (Exception e) {
            noteFailure(e);
            log.warn("读车友节点名失败(车友 {}),本次按原规则出订阅: {}", userId, e.getMessage());
            return SubNames.UserNames.EMPTY;
        }
    }

    /**
     * 批量写(一个事务:要么全写进去,要么一条都不写)。每项 [scope, refKey, name],name 为空 = 删掉。
     * 出错抛给调用方(保存接口要如实报错)。
     */
    public void putAll(Long userId, List<String[]> writes) {
        if (!ensure()) {
            throw new IllegalStateException("user_node_name 表不可用");
        }
        try {
            new TransactionTemplate(txManager).executeWithoutResult(st -> {
                for (String[] w : writes) {
                    put(userId, w[0], w[1], w[2]);
                }
            });
        } catch (RuntimeException e) {
            noteFailure(e);
            throw e;
        }
    }

    private void put(Long userId, String scope, String refKey, String name) {
        if (name == null || name.isEmpty()) {
            jdbc.update("DELETE FROM `user_node_name` WHERE `user_id` = ? AND `scope` = ? AND `ref_key` = ?",
                    userId, scope, refKey);
            return;
        }
        jdbc.update("INSERT INTO `user_node_name` (`user_id`, `scope`, `ref_key`, `name`, `updated_time`) VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_time` = VALUES(`updated_time`)",
                userId, scope, refKey, name, System.currentTimeMillis());
    }

    // ---- 级联清理:都吞掉异常,绝不影响原来的删除流程;不建表(见 ensure 的说明) ----

    public void deleteUser(Long userId) {
        quiet("DELETE FROM `user_node_name` WHERE `user_id` = ?", userId);
    }

    /** 协议删了:所有车友对它起的名字一起清 */
    public void deleteInbound(Long inboundId) {
        quiet("DELETE FROM `user_node_name` WHERE `scope` = 'inbound' AND `ref_key` = ?", String.valueOf(inboundId));
    }

    /** 某车友不再有这个协议 */
    public void deleteUserInbound(Long userId, Long inboundId) {
        quiet("DELETE FROM `user_node_name` WHERE `user_id` = ? AND `scope` = 'inbound' AND `ref_key` = ?",
                userId, String.valueOf(inboundId));
    }

    public void deleteUserLine(Long userId, String lineKey) {
        quiet("DELETE FROM `user_node_name` WHERE `user_id` = ? AND `scope` = 'line' AND `ref_key` = ?", userId, lineKey);
    }

    /** 转发删了;新建转发拿到的 id 也调一次(防 id 复用继承旧名字) */
    public void deleteForward(Long forwardId) {
        if (forwardId == null) {
            return;
        }
        quiet("DELETE FROM `user_node_name` WHERE `scope` = 'forward' AND `ref_key` = ?", String.valueOf(forwardId));
    }

    private void quiet(String sql, Object... args) {
        if (!available) {
            return;
        }
        try {
            jdbc.update(sql, args);
        } catch (Exception e) {
            noteFailure(e);
            log.warn("清理车友节点名失败(不影响原操作): {}", e.getMessage());
        }
    }
}
