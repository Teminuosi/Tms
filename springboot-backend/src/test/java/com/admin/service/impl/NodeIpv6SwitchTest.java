package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 节点 IPv6 双栈开关(SwitchServiceAddr) */
class NodeIpv6SwitchTest {

    static class Fake implements NodeIpv6Switch.Port {
        Node node;
        List<String> caps = Collections.singletonList("listen_switch");
        Tunnel tunnel;
        List<Forward> forwards = new ArrayList<>();
        Map<Integer, Integer> userTunnel = new HashMap<>();
        /** 按服务全名决定节点怎么回:返回 null = ok,否则是 error */
        Function<String, String> nodeErr = n -> null;
        /** 节点上实际有的服务;null 表示都有 */
        Set<String> present;
        boolean timeout;
        List<JSONObject> sent = new ArrayList<>();
        List<Object[]> commits = new ArrayList<>();

        public Node node(Long id) { return node; }
        public List<String> caps(Long id) { return caps; }
        List<String> v6 = Collections.singletonList("2001:db8::5");
        public List<String> v6Addrs(Long id) { return v6; }
        public Tunnel autoTunnel(Long id) { return tunnel; }
        public List<Forward> forwards(Long tid) { return forwards; }
        public Integer userTunnelId(Integer uid, Long tid) { return userTunnel.get(uid); }

        public GostDto send(Long nodeId, JSONObject data) {
            sent.add(data);
            GostDto r = new GostDto();
            if (timeout && sent.size() == 1) {
                r.setMsg("等待响应超时");
                return r;
            }
            JSONArray res = new JSONArray();
            for (Object o : data.getJSONArray("items")) {
                String name = ((JSONObject) o).getString("name");
                JSONObject x = new JSONObject();
                x.put("name", name);
                String err = present != null && !present.contains(name) ? "service " + name + " not found" : nodeErr.apply(name);
                x.put("ok", err == null);
                if (err != null) x.put("error", err);
                res.add(x);
            }
            JSONObject d = new JSONObject();
            d.put("results", res);
            r.setMsg("OK");
            r.setData(d);
            return r;
        }

        public void commit(Long tunnelId, String host, Long nodeId, int dual) {
            commits.add(new Object[]{tunnelId, host, nodeId, dual});
        }
    }

    static Fake online() {
        Fake f = new Fake();
        f.node = new Node();
        f.node.setId(7L);
        f.node.setStatus(1);
        f.node.setServerIp("203.0.113.5");
        f.node.setIpv6Dual(1);
        f.tunnel = new Tunnel();
        f.tunnel.setId(3L);
        f.tunnel.setName("inbound-tunnel-node7");
        f.tunnel.setTcpListenAddr("0.0.0.0");
        f.tunnel.setUdpListenAddr("0.0.0.0");
        return f;
    }

    static Forward fwd(long id, int uid, int port) {
        Forward f = new Forward();
        f.setId(id);
        f.setUserId(uid);
        f.setInPort(port);
        f.setTunnelId(3);
        return f;
    }

    static List<String> items(JSONObject cmd) {
        List<String> out = new ArrayList<>();
        for (Object o : cmd.getJSONArray("items")) {
            JSONObject x = (JSONObject) o;
            out.add(x.getString("name") + "=" + x.getString("addr"));
        }
        return out;
    }

