package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostDeleteFallback;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点「IPv6 双栈」开关:把这台节点自动隧道(inbound-tunnel-node{id})上所有转发的 gost 监听
 * 在 0.0.0.0:p 和 :p(双栈)之间切换。
 *
 * 用节点程序的 SwitchServiceAddr 命令(逐条切、逐条回滚、只换监听地址),不用 UpdateService
 * (非原子、会清暂停标记)。全部成功才写库,而且只写隧道的 tcp_listen_addr / udp_listen_addr
 * 和节点的 ipv6_dual;forward / user_tunnel 一行都不碰。
 * 有任何一条失败或没回结果:把已经切过去的那些切回来,库不动,逐条报原因。
 */
@Service
public class NodeIpv6Switch {

    static final String CMD = "SwitchServiceAddr";
    static final String CAP = "listen_switch";
    static final String V6_HOST = "[::]";
    static final String V4_HOST = "0.0.0.0";

    /** 读库 / 发命令 / 写库,测试里换成假的 */
    public interface Port {
        Node node(Long nodeId);

        List<String> caps(Long nodeId);

        /** 节点上报的公网 IPv6(WebSocketServer.nodeV6Addrs) */
        List<String> v6Addrs(Long nodeId);

        Tunnel autoTunnel(Long nodeId);

        List<Forward> forwards(Long tunnelId);

        /** 车友在这条隧道上的 user_tunnel id,没有返回 null */
        Integer userTunnelId(Integer userId, Long tunnelId);

        /** 发 SwitchServiceAddr,和 GostUtil 用同一个 WebSocketServer.send_msg */
        GostDto send(Long nodeId, JSONObject data);

        /** 全部切换成功后写库;tunnelId 为 null 表示没有自动隧道,只改节点 */
        void commit(Long tunnelId, String listenHost, Long nodeId, int dual);
    }

    @Resource
    private NodeMapper nodeMapper;
    @Resource
    private TunnelMapper tunnelMapper;
    @Resource
    private ForwardMapper forwardMapper;
    @Resource
    private UserTunnelMapper userTunnelMapper;

    private Port port;

    public NodeIpv6Switch() {
    }

    /** 测试用 */
    NodeIpv6Switch(Port port) {
        this.port = port;
    }

    public R enable(Long nodeId) {
        return run(nodeId, true);
    }

    public R disable(Long nodeId) {
        return run(nodeId, false);
    }

    // ---------------- 核心逻辑 ----------------

    /** 一条要切的 gost 服务 */
    static final class Item {
        final List<String> candidates; // 服务名基名候选:原名在前,_0 在后(同 GostDeleteFallback)
        final String proto;
        final int listenPort;
        int idx;                        // 当前用的是第几个候选

        Item(List<String> candidates, String proto, int listenPort) {
            this.candidates = candidates;
            this.proto = proto;
            this.listenPort = listenPort;
        }

        String name() {
            return candidates.get(idx) + "_" + proto;
        }
    }

