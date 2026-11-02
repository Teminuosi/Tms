package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class NodeUpdateDto {

    @NotNull(message = "节点ID不能为空")
    private Long id;

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @NotBlank(message = "入口IP不能为空")
    private String ip;

    @NotBlank(message = "服务器ip不能为空")
    private String serverIp;

    /** 连接域名(可选):填了就用它替代 serverIp 生成给车友的节点链接 */
    private String domain;

    /** 服务器 IPv6(可选):null=不改,空串=清空,否则须是公网 IPv6(2000::/3) */
    @com.fasterxml.jackson.annotation.JsonAlias("server_ipv6")
    private String serverIpv6;

    /** 订阅出 IPv6 节点(0/1),null=不改 */
    @com.fasterxml.jackson.annotation.JsonAlias("ipv6_sub")
    private Integer ipv6Sub;

    @NotNull(message = "起始端口不能为空")
    @Min(value = 1, message = "起始端口必须大于0")
    @Max(value = 65535, message = "起始端口不能超过65535")
    private Integer portSta;

    @NotNull(message = "结束端口不能为空")
    @Min(value = 1, message = "结束端口必须大于0")
    @Max(value = 65535, message = "结束端口不能超过65535")
    private Integer portEnd;

    private Integer http;
    private Integer tls;
    private Integer socks;
} 