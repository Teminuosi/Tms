package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * sing-box 配置生成 + 下发(合体面板 · 协议侧)。
 * 与 GostUtil 对称:GostUtil 管转发/限速的下发,SingboxUtil 管协议的下发。
 * 节点端 x/socket/singbox.go 收到 SetSingboxConfig 后写文件 + systemd 起 sing-box。
 * 约束:入站一律 listen 127.0.0.1,公网口交给 gost 转发并限速。
 */
public class SingboxUtil {

    /**
     * 生成某节点的完整 sing-box 配置(汇总该节点上所有入站),并通过 WebSocket 下发。
     *
     * @param nodeId          节点ID
     * @param inbounds        该节点上的入站列表
     * @param usersByInbound  入站ID -> 该入站下的用户凭证列表
     * @param mirror          国内 GitHub 镜像前缀(如 https://ghfast.top/),可为 null
     */
    public static GostDto SetSingboxConfig(Long nodeId, List<Inbound> inbounds,
                                           Map<Long, List<InboundUser>> usersByInbound,
                                           Map<Long, String> landingOutbounds, String mirror) {
        JSONObject payload = new JSONObject();
        payload.put("config", buildNodeConfig(inbounds, usersByInbound, landingOutbounds));
        if (mirror != null && !mirror.isEmpty()) {
            payload.put("mirror", mirror);
        }
        return WebSocketServer.send_msg(nodeId, payload, "SetSingboxConfig");
    }

    /** 关掉某节点的 sing-box */
    public static GostDto DeleteSingbox(Long nodeId) {
        return WebSocketServer.send_msg(nodeId, new JSONObject(), "DeleteSingbox");
    }

    /**
     * 中转:让【前置机节点】用给定的 socks 落地拨号测一下,回显出口 IP + 延迟。
     * outbound = LandingUtil 解出的 sing-box 出站(socks:server/server_port/username/password)。
     * 返回 GostDto.data = {ok, exitIp, latencyMs}(节点端 handleTestOutbound)。
     */
    public static GostDto TestOutbound(Long nodeId, JSONObject outbound) {
        JSONObject payload = new JSONObject();
        payload.put("type", outbound.getString("type"));
        payload.put("server", outbound.getString("server"));
        payload.put("port", outbound.getInteger("server_port"));
        if (outbound.containsKey("username")) {
            payload.put("username", outbound.getString("username"));
        }
        if (outbound.containsKey("password")) {
            payload.put("password", outbound.getString("password"));
        }
        return WebSocketServer.send_msg(nodeId, payload, "TestOutbound");
    }

    /**
     * 让节点用 sing-box 生成 Reality 密钥对。
     * 返回的 GostDto.data = {"privateKey": "...", "publicKey": "..."}(节点端 handleGenerateRealityKeypair)。
     */
    public static GostDto GenerateRealityKeypair(Long nodeId, String mirror) {
        JSONObject payload = new JSONObject();
        if (mirror != null && !mirror.isEmpty()) {
            payload.put("mirror", mirror);
        }
        return WebSocketServer.send_msg(nodeId, payload, "GenerateRealityKeypair");
    }

    /**
     * 生成 VLESS-Reality 客户端分享链接。
     * 地址填的是该用户的【gost 公网端口】(被限速/计流量/到期),不是 sing-box 本机口。
     */
    public static String buildVlessRealityLink(String uuid, String serverIp, Integer port,
                                               String sni, String publicKey, String shortId, String remark) {
        String frag;
        try {
            frag = java.net.URLEncoder.encode(remark == null ? "" : remark, "UTF-8");
        } catch (Exception e) {
            frag = "";
        }
        return "vless://" + uuid + "@" + uriHost(serverIp) + ":" + port
                + "?encryption=none&flow=xtls-rprx-vision&security=reality"
                + "&sni=" + (sni == null ? "" : sni)
                + "&fp=chrome"
                + "&pbk=" + (publicKey == null ? "" : publicKey)
                + "&sid=" + (shortId == null ? "" : shortId)
                + "&type=tcp#" + frag;
    }