    @Test
    void allOkCommitsOnlyListenAndDual() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(6, 3, 20002));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals(0, r.getCode(), r.getMsg());
        assertEquals(1, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=:20001", "5_2_0_udp=:20001", "6_3_0_tcp=:20002", "6_3_0_udp=:20002"),
                items(f.sent.get(0)));
        assertEquals(1, f.commits.size());
        assertArrayEquals(new Object[]{3L, "[::]", 7L, 1}, f.commits.get(0));
    }

    @Test
    void disableUsesV4Addr() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        R r = new NodeIpv6Switch(f).disable(7L);
        assertEquals(0, r.getCode(), r.getMsg());
        assertEquals(Arrays.asList("5_2_0_tcp=0.0.0.0:20001", "5_2_0_udp=0.0.0.0:20001"), items(f.sent.get(0)));
        assertArrayEquals(new Object[]{3L, "0.0.0.0", 7L, 0}, f.commits.get(0));
    }

    @Test
    void partialFailureRollsBackExactlySucceededNoDb() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(6, 3, 20002));
        f.nodeErr = n -> n.equals("6_3_0_udp") ? "IPv6 预检失败(udp6 [::]:20002): address already in use" : null;
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(r.getMsg().contains("6_3_0_udp"), r.getMsg());
        assertTrue(r.getMsg().contains("端口被占用"), r.getMsg());
        assertEquals(2, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=0.0.0.0:20001", "5_2_0_udp=0.0.0.0:20001", "6_3_0_tcp=0.0.0.0:20002"),
                items(f.sent.get(1)));
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void timeoutRollsBackEverythingSentNoDb() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.timeout = true;
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(r.getMsg().contains("等待响应超时"), r.getMsg());
        assertEquals(2, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=0.0.0.0:20001", "5_2_0_udp=0.0.0.0:20001"), items(f.sent.get(1)));
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void fallsBackToZeroNameOnlyWhenOriginalNotFound() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));   // 车友在自动隧道上有 user_tunnel 9,但服务实际叫 _0
        f.forwards.add(fwd(6, 3, 20002));   // 原名 _8 就在
        f.userTunnel.put(2, 9);
        f.userTunnel.put(3, 8);
        f.present = new HashSet<>(Arrays.asList("5_2_0_tcp", "5_2_0_udp", "6_3_8_tcp", "6_3_8_udp"));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals(0, r.getCode(), r.getMsg());
        assertEquals(2, f.sent.size());
        assertEquals(Arrays.asList("5_2_9_tcp=:20001", "5_2_9_udp=:20001", "6_3_8_tcp=:20002", "6_3_8_udp=:20002"),
                items(f.sent.get(0)));
        assertEquals(Arrays.asList("5_2_0_tcp=:20001", "5_2_0_udp=:20001"), items(f.sent.get(1)));
        assertEquals(1, f.commits.size());
    }

    @Test
    void notFoundEverywhereFailsAndRollsBack() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(6, 3, 20002));
        f.present = new HashSet<>(Arrays.asList("6_3_0_tcp", "6_3_0_udp"));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(r.getMsg().contains("节点上没有这个服务"), r.getMsg());
        assertEquals(Arrays.asList("6_3_0_tcp=0.0.0.0:20002", "6_3_0_udp=0.0.0.0:20002"), items(f.sent.get(1)));
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void oldNodeRefusedNothingSent() {
        Fake f = online();
        f.caps = Collections.emptyList();
        f.forwards.add(fwd(5, 2, 20001));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals("节点程序太旧,先升级节点程序", r.getMsg());
        assertTrue(f.sent.isEmpty());
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void offlineRefused() {
        Fake f = online();
        f.node.setStatus(0);
        f.forwards.add(fwd(5, 2, 20001));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals("节点不在线", r.getMsg());
        assertTrue(f.sent.isEmpty());
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void noAutoTunnelOnlySetsDual() {
        Fake f = online();
        f.tunnel = null;
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals(0, r.getCode());
        assertTrue(f.sent.isEmpty());
        assertArrayEquals(new Object[]{null, null, 7L, 1}, f.commits.get(0));
    }

    @Test
    void emptyTunnelSetsColumnsWithoutSending() {
        Fake f = online();
        R r = new NodeIpv6Switch(f).disable(7L);
        assertEquals(0, r.getCode());
        assertTrue(f.sent.isEmpty());
        assertArrayEquals(new Object[]{3L, "0.0.0.0", 7L, 0}, f.commits.get(0));
    }

    @Test
    void disableRefusedWhenNotEnabledByClient() {
        for (Integer d : new Integer[]{null, 0}) {
            Fake f = online();
            f.node.setIpv6Dual(d);
            f.forwards.add(fwd(11, 5, 30001));
            R r = new NodeIpv6Switch(f).disable(7L);
            assertNotEquals(0, r.getCode());
            assertEquals("这台没有通过客户端开启过 IPv6,不能关闭", r.getMsg());
            assertTrue(f.sent.isEmpty());
            assertTrue(f.commits.isEmpty());
        }
    }

    @Test
    void disableRefusedOnIpv6OnlyNode() {
        Fake f = online();
        f.node.setServerIp("2001:db8::9");
        f.forwards.add(fwd(11, 5, 30001));
        R r = new NodeIpv6Switch(f).disable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(f.sent.isEmpty());
        assertTrue(f.commits.isEmpty());
    }

    @Test
    void enableOnAlreadyWildcardTunnelOnlySetsFlag() {
        Fake f = online();
        f.node.setIpv6Dual(0);
        f.tunnel.setTcpListenAddr("[::]");
        f.tunnel.setUdpListenAddr("[::]");
        f.forwards.add(fwd(11, 5, 30001));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals(0, r.getCode());
        assertTrue(f.sent.isEmpty());
        assertArrayEquals(new Object[]{null, null, 7L, 1}, f.commits.get(0));
    }

    @Test
    void enableRefusedWithoutReportedIpv6() {
        for (List<String> v6 : java.util.Arrays.asList(null, Collections.<String>emptyList())) {
            Fake f = online();
            f.v6 = v6;
            f.forwards.add(fwd(11, 5, 30001));
            R r = new NodeIpv6Switch(f).enable(7L);
            assertEquals("节点没检测到公网 IPv6(机器没开 IPv6 或被关了),先在服务商后台开通", r.getMsg());
            assertTrue(f.sent.isEmpty());
            assertTrue(f.commits.isEmpty());
        }
    }

    @Test
    void updateWrappersSetOnlyThoseColumns() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, Tunnel.class);
        TableInfoHelper.initTableInfo(a, Node.class);
        assertEquals("tcp_listen_addr,udp_listen_addr",
                NodeIpv6Switch.tunnelListenUpdate(3L, "[::]").getSqlSet().replaceAll("=#\\{[^}]*}", ""));
        assertEquals("ipv6_dual",
                NodeIpv6Switch.nodeDualUpdate(7L, 1).getSqlSet().replaceAll("=#\\{[^}]*}", ""));
    }
}
