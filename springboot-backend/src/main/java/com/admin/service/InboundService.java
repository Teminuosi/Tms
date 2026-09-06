package com.admin.service;

import com.admin.common.dto.InboundDto;
import com.admin.common.dto.InboundUserDto;
import com.admin.common.lang.R;
import com.admin.entity.Inbound;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 协议入站服务(合体面板:协议搭建 + 限速)。
 *
 * @author QAQ
 * @since 2026-07-19
 */
public interface InboundService extends IService<Inbound> {

    /** 新建入站(节点生成 Reality 密钥 → 存 → 推 sing-box 配置) */
    R createInbound(InboundDto dto);

    /** 一键添加:在指定节点上把所有支持的协议一键全建出来。sni=Reality 借壳域名,空则默认 www.apple.com */
    R oneClickCreate(Long nodeId, String sni);

    /** 一键搭中转:在前置机上把全套协议建出来,流量经落地(内联粘贴的分享链接)出网。sni 同上 */
    R oneClickRelay(Long nodeId, String link, String name, String sni);

    /** 入站列表 */
    R getInbounds();

    /** 删除入站(连带其用户的 gost 转发 + 重推配置) */
    /** 把某台机器的 sing-box 配置重推一遍(改了落地出口之后要用) */
    R pushNodeConfig(Long nodeId);

    R deleteInbound(Long id);

    /** 清空某节点上目标组的协议入站(relay=true 清某落地的中转;否则清直连);连带其转发/用户 */
    R deleteInboundsByNode(Long nodeId, Boolean relay, Long landingId);

    /** 给入站分配子账号(生成 uuid + 建限速转发 + 重推 + 出客户端链接) */
    R assignUser(InboundUserDto dto);

    /** 机器卡分配:把某台机器(dto.nodeId)的全套协议一次分给车友;不传 nodeId=所有机器。返回订阅 token,车友一条订阅拿到全部协议 */
    R assignAllToUser(InboundUserDto dto);

    /** 取消某个入站用户 */
    R unassignUser(Long inboundUserId);

    /** 停用(status=0)/ 恢复(status=1)某车友的一条线路(机器 × 落地) */
    R setLineStatus(Long userId, Long nodeId, Long landingId, Integer status);

    /** 彻底删掉某车友的一条线路:分配记录 + 转发 + 线路本身,端口一并释放 */
    R deleteLine(Long userId, Long nodeId, Long landingId);

    /** Clash / Mihomo 订阅(YAML)。和 buildSubscription 同源,只是格式不同 */
    String buildClashSubscription(String token);

    /** 给协议改显示名(写 Inbound.remark);订阅链接按需生成,改完车友刷新订阅即见新名 */
    R renameInbound(Long id, String remark);

    /** 按订阅 token 生成该用户所有协议链接的 base64 订阅内容(客户端订阅用) */
    String buildSubscription(String token);

    /** 取某用户的订阅 token(兼容旧接口;订阅现在按线路,推荐 getUserLines) */
    String getUserSubToken(Long userId);

    /** 取某车友的所有订阅线路(车友×机器):每台机器一条订阅[nodeId,nodeName,type直连/中转,landingName,协议数,subToken] */
    R getUserLines(Long userId);
}