    /**
     * 汇总一个节点的完整 sing-box 配置(log + 所有入站 + direct 出站 + 中转的落地出站/路由)。
     * landingOutbounds: 落地ID -> 该落地的 sing-box outbound JSON(不含 tag);入站带 landing_id 时按此路由出网。
     */
    public static JSONObject buildNodeConfig(List<Inbound> inbounds,
                                             Map<Long, List<InboundUser>> usersByInbound,
                                             Map<Long, String> landingOutbounds) {
        JSONObject log = new JSONObject();
        log.put("level", "warn");

        JSONArray inboundArr = new JSONArray();
        JSONArray outbounds = new JSONArray();
        JSONArray routeRules = new JSONArray();
        java.util.Set<Long> addedLandings = new java.util.HashSet<>();

        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.add(direct);

        if (inbounds != null) {
            for (Inbound in : inbounds) {
                if (in.getStatus() != null && in.getStatus() == 0) {
                    continue; // 停用的入站不下发
                }
                List<InboundUser> users = usersByInbound != null ? usersByInbound.get(in.getId()) : null;
                JSONObject inboundJson = buildInbound(in, users);
                if (inboundJson == null) {
                    continue;
                }
                inboundArr.add(inboundJson);

                // 中转:该入站有落地 → 加落地出站(去重)+ 路由(该入站 tag → 落地出站)
                Long lid = in.getLandingId();
                String obJson = (lid != null && landingOutbounds != null) ? landingOutbounds.get(lid) : null;
                if (lid != null && obJson != null && !obJson.isEmpty()) {
                    String tag = "landing-" + lid;
                    if (addedLandings.add(lid)) {
                        JSONObject ob = JSON.parseObject(obJson);
                        ob.put("tag", tag);
                        outbounds.add(ob);
                    }
                    JSONObject rule = new JSONObject();
                    JSONArray inTags = new JSONArray();
                    inTags.add(in.getTag());
                    rule.put("inbound", inTags);
                    rule.put("outbound", tag);
                    routeRules.add(rule);
                }
            }
        }

        JSONObject config = new JSONObject();
        config.put("log", log);
        config.put("inbounds", inboundArr);
        config.put("outbounds", outbounds);
        // 有中转入站才写 route(纯直连节点保持原样,不影响协议管理)
        if (!routeRules.isEmpty()) {
            JSONObject route = new JSONObject();
            route.put("rules", routeRules);
            route.put("final", "direct");
            config.put("route", route);
        }
        return config;
    }

    /** 按协议生成单个 sing-box 入站 */
    public static JSONObject buildInbound(Inbound in, List<InboundUser> users) {
        String protocol = in.getProtocol() == null ? "" : in.getProtocol().toLowerCase();
        switch (protocol) {
            case "vless":
                return buildVlessReality(in, users);
            case "trojan":
                return buildTrojanReality(in, users);
            case "vmess":
                return buildVmess(in, users);
            case "shadowsocks":
                return buildShadowsocks(in);
            case "hysteria2":
                return buildHysteria2(in, users);
            case "tuic":
                return buildTuic(in, users);
            case "anytls":
                return buildAnyTls(in, users);
            default:
                return null;
        }
    }

    /**
     * Shadowsocks-2022 入站(无 TLS、不依赖客户端指纹,绕开 reality 的后量子坑)。
     * 单密码,用户靠各自的 gost 公网口区分/限速;method+password 存在 inbound.configJson。
     */
    private static JSONObject buildShadowsocks(Inbound in) {
        JSONObject cfg = parseConfig(in.getConfigJson());
        JSONObject inbound = new JSONObject();
        inbound.put("type", "shadowsocks");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        inbound.put("method", cfg.getString("method"));
        inbound.put("password", cfg.getString("password"));
        return inbound;
    }

