package com.admin.service.impl;

import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 自动隧道(inbound-tunnel-node{id})新建 / 空隧道的监听地址:
 * 节点开了 IPv6(ipv6_dual=1)时和 IPv6 机器一样用 [::],否则保持原来的 0.0.0.0。
 * 以前只认「节点地址本身是 IPv6」:先开 IPv6(那时还没有自动隧道)再分配协议,新隧道是 0.0.0.0,
 * 新协议只听 IPv4,卡片却显示「已开启 IPv6」。
 */
class InboundTunnelDualStackTest {

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Tunnel.class);
    }

    static final class Env {
        final InboundServiceImpl svc = new InboundServiceImpl();
        final NodeMapper nodeMapper = mock(NodeMapper.class);
        final TunnelMapper tunnelMapper = mock(TunnelMapper.class);
        final TunnelService tunnelService = mock(TunnelService.class);
        final ForwardService forwardService = mock(ForwardService.class);

        Env(String serverIp, Integer dual) {
            Node n = new Node();
            n.setId(3L);
            n.setServerIp(serverIp);
            n.setIpv6Dual(dual);
            when(nodeMapper.selectById(3L)).thenReturn(n);
            when(tunnelService.createTunnel(any())).thenReturn(R.ok());
            ReflectionTestUtils.setField(svc, "nodeMapper", nodeMapper);
            ReflectionTestUtils.setField(svc, "tunnelMapper", tunnelMapper);
            ReflectionTestUtils.setField(svc, "tunnelService", tunnelService);
            ReflectionTestUtils.setField(svc, "forwardService", forwardService);
        }

        Tunnel ensure() throws Exception {
            Method m = InboundServiceImpl.class.getDeclaredMethod("ensurePortForwardTunnel", Long.class);
            m.setAccessible(true);
            return (Tunnel) m.invoke(svc, 3L);
        }

        TunnelDto created() {
            ArgumentCaptor<TunnelDto> c = ArgumentCaptor.forClass(TunnelDto.class);
            verify(tunnelService).createTunnel(c.capture());
            return c.getValue();
        }
    }

    static Tunnel tunnel(String tcp, String udp) {
        Tunnel t = new Tunnel();
        t.setId(9L);
        t.setName("inbound-tunnel-node3");
        t.setTcpListenAddr(tcp);
        t.setUdpListenAddr(udp);
        return t;
    }

    @Test
    void newTunnelOnDualEnabledV4NodeListensOnWildcard() throws Exception {
        Env e = new Env("203.0.113.5", 1);
        when(e.tunnelMapper.selectOne(any())).thenReturn(null, tunnel("[::]", "[::]"));
        e.ensure();
        TunnelDto d = e.created();
        assertEquals("[::]", d.getTcpListenAddr());
        assertEquals("[::]", d.getUdpListenAddr());
    }

    /** 没开 IPv6(或关掉以后)新建的还是原来的 0.0.0.0 */
    @Test
    void newTunnelWithoutDualKeepsOldDefault() throws Exception {
        for (Integer dual : new Integer[]{null, 0}) {
            Env e = new Env("203.0.113.5", dual);
            when(e.tunnelMapper.selectOne(any())).thenReturn(null, tunnel("0.0.0.0", "0.0.0.0"));
            e.ensure();
            TunnelDto d = e.created();
            assertEquals("0.0.0.0", d.getTcpListenAddr(), "dual=" + dual);
            assertEquals("0.0.0.0", d.getUdpListenAddr(), "dual=" + dual);
        }
    }

    /** IPv6 机器原来的行为不变 */
    @Test
    void newTunnelOnV6NodeStillWildcard() throws Exception {
        Env e = new Env("2001:db8::5", null);
        when(e.tunnelMapper.selectOne(any())).thenReturn(null, tunnel("[::]", "[::]"));
        e.ensure();
        assertEquals("[::]", e.created().getTcpListenAddr());
    }

    /** 已有但一条转发都没有的自动隧道:开了 IPv6 就升级成 [::](只改库) */
    @SuppressWarnings("unchecked")
    @Test
    void emptyExistingTunnelUpgradedWhenDual() throws Exception {
        Env e = new Env("203.0.113.5", 1);
        when(e.tunnelMapper.selectOne(any())).thenReturn(tunnel("0.0.0.0", "0.0.0.0"));
        when(e.forwardService.count(any(Wrapper.class))).thenReturn(0);
        when(e.tunnelMapper.selectById(9L)).thenReturn(tunnel("[::]", "[::]"));
        Tunnel t = e.ensure();
        verify(e.tunnelMapper).update(isNull(), any(Wrapper.class));
        assertEquals("[::]", t.getTcpListenAddr());
        verify(e.tunnelService, never()).createTunnel(any());
    }

    /** 有转发的已有隧道一律不动(换监听要重推,不安全);没开 IPv6 的空隧道也不动 */
    @SuppressWarnings("unchecked")
    @Test
    void existingTunnelUntouchedWhenHasForwardsOrNotDual() throws Exception {
        Env e = new Env("203.0.113.5", 1);
        when(e.tunnelMapper.selectOne(any())).thenReturn(tunnel("0.0.0.0", "0.0.0.0"));
        when(e.forwardService.count(any(Wrapper.class))).thenReturn(2);
        assertEquals("0.0.0.0", e.ensure().getTcpListenAddr());
        verify(e.tunnelMapper, never()).update(any(), any(Wrapper.class));

        Env off = new Env("203.0.113.5", 0);
        when(off.tunnelMapper.selectOne(any())).thenReturn(tunnel("0.0.0.0", "0.0.0.0"));
        when(off.forwardService.count(any(Wrapper.class))).thenReturn(0);
        assertEquals("0.0.0.0", off.ensure().getTcpListenAddr());
        verify(off.tunnelMapper, never()).update(any(), any(Wrapper.class));
    }
}
