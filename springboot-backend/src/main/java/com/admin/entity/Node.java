package com.admin.entity;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String secret;

    private String ip;

    private String serverIp;

    /**
     * 连接域名(可选)。填了就用它替代 server_ip 生成给车友的节点链接,
     * 这样车友在客户端里看到的是域名而不是车主的真实 IP。
     * 留空则沿用 server_ip。注意:域名只是不直接显示 IP,ping 一下还是查得到,
     * 要做到查不到得走 CDN。
     */
    private String domain;

    /**
     * 该节点上 sing-box 是否在运行(不入库,查询时从节点上报的实时状态填入)。
     * gost 和 sing-box 是两个独立服务:sing-box 挂了 gost 照样在线,
     * 面板不单独标出来的话,表现就是「节点显示在线但所有协议都连不上」。
     * null = 节点还没上报过(老版本节点或刚连上)。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxRunning;

    /**
     * 这台机装没装 sing-box。null = 节点版本较老、没上报过这个字段。
     * 区分它是因为「没装」和「装了没跑」的修法完全不同:前者要重跑安装脚本
     * (国内机常见于下载 GitHub 失败),后者 systemctl enable --now 就行,
     * 而对没装的机器执行后者只会得到 "Unit file does not exist"。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxInstalled;

    /** sing-box 正在下载安装中。刚建完协议的那一两分钟就是这个状态,界面上该显示等待而不是报错 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxInstalling;

    /** 上次安装失败的原因(节点上报)。有值时直接显示给车主,省得上机器翻 journalctl */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxInstallErr;

    /** 服务器 IPv6 地址(可选,裸地址不带方括号,小写)。为空时节点上报的第一个公网 v6 会自动填入 */
    private String serverIpv6;

    /** 订阅里是否给 IPv6 地址出节点(0/1) */
    private Integer ipv6Sub;

    /** IPv6 双栈(0/1) */
    private Integer ipv6Dual;

    /** 节点上报的公网 IPv6 地址(不入库) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<String> reportedIpv6;

    /** 节点程序是否支持切换监听地址(上报的 caps 含 listen_switch;不入库) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean canSwitchListen;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

}