    /** 生成 Shadowsocks 客户端分享链接(SIP002:ss://base64url(method:password)@ip:port#remark)。地址=gost 公网口 */
    public static String buildShadowsocksLink(String serverIp, Integer port, String method, String password, String remark) {
        String userinfo = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((method + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "ss://" + userinfo + "@" + uriHost(serverIp) + ":" + port + "#" + urlEncode(remark);
    }

    private static JSONObject parseConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(configJson);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * 分享链接里的主机名:IPv6 字面量要包方括号(vless://uuid@[2001:db8::1]:443),
     * 否则客户端把最后一段冒号当端口,解析成错的地址。IPv4 / 域名原样返回 —— 老链接一个字都不变。
     * 粉丝反馈「v6 机器 TMS 出的节点不通、S-UI 的能通」(2026-10-28)。
     */
    public static String uriHost(String host) {
        if (host == null) return null;
        String h = host.trim();
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) return "[" + h + "]";
        return host;
    }

    /** 节点地址是不是 IPv6 字面量(含冒号、或已带方括号)。域名 / IPv4 都是 false */
    public static boolean isIpv6Literal(String host) {
        if (host == null) return false;
        String h = host.trim();
        return h.indexOf(':') >= 0 || h.startsWith("[");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** VLESS + Reality 入站(无域名);listen 一律 127.0.0.1,公网口交给 gost 限速 */
    private static JSONObject buildVlessReality(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "vless");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("flow", "xtls-rprx-vision");
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildRealityTls(in));
        return inbound;
    }

    /** Trojan + Reality 入站(无域名);和 VLESS-Reality 同一套 reality,凭证是 password */
    private static JSONObject buildTrojanReality(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "trojan");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildRealityTls(in));
        return inbound;
    }

    /** VMess 入站(TCP,无 TLS,无域名);凭证是 uuid */
    private static JSONObject buildVmess(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "vmess");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        // 传输层:configJson 里存了 net=ws 才写 transport,否则保持原来的裸 TCP。
        // 【为什么 gost 那一跳不用管】它是裸字节转发(handler=relay),
        // 而 ws 握手本质就是 HTTP over TCP,原样透得过去。
        JSONObject vcfg = parseConfig(in.getConfigJson());
        if ("ws".equalsIgnoreCase(vcfg.getString("net"))) {
            JSONObject tr = new JSONObject();
            tr.put("type", "ws");
            String p = vcfg.getString("path");
            if (p != null && !p.isEmpty()) tr.put("path", p);
            String h = vcfg.getString("host");
            if (h != null && !h.isEmpty()) {
                JSONObject headers = new JSONObject();
                headers.put("Host", h);
                tr.put("headers", headers);
            }
            inbound.put("transport", tr);
        }

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("alterId", 0);
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        return inbound;
    }

    /** Reality over TLS 配置块(VLESS / Trojan 共用) */
    private static JSONObject buildRealityTls(Inbound in) {
        JSONObject handshake = new JSONObject();
        handshake.put("server", in.getDest());
        handshake.put("server_port", 443);

        JSONArray shortIds = new JSONArray();
        shortIds.add(in.getShortId() == null ? "" : in.getShortId());

        JSONObject reality = new JSONObject();
        reality.put("enabled", true);
        reality.put("handshake", handshake);
        reality.put("private_key", in.getPrivateKey());
        reality.put("short_id", shortIds);

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", in.getSni());
        tls.put("reality", reality);
        return tls;
    }

    /** VMess 客户端链接(vmess://base64(json)) */
    public static String buildVmessLink(String uuid, String serverIp, Integer port, String remark) {
        return buildVmessLink(uuid, serverIp, port, remark, null, null);
    }

    /**
     * VMess 客户端链接。net=ws 时把 path/host 填进去 ——
     * 这几个字段本来就在下面的 JSON 里当空占位,填上即可,不用改结构。
     */
    public static String buildVmessLink(String uuid, String serverIp, Integer port, String remark,
                                        String net, String path) {
        return buildVmessLink(uuid, serverIp, port, remark, net, path, null);
    }

    public static String buildVmessLink(String uuid, String serverIp, Integer port, String remark,
                                        String net, String path, String host) {
        JSONObject v = new JSONObject();
        v.put("v", "2");
        v.put("ps", remark == null ? "" : remark);
        v.put("add", serverIp);
        v.put("port", String.valueOf(port));
        v.put("id", uuid);
        v.put("aid", "0");
        v.put("scy", "auto");
        boolean ws = "ws".equalsIgnoreCase(net);
        v.put("net", ws ? "ws" : "tcp");
        v.put("type", "none");
        v.put("host", ws && host != null ? host : "");
        v.put("path", ws && path != null ? path : "");
        v.put("tls", "");
        v.put("sni", "");
        String b64 = Base64.getEncoder().encodeToString(v.toJSONString().getBytes(StandardCharsets.UTF_8));
        return "vmess://" + b64;
    }

    /** Trojan + Reality 客户端链接 */
    public static String buildTrojanRealityLink(String password, String serverIp, Integer port,
                                                String sni, String publicKey, String shortId, String remark) {
        return "trojan://" + password + "@" + uriHost(serverIp) + ":" + port
                + "?security=reality"
                + "&sni=" + (sni == null ? "" : sni)
                + "&fp=chrome"
                + "&pbk=" + (publicKey == null ? "" : publicKey)
                + "&sid=" + (shortId == null ? "" : shortId)
                + "&type=tcp#" + urlEncode(remark);
    }

    // ---- 自签证书类协议(Hysteria2 / TUIC / AnyTLS,无域名,客户端 insecure)----
    // 证书由节点端自动生成,固定路径;面板配置直接引用。
    private static final String SELF_CERT = "/etc/gost/certs/self.crt";
    private static final String SELF_KEY = "/etc/gost/certs/self.key";

    /** 自签 TLS 配置块 */
    private static JSONObject buildSelfTls(Inbound in) {
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", (in.getSni() == null || in.getSni().isEmpty()) ? "www.bing.com" : in.getSni());
        tls.put("certificate_path", SELF_CERT);
        tls.put("key_path", SELF_KEY);
        return tls;
    }

    /** Hysteria2 入站(QUIC/UDP,自签证书);凭证是 password */
    private static JSONObject buildHysteria2(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "hysteria2");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildSelfTls(in));
        return inbound;
    }

    /** TUIC 入站(QUIC/UDP,自签证书,alpn h3);凭证是 uuid + password */
    private static JSONObject buildTuic(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "tuic");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        inbound.put("congestion_control", "bbr");
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        JSONObject tls = buildSelfTls(in);
        JSONArray alpn = new JSONArray();
        alpn.add("h3");
        tls.put("alpn", alpn);
        inbound.put("tls", tls);
        return inbound;
    }

    /** AnyTLS 入站(TCP/TLS,自签证书);凭证是 password */
    private static JSONObject buildAnyTls(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "anytls");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildSelfTls(in));
        return inbound;
    }

    /** Hysteria2 客户端链接 */
    public static String buildHysteria2Link(String password, String serverIp, Integer port, String sni, String remark) {
        return "hysteria2://" + password + "@" + uriHost(serverIp) + ":" + port
                + "?sni=" + (sni == null ? "" : sni) + "&insecure=1#" + urlEncode(remark);
    }

    /** TUIC 客户端链接 */
    public static String buildTuicLink(String uuid, String password, String serverIp, Integer port, String sni, String remark) {
        return "tuic://" + uuid + ":" + password + "@" + uriHost(serverIp) + ":" + port
                + "?congestion_control=bbr&alpn=h3&sni=" + (sni == null ? "" : sni)
                + "&allow_insecure=1#" + urlEncode(remark);
    }

    /** AnyTLS 客户端链接 */
    public static String buildAnyTlsLink(String password, String serverIp, Integer port, String sni, String remark) {
        return "anytls://" + password + "@" + uriHost(serverIp) + ":" + port
                + "?insecure=1&sni=" + (sni == null ? "" : sni) + "#" + urlEncode(remark);
    }
}
