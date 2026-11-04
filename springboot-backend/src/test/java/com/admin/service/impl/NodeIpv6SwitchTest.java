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
        /** 非 null:节点对整条命令只回这个错误、不带 results(老节点程序不认命令时就是这样) */
        String wholeErr;
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
            if (wholeErr != null) {
                r.setMsg(wholeErr);
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
        return fwd(id, uid, port, 1);
    }

    static Forward fwd(long id, int uid, int port, Integer status) {
        Forward f = new Forward();
        f.setId(id);
        f.setUserId(uid);
        f.setInPort(port);
        f.setTunnelId(3);
        f.setStatus(status);
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

    /** 原名和 _0 节点上都没有(老版本留下的 status=-1 转发):不用切,跳过并列出来,不拖累整台 */
    @Test
    void notFoundEverywhereIsSkippedAndListed() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(50, 1, 20050, -1));
        f.userTunnel.put(1, 4);
        f.present = new HashSet<>(Arrays.asList("5_2_0_tcp", "5_2_0_udp"));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertEquals(0, r.getCode(), r.getMsg());
        assertEquals(2, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=:20001", "5_2_0_udp=:20001", "50_1_4_tcp=:20050", "50_1_4_udp=:20050"),
                items(f.sent.get(0)));
        assertEquals(Arrays.asList("50_1_0_tcp=:20050", "50_1_0_udp=:20050"), items(f.sent.get(1)));
        assertArrayEquals(new Object[]{3L, "[::]", 7L, 1}, f.commits.get(0));
        JSONObject d = (JSONObject) r.getData();
        List<String> skipped = d.getJSONArray("skipped").toJavaList(String.class);
        assertEquals(1, skipped.size(), skipped.toString());
        assertTrue(skipped.get(0).contains("#50") && skipped.get(0).contains("20050"), skipped.get(0));
    }

    @Test
    void allNotFoundStillCommits() {
        Fake f = online();
        f.forwards.add(fwd(50, 1, 20050, -1));
        f.forwards.add(fwd(51, 1, 20051, null));
        f.forwards.add(fwd(52, 1, 20052, 0));
        f.present = Collections.emptySet();
        R r = new NodeIpv6Switch(f).disable(7L);
        assertEquals(0, r.getCode(), r.getMsg());
        assertArrayEquals(new Object[]{3L, "0.0.0.0", 7L, 0}, f.commits.get(0));
        assertEquals(3, ((JSONObject) r.getData()).getJSONArray("skipped").size());
    }

    /**
     * 库里是运行中(status=1)但原名和 _0 节点上都找不到:不是老版本留下的坏转发,是名字对不上,
     * 照样算失败、整体回滚(已切的切回、库不动),报错点名是哪条、为什么
     */
    @Test
    void runningForwardNotFoundAnywhereFailsAndRollsBack() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(60, 1, 20060));          // status=1
        f.forwards.add(fwd(50, 1, 20050, -1));      // 老版本留下的,照旧跳过
        f.userTunnel.put(1, 4);
        f.present = new HashSet<>(Arrays.asList("5_2_0_tcp", "5_2_0_udp"));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(f.commits.isEmpty());
        assertTrue(r.getMsg().contains("#60") && r.getMsg().contains("20060"), r.getMsg());
        assertTrue(r.getMsg().contains("60_1_4") && r.getMsg().contains("60_1_0"), r.getMsg());
        assertTrue(r.getMsg().contains("名字对不上"), r.getMsg());
        // 节点重装过也是这个样子:告诉车主重新保存这条转发(更新时 not found 会按原名重建)
        assertTrue(r.getMsg().contains("节点重装过") && r.getMsg().contains("重新保存一下这条转发"), r.getMsg());
        // 界面(node.tsx / 客户端 parseIpv6Msg)按 ';' 拆成一行一条:同一条失败里不能有 ';',否则提示被拆成孤零零的另一行
        String tail = r.getMsg().substring(r.getMsg().indexOf("失败的:") + 4);
        String entry60 = null;
        for (String p : tail.split(";")) {
            if (p.contains("#60")) {
                entry60 = p;
            }
        }
        assertNotNull(entry60, r.getMsg());
        assertTrue(entry60.contains("重新保存一下这条转发"), entry60);
        assertFalse(r.getMsg().contains("#50"), r.getMsg());
        assertTrue(r.getMsg().contains("已把切过去的 2 条切回原地址"), r.getMsg());
        assertEquals(3, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=0.0.0.0:20001", "5_2_0_udp=0.0.0.0:20001"), items(f.sent.get(2)));
    }

    /** 有不存在的也有真失败:真失败仍整体回滚,不存在的那条不当失败、也不发切回 */
    @Test
    void notFoundPlusRealFailureStillRollsBack() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(6, 3, 20002));
        f.forwards.add(fwd(50, 1, 20050, -1));
        f.present = new HashSet<>(Arrays.asList("5_2_0_tcp", "5_2_0_udp", "6_3_0_tcp", "6_3_0_udp"));
        f.nodeErr = n -> n.equals("6_3_0_tcp") ? "listen tcp6 [::]:20002: bind: address already in use" : null;
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertTrue(r.getMsg().contains("6_3_0_tcp"), r.getMsg());
        assertFalse(r.getMsg().contains("50_1"), r.getMsg());
        assertEquals(2, f.sent.size());
        assertEquals(Arrays.asList("5_2_0_tcp=0.0.0.0:20001", "5_2_0_udp=0.0.0.0:20001", "6_3_0_udp=0.0.0.0:20002"),
                items(f.sent.get(1)));
        assertTrue(f.commits.isEmpty());
    }

    /** 面板还没收到这台节点的上报(刚重启 / 节点刚连上):不当成「太旧」 */
    @Test
    void capsNotReportedYetIsNotCalledOld() {
        Fake f = online();
        f.caps = null;
        f.forwards.add(fwd(5, 2, 20001));
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertFalse(r.getMsg().contains("太旧"), r.getMsg());
        assertTrue(r.getMsg().contains("上报"), r.getMsg());
        assertTrue(f.sent.isEmpty());
        assertTrue(f.commits.isEmpty());
    }

    /** 节点不认命令(老节点程序):什么都没切,别说「已切回」,也不再发切回 */
    @Test
    void unknownCommandSaysNothingSwitched() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.wholeErr = "未知命令类型: SwitchServiceAddr";
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertFalse(r.getMsg().contains("切回"), r.getMsg());
        assertTrue(r.getMsg().contains("没有改动"), r.getMsg());
        assertTrue(r.getMsg().contains("太旧"), r.getMsg());
        assertEquals(1, f.sent.size());
        assertTrue(f.commits.isEmpty());
    }

    /** 没拿到结果、切回也没拿到结果:不能说「已切回」 */
    @Test
    void noResultEitherWayDoesNotClaimRolledBack() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.wholeErr = "等待响应超时";
        R r = new NodeIpv6Switch(f).enable(7L);
        assertNotEquals(0, r.getCode());
        assertFalse(r.getMsg().contains("已把"), r.getMsg());
        assertTrue(r.getMsg().contains("不确定"), r.getMsg());
        assertTrue(f.commits.isEmpty());
    }

    /** 切过去的切回成功:照旧说已切回 N 条(N = 真切过去的条数) */
    @Test
    void rollbackMessageCountsOnlyConfirmedSwitched() {
        Fake f = online();
        f.forwards.add(fwd(5, 2, 20001));
        f.forwards.add(fwd(6, 3, 20002));
        f.nodeErr = n -> n.equals("6_3_0_udp") ? "boom" : null;
        R r = new NodeIpv6Switch(f).enable(7L);
        assertTrue(r.getMsg().contains("已把切过去的 3 条切回原地址"), r.getMsg());
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
