-- phpMyAdmin SQL Dump
-- version 5.2.0
-- https://www.phpmyadmin.net/
--
-- 主机： localhost
-- 生成日期： 2025-08-14 21:52:52
-- 服务器版本： 5.7.40-log
-- PHP 版本： 7.4.33

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- 数据库： `gost`
--

-- --------------------------------------------------------

--
-- 表的结构 `forward`
--

CREATE TABLE `forward` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `user_name` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `in_port` int(10) NOT NULL,
  `out_port` int(10) DEFAULT NULL,
  `remote_addr` longtext NOT NULL,
  `strategy` varchar(100) NOT NULL DEFAULT 'fifo',
  `interface_name` varchar(200) DEFAULT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL,
  `inx` int(10) NOT NULL DEFAULT '0',
  `speed_id` int(10) DEFAULT NULL,
  `exp_time` bigint(20) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `node`
--

CREATE TABLE `node` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `secret` varchar(100) NOT NULL,
  `ip` longtext,
  `server_ip` varchar(100) NOT NULL,
  -- 注意:domain / cert_mode / cert_path / key_path 由本文件后半段的
  -- 「合体面板 schema」用 ALTER TABLE 添加,别在这里重复定义 ——
  -- MySQL 5.7 的 ADD COLUMN 没有 IF NOT EXISTS,重复了会报 1060,
  -- 而 initdb 脚本一报错就整个中断,后面的 inbound 等表全都建不出来。
  `port_sta` int(10) NOT NULL,
  `port_end` int(10) NOT NULL,
  `version` varchar(100) DEFAULT NULL,
  `http` int(10) NOT NULL DEFAULT '0',
  `tls` int(10) NOT NULL DEFAULT '0',
  `socks` int(10) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `speed_limit`
--

CREATE TABLE `speed_limit` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `speed` int(10) NOT NULL,
  `mode` tinyint(4) NOT NULL DEFAULT '0',
  `total` int(10) NOT NULL DEFAULT '0',
  `tunnel_id` int(10) NOT NULL,
  `tunnel_name` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `statistics_flow`
--

CREATE TABLE `statistics_flow` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `total_flow` bigint(20) NOT NULL,
  `time` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tunnel`
--

CREATE TABLE `tunnel` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `traffic_ratio` decimal(10,1) NOT NULL DEFAULT '1.0',
  `in_node_id` int(10) NOT NULL,
  `in_ip` varchar(100) NOT NULL,
  `out_node_id` int(10) NOT NULL,
  `out_ip` varchar(100) NOT NULL,
  `type` int(10) NOT NULL,
  `protocol` varchar(10) NOT NULL DEFAULT 'tls',
  `flow` int(10) NOT NULL,
  `tcp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `udp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `interface_name` varchar(200) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user`
--

