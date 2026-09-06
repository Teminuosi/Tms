package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.LandingUtil;
import com.admin.common.utils.SingboxUtil;
import com.admin.entity.Inbound;
import com.admin.entity.Landing;
import com.admin.entity.Node;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.LandingMapper;
import com.admin.mapper.NodeMapper;
import com.admin.service.LandingService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 落地服务实现。
 *
 * @author QAQ
 * @since 2026-07-23
 */
@Service
public class LandingServiceImpl extends ServiceImpl<LandingMapper, Landing> implements LandingService {

    @Resource
    private InboundMapper inboundMapper;
    @Resource
    private NodeMapper nodeMapper;

    @Override
    public R createLanding(LandingDto dto) {
        LandingUtil.Parsed parsed;
        try {
            parsed = LandingUtil.parse(dto.getLink());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        Landing landing = new Landing();
        landing.setName(dto.getName());
        landing.setType(parsed.type);
        landing.setLink(dto.getLink().trim());
        landing.setOutboundJson(parsed.outbound.toJSONString());
        landing.setRemark(dto.getRemark());
        landing.setStatus(1);
        landing.setCreatedTime(System.currentTimeMillis());
        landing.setUpdatedTime(System.currentTimeMillis());
        if (!this.save(landing)) {
            return R.err("落地保存失败");
        }
        return R.ok(landing);
    }

    /**
     * 改落地。粉丝反馈:「落地出口(socks5)配置无法二次更改,只能删掉这个中转协议重新创建,
     * 然后又要去指挥舱删不需要的协议,逻辑上就有点复杂了」—— 原来确实只有 create/delete。
     *
     * 这里只改库,并把「用到这条落地的机器」一并返回;
     * 重推配置交给 Controller(原因见 InboundService.pushNodeConfig 上的注释:避开循环依赖)。
     */
    @Override
    public R updateLanding(LandingDto dto) {
        if (dto.getId() == null) {
            return R.err("缺少落地 id");
        }
        Landing landing = this.getById(dto.getId());
        if (landing == null) {
            return R.err("落地不存在(可能已经被删了)");
        }
        LandingUtil.Parsed parsed;
        try {
            parsed = LandingUtil.parse(dto.getLink());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        landing.setName(dto.getName());
        landing.setType(parsed.type);
        landing.setLink(dto.getLink().trim());
        landing.setOutboundJson(parsed.outbound.toJSONString());
        if (dto.getRemark() != null) {
            landing.setRemark(dto.getRemark());
        }
        landing.setUpdatedTime(System.currentTimeMillis());
        if (!this.updateById(landing)) {
            return R.err("落地保存失败");
        }

        // 用到这条落地的机器,配置全得重推一遍,否则出口还是老的
        List<Inbound> used = inboundMapper.selectList(
                new QueryWrapper<Inbound>().eq("landing_id", landing.getId()));
        Set<Long> nodeIds = new LinkedHashSet<>();
        for (Inbound in : used) {
            if (in.getNodeId() != null) {
                nodeIds.add(in.getNodeId());
            }
        }
        JSONObject data = new JSONObject();
        data.put("landing", landing);
        data.put("nodeIds", nodeIds);
        return R.ok(data);
    }

    @Override
    public R getLandings() {
        List<Landing> list = this.list(new QueryWrapper<Landing>().orderByDesc("id"));
        return R.ok(list);
    }

    @Override
    public R deleteLanding(Long id) {
        long used = inboundMapper.selectCount(new QueryWrapper<Inbound>().eq("landing_id", id));
        if (used > 0) {
            return R.err("这条落地正在被 " + used + " 个中转协议使用,先清空对应机器的中转再删");
        }
        this.removeById(id);
        return R.ok();
    }

    @Override
    public R testLanding(Long nodeId, String link) {
        LandingUtil.Parsed parsed;
        try {
            parsed = LandingUtil.parse(link);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        // 协议落地(ss/vless…)暂不在线测,格式已校验即算通过
        if (!"socks5".equals(parsed.type)) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("skipped", true);
            r.put("type", parsed.type);
            r.put("msg", parsed.type + " 落地格式已校验(协议落地暂不支持在线测试,可直接保存)");
            return R.ok(r);
        }
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) {
            return R.err("前置机不存在");
        }
        GostDto g = SingboxUtil.TestOutbound(nodeId, parsed.outbound);
        if (g == null || !"OK".equals(g.getMsg())) {
            return R.err("经前置机测落地不通:" + (g != null && g.getMsg() != null ? g.getMsg() : "节点无响应/超时"));
        }
        JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(g.getData()));
        data.put("type", parsed.type);
        return R.ok(data); // {ok, exitIp, latencyMs, type}
    }
}
