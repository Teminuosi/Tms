package com.admin.common.utils;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clash / Mihomo 订阅(YAML)。
 *
 * 和 SingboxUtil 出的那套 base64 链接是两种东西:v2rayN、小火箭吃链接列表,
 * Mihomo 系(Clash Verge Rev、ClashMeta、Nyanpasu)吃 YAML,两边不通用 ——
 * 把链接订阅贴进 Clash Verge,结果是空的。
 *
 * 每个协议的字段名都要和 SingboxUtil 里对应的 build*Link 保持一致,那边改了
 * 这边就得跟着改,不然两种订阅会指向不同的配置。
 */
public class ClashUtil {

    /** 三个自签证书协议(Hysteria2 / TUIC / AnyTLS)都得跳过证书校验,和链接里的 insecure=1 对应 */
    private static final boolean SKIP_CERT = true;

    /**
     * 一个节点 → 一条 Clash proxy。
     * 参数刻意和 SingboxUtil.build*Link 对齐,免得两边各写一套导致对不上。
     * 返回 null 表示这个协议 Mihomo 不认,调用方跳过即可。
     */
    public static Map<String, Object> toProxy(String protocol, String name, String server, Integer port,
                                              String uuid, String password, String sni,
                                              String publicKey, String shortId, String ssMethod) {
        return toProxy(protocol, name, server, port, uuid, password, sni, publicKey, shortId, ssMethod, null, null);
    }