    R run(Long nodeId, boolean enable) {
        Port p = port();
        if (nodeId == null) {
            return R.err("缺少节点");
        }
        Node node = p.node(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }
        if (node.getStatus() == null || node.getStatus() != 1) {
            return R.err("节点不在线");
        }
        if (!p.caps(nodeId).contains(CAP)) {
            return R.err("节点程序太旧,先升级节点程序");
        }
        if (!enable) {
            // 只关客户端开过的;纯 IPv6 机器(入口地址本身是 IPv6)的 [::] 是底座设的,关了就没法连
            if (node.getIpv6Dual() == null || node.getIpv6Dual() != 1) {
                return R.err("这台没有通过客户端开启过 IPv6,不能关闭");
            }
            if (node.getServerIp() != null && node.getServerIp().contains(":")) {
                return R.err("这台是纯 IPv6 机器(入口地址是 IPv6),不能关闭 IPv6");
            }
        } else {
            List<String> v6 = p.v6Addrs(nodeId);
            if (v6 == null || v6.isEmpty()) {
                return R.err("节点没检测到公网 IPv6(机器没开 IPv6 或被关了),先在服务商后台开通");
            }
        }
        String host = enable ? V6_HOST : V4_HOST;
        int dual = enable ? 1 : 0;

        Tunnel tunnel = p.autoTunnel(nodeId);
        if (enable && tunnel != null && (V6_HOST.equals(tunnel.getTcpListenAddr()) || V6_HOST.equals(tunnel.getUdpListenAddr()))) {
            // 隧道已经是 [::](底座给纯 IPv6 节点设的),监听本来就是双栈:只记开关,不发命令
            p.commit(null, null, nodeId, dual);
            return R.ok();
        }
        if (tunnel == null) {
            p.commit(null, null, nodeId, dual);
            return R.ok();
        }

        List<Item> items = new ArrayList<>();
        for (Forward f : p.forwards(tunnel.getId())) {
            if (f.getInPort() == null) {
                continue;
            }
            List<String> names = GostDeleteFallback.candidateNames(f.getId(), f.getUserId(),
                    p.userTunnelId(f.getUserId(), tunnel.getId()));
            items.add(new Item(names, "tcp", f.getInPort()));
            items.add(new Item(names, "udp", f.getInPort()));
        }
        if (items.isEmpty()) {
            p.commit(tunnel.getId(), host, nodeId, dual);
            return R.ok();
        }

        List<Item> switched = new ArrayList<>();   // 确认已切过去的
        List<Item> uncertain = new ArrayList<>();  // 发了但没拿到结果的(超时等),回滚时一并切回
        Map<Item, String> failed = new LinkedHashMap<>();

        // 第一轮按原名;只有节点回「原名 not found」且还有候选名(_0)的,第二轮换名再发(同 firstFound)
        List<Item> round = items;
        String[] why = new String[1];
        while (!round.isEmpty()) {
            Map<String, JSONObject> res = sendRound(p, nodeId, round, enable, why);
            List<Item> next = new ArrayList<>();
            for (Item it : round) {
                JSONObject r = res == null ? null : res.get(it.name());
                if (r == null) {
                    uncertain.add(it);
                    failed.put(it, res == null ? why[0] : "节点没回这一条的结果");
                    continue;
                }
                if (r.getBooleanValue("ok")) {
                    switched.add(it);
                    continue;
                }
                String err = r.getString("error");
                if (("service " + it.name() + " not found").equals(err) && it.idx + 1 < it.candidates.size()) {
                    it.idx++;
                    next.add(it);
                    continue;
                }
                failed.put(it, err);
            }
            round = next;
        }

        if (failed.isEmpty()) {
            p.commit(tunnel.getId(), host, nodeId, dual);
            return R.ok();
        }

        // 回滚:已切过去的(以及没拿到结果的)切回原来的地址,库不动
        List<Item> back = new ArrayList<>(switched);
        back.addAll(uncertain);
        String rollbackErr = null;
        if (!back.isEmpty()) {
            Map<String, JSONObject> rb = sendRound(p, nodeId, back, !enable, why);
            List<String> bad = new ArrayList<>();
            for (Item it : back) {
                JSONObject r = rb == null ? null : rb.get(it.name());
                if (r == null) {
                    bad.add(it.name() + "(" + (rb == null ? why[0] : "没回结果") + ")");
                } else if (!r.getBooleanValue("ok")) {
                    String e = r.getString("error");
                    // 没拿到结果的那条:本来没切过去的,切回时节点回 ok(地址没变);回 not found 说明节点上本来就没有,不算切回失败
                    if (!uncertain.contains(it) || !("service " + it.name() + " not found").equals(e)) {
                        bad.add(it.name() + "(" + zh(it, e) + ")");
                    }
                }
            }
            if (!bad.isEmpty()) {
                rollbackErr = "切回也失败了:" + String.join(";", bad);
            }
        }

        StringBuilder sb = new StringBuilder(enable ? "开启 IPv6 双栈失败" : "关闭 IPv6 双栈失败");
        sb.append(back.isEmpty() ? ",节点上没有改动" : ",已把切过去的 " + back.size() + " 条切回原地址");
        sb.append(",设置没保存。失败的:");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, String> e : failed.entrySet()) {
            parts.add(e.getKey().name() + "(端口 " + e.getKey().listenPort + "):" + zh(e.getKey(), e.getValue()));
        }
        sb.append(String.join(";", parts));
        if (rollbackErr != null) {
            sb.append("。").append(rollbackErr);
        }
        return R.err(sb.toString());
    }

    /** 发一次命令;拿不到 results 返回 null(why[0] 记原因) */
    private static Map<String, JSONObject> sendRound(Port p, Long nodeId, List<Item> round, boolean toDual, String[] why) {
        JSONArray arr = new JSONArray();
        for (Item it : round) {
            JSONObject o = new JSONObject();
            o.put("name", it.name());
            o.put("addr", (toDual ? ":" : V4_HOST + ":") + it.listenPort);
            arr.add(o);
        }
        JSONObject data = new JSONObject();
        data.put("items", arr);
        GostDto r = p.send(nodeId, data);
        JSONArray results = null;
        if (r != null && r.getData() != null) {
            try {
                Object d = r.getData();
                JSONObject dj = d instanceof JSONObject ? (JSONObject) d : (JSONObject) JSONObject.toJSON(d);
                results = dj.getJSONArray("results");
            } catch (Exception ignore) {
                results = null;
            }
        }
        if (results == null) {
            why[0] = r == null || r.getMsg() == null ? "节点没有响应" : r.getMsg();
            return null;
        }
        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject x = results.getJSONObject(i);
            if (x != null && x.getString("name") != null) {
                out.put(x.getString("name"), x);
            }
        }
        return out;
    }

    private static String zh(Item it, String err) {
        if (err == null || err.isEmpty()) {
            return "未知错误";
        }
        if (("service " + it.name() + " not found").equals(err)) {
            return "节点上没有这个服务";
        }
        if (err.contains("address already in use")) {
            return "端口被占用:" + err;
        }
        return err;
    }

    // ---------------- 写库(只动这几列) ----------------

    static LambdaUpdateWrapper<Tunnel> tunnelListenUpdate(Long tunnelId, String host) {
        return new LambdaUpdateWrapper<Tunnel>()
                .eq(Tunnel::getId, tunnelId)
                .set(Tunnel::getTcpListenAddr, host)
                .set(Tunnel::getUdpListenAddr, host);
    }

    static LambdaUpdateWrapper<Node> nodeDualUpdate(Long nodeId, int dual) {
        return new LambdaUpdateWrapper<Node>()
                .eq(Node::getId, nodeId)
                .set(Node::getIpv6Dual, dual);
    }

    private Port port() {
        if (port == null) {
            port = new DbPort();
        }
        return port;
    }

    private final class DbPort implements Port {
        @Override
        public Node node(Long nodeId) {
            return nodeMapper.selectById(nodeId);
        }

        @Override
        public List<String> caps(Long nodeId) {
            return WebSocketServer.nodeCaps(nodeId);
        }

        @Override
        public List<String> v6Addrs(Long nodeId) {
            return WebSocketServer.nodeV6Addrs(nodeId);
        }

        @Override
        public Tunnel autoTunnel(Long nodeId) {
            return tunnelMapper.selectOne(new QueryWrapper<Tunnel>()
                    .eq("in_node_id", nodeId).eq("type", 1)
                    .eq("name", "inbound-tunnel-node" + nodeId).last("limit 1"));
        }

        @Override
        public List<Forward> forwards(Long tunnelId) {
            List<Forward> l = forwardMapper.selectList(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId));
            return l == null ? Collections.emptyList() : l;
        }

        @Override
        public Integer userTunnelId(Integer userId, Long tunnelId) {
            List<UserTunnel> l = userTunnelMapper.selectList(new QueryWrapper<UserTunnel>()
                    .eq("user_id", userId).eq("tunnel_id", tunnelId));
            return l == null || l.isEmpty() ? null : l.get(0).getId();
        }

        @Override
        public GostDto send(Long nodeId, JSONObject data) {
            return WebSocketServer.send_msg(nodeId, data, CMD);
        }

        @Override
        public void commit(Long tunnelId, String listenHost, Long nodeId, int dual) {
            if (tunnelId != null) {
                tunnelMapper.update(null, tunnelListenUpdate(tunnelId, listenHost));
            }
            nodeMapper.update(null, nodeDualUpdate(nodeId, dual));
        }
    }
}