CREATE TABLE `user` (
  `id` int(10) NOT NULL,
  `user` varchar(100) NOT NULL,
  `pwd` varchar(100) NOT NULL,
  `role_id` int(10) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `num` int(10) NOT NULL,
  `all_sub_token` varchar(64) DEFAULT NULL COMMENT '全部线路聚合订阅token',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `user`
--

INSERT INTO `user` (`id`, `user`, `pwd`, `role_id`, `exp_time`, `flow`, `in_flow`, `out_flow`, `flow_reset_time`, `num`, `created_time`, `updated_time`, `status`) VALUES
(1, 'admin_user', '3c85cdebade1c51cf64ca9f3c09d182d', 0, 2727251700000, 99999, 0, 0, 1, 99999, 1748914865000, 1754011744252, 1);

-- --------------------------------------------------------

--
-- 表的结构 `user_tunnel`
--

CREATE TABLE `user_tunnel` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `speed_id` int(10) DEFAULT NULL,
  `num` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `vite_config`
--

CREATE TABLE `vite_config` (
  `id` int(10) NOT NULL,
  `name` varchar(200) NOT NULL,
  `value` varchar(200) NOT NULL,
  `time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `vite_config`
--

INSERT INTO `vite_config` (`id`, `name`, `value`, `time`) VALUES
(1, 'app_name', 'TMS', 1755147963000);

--
-- 转储表的索引
--

--
-- 表的索引 `forward`
--
ALTER TABLE `forward`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `node`
--
ALTER TABLE `node`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `speed_limit`
--
ALTER TABLE `speed_limit`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `statistics_flow`
--
ALTER TABLE `statistics_flow`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `tunnel`
--
ALTER TABLE `tunnel`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user_tunnel`
--
ALTER TABLE `user_tunnel`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `vite_config`
--
ALTER TABLE `vite_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `name` (`name`);

--
-- 在导出的表使用AUTO_INCREMENT
--

--
-- 使用表AUTO_INCREMENT `forward`
--
ALTER TABLE `forward`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `node`
--
ALTER TABLE `node`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `speed_limit`
--
ALTER TABLE `speed_limit`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `statistics_flow`
--
ALTER TABLE `statistics_flow`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel`
--
ALTER TABLE `tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user`
--
ALTER TABLE `user`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user_tunnel`
--
ALTER TABLE `user_tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `vite_config`
--
ALTER TABLE `vite_config`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;

-- ============================================================
-- TMS 合体面板 schema(协议管理 / 中转 / 线路)
-- 新装时随本文件一次建好;老库升级请单独执行 hybrid-schema-v1/v2/v3.sql。
-- 注意:合并进来后,docker-compose 里不要再额外挂载那三个文件,
--       否则 ALTER TABLE 会重复执行报 1060 导致 MySQL 初始化中断。
-- ============================================================


-- flux 合体面板 · 阶段1 数据库 schema(协议搭建 + 限速)
-- 加法式迁移:只新增表/列,不动现有数据。可在现有 flux 库上直接执行。
-- 新装最终会并进 gost.sql;此文件供现有库升级用。
-- 目标库:MySQL 5.7 / utf8mb4。


-- ------------------------------------------------------------
-- 1) node 加"有域名/无域名"相关列
--    cert_mode: 0=无域名(Reality/自签),1=有域名(正经 TLS 证书)
--    MySQL 5.7 的 ADD COLUMN 不支持 IF NOT EXISTS,重复执行会报 1060,忽略即可。
-- ------------------------------------------------------------
ALTER TABLE `node`
  ADD COLUMN `domain`    varchar(255) DEFAULT NULL COMMENT '有域名节点的域名，无域名留空',
  ADD COLUMN `cert_mode` int(10)      NOT NULL DEFAULT 0 COMMENT '0=无域名(Reality/自签) 1=有域名TLS',
  ADD COLUMN `cert_path` varchar(500) DEFAULT NULL COMMENT '有域名时证书路径',
  ADD COLUMN `key_path`  varchar(500) DEFAULT NULL COMMENT '有域名时私钥路径';

-- ------------------------------------------------------------
-- 2) inbound：协议入站(一条 = 一个 sing-box 本机入站)
--    listen_port 只在 127.0.0.1 监听,公网口由 gost 转发占用(限速)。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inbound` (
  `id`           int(10)      NOT NULL AUTO_INCREMENT,
  `node_id`      int(10)      NOT NULL COMMENT '落在哪台节点',
  `tag`          varchar(100) NOT NULL COMMENT 'sing-box inbound tag',
  `protocol`     varchar(50)  NOT NULL COMMENT 'vless/vmess/trojan/shadowsocks/hysteria2',
  `listen_port`  int(10)      NOT NULL COMMENT 'sing-box 本机监听口(127.0.0.1)',
  `security`     varchar(20)  NOT NULL DEFAULT 'reality' COMMENT 'none/tls/reality',
  `sni`          varchar(255) DEFAULT NULL COMMENT 'TLS/Reality 的 SNI',
  `dest`         varchar(255) DEFAULT NULL COMMENT 'Reality 借用的目标站点',
  `public_key`   varchar(255) DEFAULT NULL COMMENT 'Reality 公钥',
  `private_key`  varchar(255) DEFAULT NULL COMMENT 'Reality 私钥',
  `short_id`     varchar(100) DEFAULT NULL COMMENT 'Reality shortId',
  `config_json`  longtext              COMMENT '该入站完整 sing-box JSON(后端生成、下发节点)',
  `remark`       varchar(255) DEFAULT NULL,
  `status`       int(10)      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `created_time` bigint(20)   NOT NULL,
  `updated_time` bigint(20)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inbound_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 3) inbound_user：用户在某入站里的凭证 + 对应的 gost 前置转发
--    gost_forward_id 指向 forward 表:那条转发带该用户的限速/流量/到期。
--    客户端最终连的是那条 forward 的公网端口(被限速),落地到本入站。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inbound_user` (
  `id`              int(10)      NOT NULL AUTO_INCREMENT,
  `inbound_id`      int(10)      NOT NULL,
  `user_id`         int(10)      NOT NULL COMMENT '关联 user 表(子账号)',
  `uuid`            varchar(100) DEFAULT NULL COMMENT 'vless/vmess 用',
  `password`        varchar(255) DEFAULT NULL COMMENT 'trojan/ss/hysteria2 用',
  `gost_forward_id` int(10)      DEFAULT NULL COMMENT '对应的 gost 前置转发(带限速/流量/到期)',
  `sub_token`       varchar(100) DEFAULT NULL COMMENT '订阅链接 token',
  `status`          int(10)      NOT NULL DEFAULT 1,
  `created_time`    bigint(20)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iu_inbound` (`inbound_id`),
  KEY `idx_iu_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 4) speed_limit：tunnel_id 改为可空(合体面板协议限速不绑隧道,
--    分配协议用户时按需把限速器推到协议节点)。重复执行无害。
-- ------------------------------------------------------------
ALTER TABLE `speed_limit` MODIFY COLUMN `tunnel_id` bigint(20) NULL DEFAULT NULL;