    /**
     * wsPath / wsHost:vmess 走 WebSocket 时才有值。
     *
     * 【为什么这儿非改不可】Clash 订阅是这个类单独生成的,跟 buildVmessLink 是两条路。
     * 只改那边的话,同一个 ws 节点在 v2rayN 里是好的、在 Clash / Mihomo 里
     * 因为 network 还写着 tcp 而连不上 —— 而且两边都"有节点、看着正常",
     * 用户只会觉得"Clash 不好使",很难想到是订阅生成的问题。
     */
    public static Map<String, Object> toProxy(String protocol, String name, String server, Integer port,
                                              String uuid, String password, String sni,
                                              String publicKey, String shortId, String ssMethod,
                                              String wsPath, String wsHost) {
        if (protocol == null || server == null || port == null) {
            return null;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("server", server);
        p.put("port", port);

        switch (protocol) {
            case "vless": {
                p.put("type", "vless");
                p.put("uuid", uuid);
                p.put("network", "tcp");
                p.put("udp", true);
                p.put("tls", true);
                // flow 和链接里的 flow=xtls-rprx-vision 对应
                p.put("flow", "xtls-rprx-vision");
                p.put("servername", nz(sni));
                p.put("client-fingerprint", "chrome");
                p.put("reality-opts", realityOpts(publicKey, shortId));
                return p;
            }
            case "trojan": {
                p.put("type", "trojan");
                p.put("password", password);
                p.put("udp", true);
                // Trojan 这边 SNI 的字段名是 sni,不是 vless 的 servername
                p.put("sni", nz(sni));
                p.put("client-fingerprint", "chrome");
                p.put("reality-opts", realityOpts(publicKey, shortId));
                return p;
            }
            case "vmess": {
                p.put("type", "vmess");
                p.put("uuid", uuid);
                // 和 buildVmessLink 里的 aid=0 / scy=auto / net=tcp / tls 空 一致:
                // 这条是裸的,没有 TLS,所以不能写 tls: true
                p.put("alterId", 0);
                p.put("cipher", "auto");
                if (wsPath != null && !wsPath.isEmpty()) {
                    p.put("network", "ws");
                    Map<String, Object> ws = new LinkedHashMap<>();
                    ws.put("path", wsPath);
                    if (wsHost != null && !wsHost.isEmpty()) {
                        Map<String, Object> headers = new LinkedHashMap<>();
                        headers.put("Host", wsHost);
                        ws.put("headers", headers);
                    }
                    p.put("ws-opts", ws);
                } else {
                    p.put("network", "tcp");
                }
                p.put("udp", true);
                return p;
            }
            case "shadowsocks": {
                p.put("type", "ss");
                p.put("cipher", nz(ssMethod));
                p.put("password", password);
                p.put("udp", true);
                return p;
            }
            case "hysteria2": {
                p.put("type", "hysteria2");
                p.put("password", password);
                p.put("sni", nz(sni));
                p.put("skip-cert-verify", SKIP_CERT);
                return p;
            }
            case "tuic": {
                p.put("type", "tuic");
                p.put("uuid", uuid);
                p.put("password", password);
                p.put("sni", nz(sni));
                p.put("alpn", new ArrayList<>(Arrays.asList("h3")));
                p.put("congestion-controller", "bbr");
                p.put("udp-relay-mode", "native");
                p.put("skip-cert-verify", SKIP_CERT);
                return p;
            }
            case "anytls": {
                // Mihomo 从 1.19 起才有 anytls。老内核会在解析时报错并整份配置失败,
                // 所以宁可不下发 —— 少一个节点好过整条订阅打不开。
                return null;
            }
            default:
                return null;
        }
    }

    private static Map<String, Object> realityOpts(String publicKey, String shortId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("public-key", nz(publicKey));
        r.put("short-id", nz(shortId));
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * proxies 列表 → 完整的 Clash 配置。
     *
     * 带上分组和分流规则,车友导进去就能用:国内直连、国外走代理。
     * 只给 proxies 不给 rules 的话,Mihomo 不知道什么流量该走哪儿,
     * 对不懂配置的人等于没用。
     */
    public static String buildConfig(List<Map<String, Object>> proxies) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> p : proxies) {
            names.add(String.valueOf(p.get("name")));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mixed-port", 7890);
        root.put("allow-lan", false);
        root.put("mode", "rule");
        root.put("log-level", "info");
        root.put("external-controller", "127.0.0.1:9090");

        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("enable", true);
        dns.put("ipv6", false);
        dns.put("enhanced-mode", "fake-ip");
        dns.put("fake-ip-range", "198.18.0.1/16");
        dns.put("nameserver", new ArrayList<>(Arrays.asList("223.5.5.5", "119.29.29.29")));
        dns.put("fallback", new ArrayList<>(Arrays.asList("8.8.8.8", "1.1.1.1")));
        root.put("dns", dns);

        root.put("proxies", proxies);

        List<Map<String, Object>> groups = new ArrayList<>();

        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("name", "🚀 节点选择");
        manual.put("type", "select");
        List<String> manualList = new ArrayList<>();
        manualList.add("♻️ 自动选择");
        manualList.addAll(names);
        manual.put("proxies", manualList);
        groups.add(manual);

        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("name", "♻️ 自动选择");
        auto.put("type", "url-test");
        auto.put("proxies", new ArrayList<>(names));
        auto.put("url", "http://www.gstatic.com/generate_204");
        auto.put("interval", 300);
        auto.put("tolerance", 50);
        groups.add(auto);

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("name", "🐟 漏网之鱼");
        fallback.put("type", "select");
        fallback.put("proxies", new ArrayList<>(Arrays.asList("🚀 节点选择", "DIRECT")));
        groups.add(fallback);

        root.put("proxy-groups", groups);

        // 规则从上往下匹配,第一条命中就停。GEOIP,CN 必须排在 MATCH 前面,
        // 否则所有流量都会被最后那条兜底规则吃掉。
        root.put("rules", new ArrayList<>(Arrays.asList(
                "DOMAIN-SUFFIX,local,DIRECT",
                "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
                "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
                "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
                "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
                "GEOIP,CN,DIRECT",
                "MATCH,🐟 漏网之鱼"
        )));

        DumperOptions opts = new DumperOptions();
        // 块状风格:一行一个字段。默认的流式风格会把整个 proxies 挤成一行 JSON 样子,
        // 车友想手工看一眼改点东西根本没法读。
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        // 不折行:节点名带中文和箭头,snakeyaml 默认 80 列就换行,
        // 折出来的 YAML 有些客户端解析会出错。
        opts.setWidth(4096);
        opts.setAllowUnicode(true);
        return new Yaml(opts).dump(root);
    }

    /**
     * 保证节点名唯一。
     *
     * Clash 里 name 是主键,重名的节点会被后来的覆盖 —— 车友看到的节点数
     * 比实际少,而且完全没有提示。两台机器重名(或者两个落地同名)时就会撞上。
     */
    public static String uniqueName(String name, Set<String> used) {
        String base = (name == null || name.trim().isEmpty()) ? "node" : name.trim();
        String candidate = base;
        int i = 2;
        while (used.contains(candidate)) {
            candidate = base + " " + i;
            i++;
        }
        used.add(candidate);
        return candidate;
    }

    /**
     * 预先占上分组名和 Mihomo 内置策略名(SubNames.CLASH_RESERVED):节点叫「🚀 节点选择」「DIRECT」时
     * Mihomo 整份配置加载失败。占上之后撞名的节点变成「DIRECT 2」这种,其余名字不受影响。
     */
    public static Set<String> newNameSet() {
        return new HashSet<>(SubNames.CLASH_RESERVED);
    }

    /** 给调用方留的空 map,避免各处 new 一遍 */
    public static Map<String, Object> emptyMap() {
        return new HashMap<>();
    }
}
