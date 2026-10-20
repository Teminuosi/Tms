package com.admin.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 监控(历史曲线 / 延迟 / 告警 / 状态页)用到的表。
 *
 * 只建新表、从不碰已有的表,所以老用户升级后原有功能完全不受影响。
 * 放在启动时建,而不是 gost.sql / temp_migration.sql:
 * gost.sql 只在新装时跑一次,temp_migration.sql 源码版升级根本不跑 ——
 * 只有这里能覆盖所有安装方式。每条都 IF NOT EXISTS、失败只记日志:
 * 建表失败顶多是监控不可用,不能把整个面板拖死。
 */
@Slf4j
@Component
@Order(2)
public class MonitorSchema implements ApplicationRunner {

    @Resource
    private JdbcTemplate jdbc;

    private static final String[] DDL = {
            // gran: 1 = 每分钟一条(留 7 天),60 = 每小时一条(留 90 天)
            // rx/tx: 这个时间段内收/发的字节数(节点报的是开机累计值,这里存的是差值)
            "CREATE TABLE IF NOT EXISTS `monitor_metric` (" +
                    "`node_id` INT NOT NULL, `gran` SMALLINT NOT NULL, `ts` BIGINT NOT NULL COMMENT '时段起点(毫秒)'," +
                    "`cpu_avg` FLOAT NOT NULL DEFAULT 0, `cpu_max` FLOAT NOT NULL DEFAULT 0, `mem_avg` FLOAT NOT NULL DEFAULT 0," +
                    "`rx` BIGINT NOT NULL DEFAULT 0, `tx` BIGINT NOT NULL DEFAULT 0, `samples` INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (`node_id`, `gran`, `ts`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转发机历史指标'",
            "CREATE TABLE IF NOT EXISTS `monitor_probe_target` (" +
                    "`id` INT NOT NULL AUTO_INCREMENT, `name` VARCHAR(64) NOT NULL, `host` VARCHAR(255) NOT NULL," +
                    "`port` INT NOT NULL DEFAULT 80, `enabled` TINYINT NOT NULL DEFAULT 1, `sort` INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='延迟监测目标'",
            "CREATE TABLE IF NOT EXISTS `monitor_probe` (" +
                    "`node_id` INT NOT NULL, `target_id` INT NOT NULL, `gran` SMALLINT NOT NULL, `ts` BIGINT NOT NULL," +
                    "`avg_ms` FLOAT NULL COMMENT '全丢时为 NULL', `loss` FLOAT NOT NULL DEFAULT 0, `samples` INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (`node_id`, `target_id`, `gran`, `ts`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='延迟监测结果'",
            // 单独一张表放转发机的到期日/流量额度,不往 node 表加列:
            // node 表一查就是全量字段,新后端先起、列还没加时会 Unknown column 直接崩
            "CREATE TABLE IF NOT EXISTS `monitor_node_meta` (" +
                    "`node_id` INT NOT NULL, `expire_date` DATE NULL, `quota_gb` INT NULL COMMENT '月流量额度,NULL=不限'," +
                    "`reset_day` TINYINT NOT NULL DEFAULT 1 COMMENT '每月几号重置流量'," +
                    "`traffic_mode` VARCHAR(8) NOT NULL DEFAULT 'both' COMMENT '额度怎么算:both 上下行相加 / out 只算上行 / max 取大的'," +
                    "`public_show` TINYINT NOT NULL DEFAULT 1," +
                    "`public_name` VARCHAR(64) NULL COMMENT '状态页上显示的名字,空=用转发机名'," +
                    "PRIMARY KEY (`node_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转发机到期/额度'",
            // 键值设置。不复用 vite_config:那边 value 只有 200 字符
            "CREATE TABLE IF NOT EXISTS `monitor_setting` (" +
                    "`k` VARCHAR(64) NOT NULL, `v` TEXT NULL, PRIMARY KEY (`k`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控设置'",
    };

    @Override
    public void run(ApplicationArguments args) {
        for (String ddl : DDL) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                log.warn("监控表创建失败(监控功能不可用,不影响其它功能): {}", e.getMessage());
            }
        }
        seedProbeTargets();
    }

    /**
     * 第一次建表时放三个默认目标(国内三网)。只放一次:靠 monitor_setting 记一笔,
     * 车主自己删光了也不会再冒出来。
     * 用的是社区常用的三网测速点(按省份+运营商命名,解析到对应运营商的机房)。
     * 不用运营商官网:国外节点解析官网常被 CDN 分到海外,测出来就不是到国内的延迟;
     * 也不用运营商 DNS 的 53 端口:实测 TCP 53 大多不通。
     */
    private void seedProbeTargets() {
        try {
            Integer done = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM monitor_setting WHERE k = 'probe_seeded'", Integer.class);
            if (done != null && done > 0) {
                return;
            }
            jdbc.update("INSERT INTO monitor_probe_target (name, host, port, enabled, sort) VALUES " +
                    "('上海电信', 'sh-ct-v4.ip.zstaticcdn.com', 80, 1, 1), " +
                    "('北京联通', 'bj-cu-v4.ip.zstaticcdn.com', 80, 1, 2), " +
                    "('广东移动', 'gd-cm-v4.ip.zstaticcdn.com', 80, 1, 3)");
            jdbc.update("INSERT INTO monitor_setting (k, v) VALUES ('probe_seeded', '1')");
        } catch (Exception e) {
            log.warn("写入默认延迟监测目标失败: {}", e.getMessage());
        }
    }
}