-- flux 合体面板 · 阶段3 数据库 schema(中转:前置机协议 + 落地出口)
-- 加法式迁移:只新增表/列,不动现有数据。可在现有 flux 库上直接执行。
-- MySQL 5.7 的 ADD COLUMN 不支持 IF NOT EXISTS,重复执行报 1060,忽略即可。
-- 目标库:MySQL 5.7 / utf8mb4。


-- ------------------------------------------------------------
-- 1) landing:可复用的「落地」出口。粘贴一条节点分享链接建成,
--    面板解析成 sing-box 出站。一条落地可分给多台前置机复用。
--    link:原始分享链接(socks5://user:pass@ip:port / ss:// / vmess:// / vless:// / trojan:// / hysteria2://…)
--    outbound_json:解析后的 sing-box outbound(下发时按 landing_id 注入节点配置)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `landing` (
  `id`            int(10)      NOT NULL AUTO_INCREMENT,
  `name`          varchar(100) NOT NULL COMMENT '落地名称(自己起,如 泰国住宅)',
  `type`          varchar(30)  NOT NULL COMMENT 'socks5/shadowsocks/vmess/vless/trojan/hysteria2',
  `link`          longtext              COMMENT '原始分享链接',
  `outbound_json` longtext              COMMENT '解析后的 sing-box outbound JSON',
  `remark`        varchar(255) DEFAULT NULL,
  `status`        int(10)      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `created_time`  bigint(20)   NOT NULL,
  `updated_time`  bigint(20)   DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 2) inbound 加 landing_id:空=直连(协议管理,本机出网),
--    有=中转(该入站的流量经这个落地出网)。加法,不影响已有直连入站。
-- ------------------------------------------------------------
ALTER TABLE `inbound`
  ADD COLUMN `landing_id` int(10) DEFAULT NULL COMMENT '落地ID:空=直连,有=经该落地中转出网';


-- TMS 面板 · 阶段4 数据库 schema(把「线路」正式建模,支持每条线路独立配额)
-- 加法式迁移:只新增表,不动现有数据。可在现有库上直接执行。
-- 目标库:MySQL 5.7 / utf8mb4。


-- ------------------------------------------------------------
-- inbound_line:一条「线路」= 车友 × 机器 × 落地组
--   landing_id 为空 = 该机器的直连线路;非空 = 该落地的中转线路。
--   同一台机器的直连和每个中转,各算一条线路、各一条订阅、各一份配额。
--
--   sub_token : 这条线路的订阅 token(该线路所有协议共享)
--   flow      : 这条线路的流量配额(GB);0 或 NULL = 不单独限,只受账号总流量约束
--   exp_time  : 这条线路的到期时间(epoch ms);空 = 不单独限
--   已用流量不在这里存,实时汇总该线路各协议对应转发的 in_flow+out_flow。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inbound_line` (
  `id`           int(10)      NOT NULL AUTO_INCREMENT,
  `user_id`      int(10)      NOT NULL COMMENT '车友(user 表)',
  `node_id`      int(10)      NOT NULL COMMENT '机器',
  `landing_id`   int(10)      DEFAULT NULL COMMENT '落地ID:空=直连线路,非空=该落地的中转线路',
  `sub_token`    varchar(100) DEFAULT NULL COMMENT '该线路的订阅 token',
  `flow`         bigint(20)   DEFAULT NULL COMMENT '该线路流量配额(GB);0/NULL=不单独限',
  `exp_time`     bigint(20)   DEFAULT NULL COMMENT '该线路到期时间(epoch ms);空=不单独限',
  `status`       int(10)      NOT NULL DEFAULT 1 COMMENT '1=正常 0=已停(超额/到期)',
  `created_time` bigint(20)   NOT NULL,
  `updated_time` bigint(20)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_line_user` (`user_id`),
  KEY `idx_line_user_node` (`user_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
