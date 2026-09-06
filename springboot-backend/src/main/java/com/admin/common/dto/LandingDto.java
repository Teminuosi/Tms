package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 新建落地:名称 + 一条分享链接(socks5/ss/vmess/vless/trojan/hysteria2)。
 */
@Data
public class LandingDto {

    @NotBlank(message = "落地名称不能为空")
    /** 改落地时必传;新建时留空 */
    private Long id;

    private String name;

    @NotBlank(message = "分享链接不能为空")
    private String link;

    private String remark;
}
