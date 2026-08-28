package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.InboundDto;
import com.admin.common.dto.InboundUserDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.common.utils.ClashUtil;
import com.admin.common.utils.SingboxUtil;
import com.admin.entity.Forward;
import com.admin.entity.Inbound;
import com.admin.entity.InboundLine;
import com.admin.entity.InboundUser;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.InboundUserMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.InboundService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协议入站服务实现(合体面板:协议 + 限速)。
 * 架构:客户端 → gost(公网口:限速/流量/到期) → 127.0.0.1:sing-box入站(协议) → 外网。
 *
 * @author QAQ
 * @since 2026-07-19
 */
@Service
public class InboundServiceImpl extends ServiceImpl<InboundMapper, Inbound> implements InboundService {

    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int SINGBOX_LISTEN_BASE = 40000;

    @Autowired
    private InboundUserMapper inboundUserMapper;
    @Autowired
    private NodeMapper nodeMapper;
    @Autowired
    private TunnelMapper tunnelMapper;
    @Autowired
    private TunnelService tunnelService;
    @Autowired
    private ForwardService forwardService;
    @Autowired
    private ForwardMapper forwardMapper;
    @Autowired
    private SpeedLimitService speedLimitService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private com.admin.mapper.LandingMapper landingMapper;
    @Autowired
    private com.admin.mapper.InboundLineMapper inboundLineMapper;
    @Autowired
    private com.admin.service.LandingService landingService;

    @Override
    public R createInbound(InboundDto dto) {
        R built = buildAndSaveInbound(dto);
        if (built.getCode() != 0) {
            return built;
        }
        Inbound created = (Inbound) built.getData();
        R push = pushNodeSingbox(created.getNodeId());
        if (push.getCode() != 0) {
            this.removeById(created.getId());
            return push;
        }
        return R.ok(created);
    }

    /**
     * 组装并入库一个入站,但【不推 sing-box 配置】。
     * 一键添加时批量建、最后统一推一次,避免每建一个就重启 sing-box、反复重启触发 systemd 启动限流。
     */
    private R buildAndSaveInbound(InboundDto dto) {
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) {
            return R.err("节点不存在");
        }
        String protocol = (dto.getProtocol() == null || dto.getProtocol().isEmpty())
                ? "shadowsocks" : dto.getProtocol().toLowerCase();

        // 通用字段(sing-box 一律 listen 127.0.0.1,公网口交给 gost 限速)
        Inbound in = new Inbound();
        in.setNodeId(node.getId());
        in.setProtocol(protocol);
        in.setListenPort(dto.getListenPort() != null ? dto.getListenPort() : allocateListenPort(node.getId()));
        in.setTag("in-" + node.getId() + "-" + in.getListenPort());
        in.setRemark(dto.getRemark());
        in.setLandingId(dto.getLandingId()); // 空=直连,有=中转(经该落地出网)
        in.setStatus(1);
        in.setCreatedTime(System.currentTimeMillis());
        in.setUpdatedTime(System.currentTimeMillis());

        // 按协议装配私有字段
        if ("shadowsocks".equals(protocol)) {
            // SS-2022:无 TLS、不依赖客户端指纹,绕开 reality 的后量子坑;单密码,用户靠 gost 口区分
            in.setSecurity("none");
            JSONObject cfg = new JSONObject();
            cfg.put("method", "2022-blake3-aes-256-gcm");
            cfg.put("password", genSsKey());
            in.setConfigJson(cfg.toJSONString());
        } else if ("vmess".equals(protocol)) {
            // VMess(TCP,无 TLS,无域名):无需密钥,用户 assign 时发 uuid
            in.setSecurity("none");
        } else if ("hysteria2".equals(protocol) || "tuic".equals(protocol) || "anytls".equals(protocol)) {
            // 自签 TLS(Hy2/TUIC 走 QUIC/UDP,AnyTLS 走 TCP;客户端 insecure);证书由节点端自动生成
            in.setSecurity("tls");
            String tlsSni = sanitizeSni(dto.getSni());
            in.setSni(tlsSni != null ? tlsSni : "www.bing.com");
        } else if ("vless".equals(protocol) || "trojan".equals(protocol)) {
            // VLESS / Trojan 均走 Reality(无域名):节点用 sing-box 生成 Reality 密钥对
            if (dto.getSni() == null || dto.getSni().isEmpty()) {
                return R.err("Reality 协议需要 SNI");
            }
            // 脏值(比如下拉的显示文字)会原样进 sing-box 的 server_name,握手必挂,这里最后卡一道
            String realSni = realitySni(dto.getSni());
            GostDto kp = SingboxUtil.GenerateRealityKeypair(node.getId(), null);
            // send_msg 返回的 GostDto 不设 code,判成功看 msg=="OK"(与 ForwardServiceImpl 一致)
            if (kp == null || !"OK".equals(kp.getMsg()) || kp.getData() == null) {
                return R.err("生成 Reality 密钥失败:" + (kp != null && kp.getMsg() != null ? kp.getMsg() : "节点无响应/超时"));
            }
            JSONObject kpData = JSON.parseObject(JSON.toJSONString(kp.getData()));
            String privateKey = kpData.getString("privateKey");
            String publicKey = kpData.getString("publicKey");
            if (privateKey == null || publicKey == null) {
                return R.err("Reality 密钥解析失败");
            }
            in.setSecurity("reality");
            in.setSni(realSni);
            String destSni = sanitizeSni(dto.getDest());
            in.setDest(destSni != null ? destSni : realSni);
            in.setPublicKey(publicKey);
            in.setPrivateKey(privateKey);
            in.setShortId(randomShortId());
        } else {
            return R.err("暂不支持的协议:" + protocol);
        }

        if (!this.save(in)) {
            return R.err("入站保存失败");
        }
        return R.ok(in);
    }

    /** 借壳域名默认值:apple 实测最稳,别换成 www.microsoft.com(上了后量子,Reality 握不上手) */
    private static final String DEFAULT_REALITY_SNI = "www.apple.com";

    /** 只允许域名字符的白名单,把「www.apple.com(默认,最稳)」这种带说明的整串挡在外面 */
    private static final java.util.regex.Pattern SNI_PATTERN = java.util.regex.Pattern
            .compile("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    /**
     * 规范化 Reality 借壳域名。
     *
     * 前端下拉曾经把选项的显示文字(带中文说明)当值传上来,直接写进了 sing-box 的
     * server_name,结果 VLESS/Trojan 全部握不上手而其它协议正常 —— 这种脏值不能
     * 再有第二次,所以在落库前用域名白名单卡一道,不合格就退回默认值。
     */
    private String realitySni(String sni) {
        String s = sanitizeSni(sni);
        return s == null ? DEFAULT_REALITY_SNI : s;
    }

    /** 清洗 SNI:合法域名原样返回,脏值(带说明文字、空白等)一律返回 null 交给调用方兜默认 */
    private String sanitizeSni(String sni) {
        if (sni == null) {
            return null;
        }
        String s = sni.trim();
        return (!s.isEmpty() && SNI_PATTERN.matcher(s).matches()) ? s : null;
    }

    @Override
    public R oneClickCreate(Long nodeId, String sni) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }
        // 支持的协议一键全建。SS 用不了,已去掉。
        String realitySni = realitySni(sni);
        String[] protocols = {"vless", "trojan", "vmess", "hysteria2", "tuic", "anytls"};
        List<Object> created = new java.util.ArrayList<>();
        for (String p : protocols) {
            InboundDto dto = new InboundDto();
            dto.setNodeId(nodeId);
            dto.setProtocol(p);
            if ("vless".equals(p) || "trojan".equals(p)) {
                dto.setSni(realitySni);
            }
            R r = buildAndSaveInbound(dto); // 只入库,不推送(否则每个都重启 sing-box,7 次触发 systemd 限流)
            if (r.getCode() != 0) {
                return R.err("一键添加中断(" + p + "):" + r.getMsg() + "(已成功 " + created.size() + " 个)");
            }
            created.add(r.getData());
        }
        // 全部入库后,统一推一次配置(只重启一次 sing-box)
        R push = pushNodeSingbox(nodeId);
        if (push.getCode() != 0) {
            return R.err("一键添加已入库,但下发 sing-box 配置失败:" + push.getMsg());
        }
        return R.ok(created);
    }

    @Override
    public R oneClickRelay(Long nodeId, String link, String name, String sni) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) {
            return R.err("前置机不存在");
        }
        // 内联建落地(粘贴的分享链接现场解析入库,不走"落地库"选择)
        com.admin.common.dto.LandingDto ldto = new com.admin.common.dto.LandingDto();
        ldto.setName((name == null || name.trim().isEmpty()) ? ("落地-" + node.getName()) : name.trim());
        ldto.setLink(link);
        R lr = landingService.createLanding(ldto);
        if (lr.getCode() != 0) {
            return R.err("落地解析失败:" + lr.getMsg());
        }
        Long landingId = ((com.admin.entity.Landing) lr.getData()).getId();
        // 和一键搭协议一样建全套,只是每个入站带上 landing_id → 流量经该落地出网
        String realitySni = realitySni(sni);
        String[] protocols = {"vless", "trojan", "vmess", "hysteria2", "tuic", "anytls"};
        List<Object> created = new java.util.ArrayList<>();
        for (String p : protocols) {
            InboundDto dto = new InboundDto();
            dto.setNodeId(nodeId);
            dto.setProtocol(p);
            dto.setLandingId(landingId);
            if ("vless".equals(p) || "trojan".equals(p)) {
                dto.setSni(realitySni);
            }
            R r = buildAndSaveInbound(dto);
            if (r.getCode() != 0) {
                return R.err("一键搭中转中断(" + p + "):" + r.getMsg() + "(已成功 " + created.size() + " 个)");
            }
            created.add(r.getData());
        }
        R push = pushNodeSingbox(nodeId);
        if (push.getCode() != 0) {
            return R.err("中转已入库,但下发 sing-box 配置失败:" + push.getMsg());
        }
        return R.ok(created);
    }

    @Override
    public R deleteInboundsByNode(Long nodeId, Boolean relay, Long landingId) {
        // 只清该机的目标组:relay=true 清某落地的中转协议;否则清直连协议。避免从直连卡把中转也删了。
        QueryWrapper<Inbound> qw = new QueryWrapper<Inbound>().eq("node_id", nodeId);
        if (Boolean.TRUE.equals(relay)) {
            if (landingId != null) {
                qw.eq("landing_id", landingId);
            } else {
                qw.isNotNull("landing_id");
            }
        } else {
            qw.isNull("landing_id");
        }
        List<Inbound> inbounds = this.list(qw);
        for (Inbound in : inbounds) {
            deleteInbound(in.getId());
        }
        return R.ok();
    }

    /** SS-2022 密钥:32 字节随机 → 标准 base64(带 padding),sing-box 的 password 要这个格式 */
    private String genSsKey() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    @Override
    public R getInbounds() {
        return R.ok(this.list());
    }

    @Override
    public R deleteInbound(Long id) {
        Inbound in = this.getById(id);
        if (in == null) {
            return R.err("入站不存在");
        }
        List<InboundUser> users = inboundUserMapper.selectList(
                new QueryWrapper<InboundUser>().eq("inbound_id", id));
        for (InboundUser u : users) {
            if (u.getGostForwardId() != null) {
                forwardService.deleteForward(u.getGostForwardId());
            }
            inboundUserMapper.deleteById(u.getId());
        }
        this.removeById(id);
        pushNodeSingbox(in.getNodeId());
        return R.ok();
    }

    @Override
    public R assignUser(InboundUserDto dto) {
        Inbound in = this.getById(dto.getInboundId());
        if (in == null) {
            return R.err("入站不存在");
        }
        User user = userMapper.selectById(dto.getUserId());
        if (user == null) {
            return R.err("用户不存在");
        }
        Node node = nodeMapper.selectById(in.getNodeId());
        if (node == null) {
            return R.err("节点不存在");
        }

        // 0. 查重:已给这个用户分过这个协议 → 直接返回现有链接 + 订阅,不重复建(避免重复占端口/转发)
        InboundUser existed = inboundUserMapper.selectOne(new QueryWrapper<InboundUser>()
                .eq("inbound_id", in.getId()).eq("user_id", user.getId()).last("limit 1"));
        // 【自愈】同 assignAllToUser:记录还在但转发没了 → 清掉孤儿,当没分配过重新走一遍。
        // 不然这里会直接 return 一个空链接,而且每次都返回空,用户无从下手。
        if (existed != null && (existed.getGostForwardId() == null
                || forwardMapper.selectById(existed.getGostForwardId()) == null)) {
            inboundUserMapper.deleteById(existed.getId());
            existed = null;
        }
        if (existed != null) {
            Forward f = existed.getGostForwardId() != null ? forwardMapper.selectById(existed.getGostForwardId()) : null;
            JSONObject result = new JSONObject();
            result.put("inboundUserId", existed.getId());
            result.put("link", f != null ? buildClientLink(in, existed, node, f) : "");
            result.put("subToken", getOrCreateLineSubToken(user.getId(), node.getId(), in.getLandingId()));
            return R.ok(result);
        }

        // 1. 确保该节点有一条端口转发隧道(入口机=该节点)
        Tunnel tunnel = ensurePortForwardTunnel(node.getId());
        if (tunnel == null) {
            return R.err("创建入站转发隧道失败");
        }

        // 2. 生成凭证(uuid 给 vless/vmess,password 给 trojan)
        String uuid = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString().replace("-", "");

        // 2.5 若选了限速规则,下发【车友专属】限速器(mode=0 服务级 `$`,TCP+UDP 都限、车友间独立)
        Integer userLimiter = null;
        if (dto.getSpeedId() != null) {
            userLimiter = perUserLimiterName(user.getId());
            R lr = speedLimitService.pushUserLimiter(dto.getSpeedId(), userLimiter.longValue(), node.getId());
            if (lr.getCode() != 0) {
                return R.err("下发限速器失败:" + lr.getMsg());
            }
        }

        // 3. 建该用户的 gost 前置转发(远程=127.0.0.1:入站口,绑限速/到期,归属车友)
        ForwardDto fdto = new ForwardDto();
        fdto.setName("inbound-" + in.getId() + "-user-" + user.getId());
        fdto.setTunnelId(tunnel.getId().intValue());
        fdto.setRemoteAddr("127.0.0.1:" + in.getListenPort());
        fdto.setStrategy("fifo");
        fdto.setSpeedId(userLimiter); // 转发引用车友专属限速器
        fdto.setExpTime(dto.getExpTime());
        R fr = createForwardAutoPort(fdto, user, node.getId()); // 端口被占自动上移
        if (fr.getCode() != 0 || fr.getData() == null) {
            return R.err("建转发失败:" + fr.getMsg());
        }
        Forward forward = (Forward) fr.getData();

        // 4. 流量配额写到用户(可空=不改)
        if (dto.getFlow() != null) {
            user.setFlow(dto.getFlow());
            userMapper.updateById(user);
        }

        // 5. 存 inbound_user(带该【线路】的订阅 token:车友×机器×落地组一条)
        String subToken = getOrCreateLineSubToken(user.getId(), node.getId(), in.getLandingId());

        InboundUser iu = new InboundUser();
        iu.setInboundId(in.getId());
        iu.setUserId(user.getId());
        iu.setUuid(uuid);
        iu.setPassword(password);
        iu.setSubToken(subToken);
        iu.setGostForwardId(forward.getId());
        iu.setStatus(1);
        iu.setCreatedTime(System.currentTimeMillis());
        inboundUserMapper.insert(iu);

        // 6. 重推 sing-box 配置(users 里加上这个 uuid)
        R push = pushNodeSingbox(node.getId());
        if (push.getCode() != 0) {
            return push;
        }

        // 7. 出客户端链接(地址=该转发的公网口,被限速)+ 该用户的订阅 token
        String link = buildClientLink(in, iu, node, forward);

        JSONObject result = new JSONObject();
        result.put("inboundUserId", iu.getId());
        result.put("uuid", uuid);
        result.put("port", forward.getInPort());
        result.put("link", link);
        result.put("subToken", subToken);
        return R.ok(result);
    }

    @Override
    public R assignAllToUser(InboundUserDto dto) {
        User user = userMapper.selectById(dto.getUserId());
        if (user == null) {
            return R.err("用户不存在");
        }
        // 分配的是「直连组」还是「某落地的中转组」——同一台机器的直连、每个落地各算一条独立线路/订阅
        boolean relay = Boolean.TRUE.equals(dto.getRelay());
        Long groupLanding = relay ? dto.getLandingId() : null;
        if (relay && groupLanding == null) {
            return R.err("中转分配缺落地");
        }
        QueryWrapper<Inbound> qw = new QueryWrapper<>();
        if (dto.getNodeId() != null) {
            qw.eq("node_id", dto.getNodeId());
        }
        if (relay) {
            qw.eq("landing_id", groupLanding);   // 中转组:该落地的协议
        } else {
            qw.isNull("landing_id");             // 直连组:本机出网的协议
        }
        List<Inbound> inbounds = this.list(qw);
        // 协议级分配:只保留选中的入站(同属这条线路);空=整条线路全部协议。
        // 线路额度/到期照常在下面按 dto.flow/expTime 写 InboundLine,和整条分配完全一致。
        if (dto.getInboundIds() != null && !dto.getInboundIds().isEmpty()) {
            java.util.Set<Long> pick = new java.util.HashSet<>(dto.getInboundIds());
            inbounds = inbounds.stream().filter(x -> pick.contains(x.getId())).collect(java.util.stream.Collectors.toList());
        }
        if (inbounds.isEmpty()) {
            return R.err(relay ? "这条中转还没有协议" : "这台机器还没有直连协议,先去「一键添加」");
        }
        // 订阅按【线路】= 车友 × 机器 × 落地组:一组共享一条订阅 token;车友可有很多条线路
        java.util.Map<Long, String> lineTokens = new java.util.HashMap<>();
        // 线路记录(流量配额/到期都记在它上面)必须在循环【之前】建好/更新:
        // 重新分配(续费、改配额)时协议往往已经全都分过了,循环会整个跳过,
        // 那样新填的配额和到期就永远写不进去 —— 用户那边看着"改了没反应"。
        if (dto.getNodeId() != null) {
            lineTokens.put(dto.getNodeId(),
                    getOrCreateLine(user.getId(), dto.getNodeId(), groupLanding, dto.getFlow(), dto.getExpTime()).getSubToken());
        }
        // 车友专属限速器(每车友唯一;每节点只推一次,该机所有协议共享;TCP+UDP 都靠服务级 `$` 限住、车友间独立)
        Integer userLimiter = (dto.getSpeedId() != null) ? perUserLimiterName(user.getId()) : null;
        java.util.Set<Long> affectedNodes = new java.util.HashSet<>();
        java.util.Set<Long> limiterPushedNodes = new java.util.HashSet<>();
        // 本次分配里已确认「机器上被别的程序占了」的端口,按节点分开记。
        // 一台机 6 个协议逐个分配,不共享的话每个协议都要把同一批被占端口重新踩一遍,
        // 而每踩一次就是一个最多 10 秒的节点往返 —— 分配卡死主要卡在这。
        java.util.Map<Long, java.util.Set<Integer>> busyPortsByNode = new java.util.HashMap<>();
        int assigned = 0, skipped = 0, updated = 0;
        String firstError = null;
        for (Inbound in : inbounds) {
            if (in.getStatus() != null && in.getStatus() == 0) {
                continue;
            }
            InboundUser existed = inboundUserMapper.selectOne(new QueryWrapper<InboundUser>()
                    .eq("inbound_id", in.getId()).eq("user_id", user.getId()).last("limit 1"));
            // 【自愈】分配记录还在,但它指向的转发已经不在了(转发所在的隧道被整条删掉时就会这样)。
            // 这种状态下链接是空的、订阅里一条都没有,而下面那段"续费"只会去 update 一条
            // 不存在的记录 —— 点多少次「一键全协议」都修不回来。先把孤儿记录清掉,
            // 让它走下面的正常分配路径重建(会重新建转发、重新出链接)。
            if (existed != null && (existed.getGostForwardId() == null
                    || forwardMapper.selectById(existed.getGostForwardId()) == null)) {
                inboundUserMapper.deleteById(existed.getId());
                existed = null;
            }
            if (existed != null) {
                skipped++;
                // 已分过这个协议 → 不能只是跳过。重新分配 = 续费 / 改配置,
                // 得把新的限速和到期落到【已有的转发】上并重推 gost,否则改了等于没改。
                if (existed.getGostForwardId() != null && (userLimiter != null || dto.getExpTime() != null)) {
                    Node n = nodeMapper.selectById(in.getNodeId());
                    if (n != null) {
                        if (userLimiter != null && !limiterPushedNodes.contains(n.getId())) {
                            R lr = speedLimitService.pushUserLimiter(dto.getSpeedId(), userLimiter.longValue(), n.getId());
                            if (lr.getCode() == 0) {
                                limiterPushedNodes.add(n.getId());
                            } else if (firstError == null) {
                                firstError = "下发限速器失败:" + lr.getMsg();
                            }
                        }
                        Forward f = forwardMapper.selectById(existed.getGostForwardId());
                        if (f != null) {
                            if (userLimiter != null) {
                                f.setSpeedId(userLimiter);
                            }
                            if (dto.getExpTime() != null) {
                                f.setExpTime(dto.getExpTime());
                            }
                            f.setStatus(1); // 之前因超额/到期被停的,续费后恢复
                            f.setUpdatedTime(System.currentTimeMillis());
                            forwardMapper.updateById(f);
                            try {
                                forwardService.updateForwardA(f); // 重推 gost,让新限速立刻生效
                            } catch (Exception e) {
                                if (firstError == null) firstError = "更新转发失败:" + e.getMessage();
                            }
                            updated++;
                        }
                    }
                }
                continue;
            }
            Node node = nodeMapper.selectById(in.getNodeId());
            if (node == null) {
                skipped++;
                continue;
            }
            // 该节点上这个车友的限速器只推一次,后面这台机器的所有协议共享它(避免每协议重推破坏共享)
            if (userLimiter != null && !limiterPushedNodes.contains(node.getId())) {
                R lr = speedLimitService.pushUserLimiter(dto.getSpeedId(), userLimiter.longValue(), node.getId());
                if (lr.getCode() != 0) {
                    if (firstError == null) firstError = "下发限速器失败:" + lr.getMsg();
                    skipped++;
                    continue;
                }
                limiterPushedNodes.add(node.getId());
            }
            // 这条线路(机器×落地组)的记录:承载订阅 token + 该线路的流量配额/到期;该组所有协议共享
            String subToken = lineTokens.computeIfAbsent(node.getId(),
                    nid -> getOrCreateLine(user.getId(), nid, groupLanding, dto.getFlow(), dto.getExpTime()).getSubToken());
            R r = assignOneNoPush(in, node, user, userLimiter, dto.getExpTime(), subToken,
                    busyPortsByNode.computeIfAbsent(node.getId(), k -> new java.util.HashSet<>()));
            if (r.getCode() != 0) {
                if (firstError == null) firstError = r.getMsg();
                skipped++;
                continue;
            }
            affectedNodes.add(node.getId());
            assigned++;
        }
        // 每个受影响的节点只推一次配置(避免反复重启)
        for (Long nid : affectedNodes) {
            pushNodeSingbox(nid);
        }
        // 结果里带回这条线路的订阅 token(机器卡分配 = 单机单落地组)
        String resultToken = (dto.getNodeId() != null)
                ? lineTokens.computeIfAbsent(dto.getNodeId(),
                        nid -> getOrCreateLine(user.getId(), nid, groupLanding, dto.getFlow(), dto.getExpTime()).getSubToken())
                : (lineTokens.isEmpty() ? null : lineTokens.values().iterator().next());
        if (assigned == 0) {
            if (firstError == null && skipped > 0) {
                // 协议早就分过了 → 这次是续费/改配置(配额和到期已写到线路,限速已重推到已有转发)
                JSONObject r2 = new JSONObject();
                r2.put("subToken", resultToken);
                r2.put("assigned", 0);
                r2.put("skipped", skipped);
                r2.put("updated", updated);
                return R.ok(r2);
            }
            return R.err(firstError != null ? ("分配失败:" + firstError) : "没有可分配的协议(可能都已分配过)");
        }
        JSONObject result = new JSONObject();
        result.put("subToken", resultToken);
        result.put("assigned", assigned);
        result.put("skipped", skipped);
        result.put("updated", updated);
        result.put("firstError", firstError);
        return R.ok(result);
    }

    /** 给某用户在某入站上建凭证+转发,但不推 sing-box(批量分配时最后统一推)。limiterName=车友专属限速器名(调用方已推好) */
    private R assignOneNoPush(Inbound in, Node node, User user, Integer limiterName, Long expTime, String subToken) {
        return assignOneNoPush(in, node, user, limiterName, expTime, subToken, new java.util.HashSet<>());
    }

    private R assignOneNoPush(Inbound in, Node node, User user, Integer limiterName, Long expTime, String subToken,
                              java.util.Set<Integer> knownBusyPorts) {
        Tunnel tunnel = ensurePortForwardTunnel(node.getId());
        if (tunnel == null) {
            return R.err("创建入站转发隧道失败");
        }
        String uuid = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString().replace("-", "");
        ForwardDto fdto = new ForwardDto();
        fdto.setName("inbound-" + in.getId() + "-user-" + user.getId());
        fdto.setTunnelId(tunnel.getId().intValue());
        fdto.setRemoteAddr("127.0.0.1:" + in.getListenPort());
        fdto.setStrategy("fifo");
        fdto.setSpeedId(limiterName); // 转发引用车友专属限速器
        fdto.setExpTime(expTime);
        R fr = createForwardAutoPort(fdto, user, node.getId(), knownBusyPorts); // 端口被占自动上移
        if (fr.getCode() != 0 || fr.getData() == null) {
            return R.err("建转发失败:" + fr.getMsg());
        }
        Forward forward = (Forward) fr.getData();
        InboundUser iu = new InboundUser();
        iu.setInboundId(in.getId());
        iu.setUserId(user.getId());
        iu.setUuid(uuid);
        iu.setPassword(password);
        iu.setSubToken(subToken);
        iu.setGostForwardId(forward.getId());
        iu.setStatus(1);
        iu.setCreatedTime(System.currentTimeMillis());
        inboundUserMapper.insert(iu);
        return R.ok(iu);
    }

    /**
     * 「全部线路」聚合订阅:把该车友所有【未停用线路】的节点拼成一条订阅。
     *
     * 两个刻意的取舍:
     * 1. 节点名带线路前缀([机器名] / [机器名→落地名]),否则十几个节点混在一起
     *    根本分不清哪个是哪条线路的出口。
     * 2. 停用的线路直接不出现在订阅里,而不是留着让人连不上 —— 车友更新订阅
     *    发现少了一组,配合前缀能立刻知道是哪条到期/跑满了。
     *
     * 不返回 subscription-userinfo 流量头:多条线路各有各的配额,只能报一组数字,
     * 报哪条都是误导。用量按线路看面板的「我的订阅」,那里是准的。
     */
    private String buildAggregateSubscription(User u) {
        List<InboundUser> ius = inboundUserMapper.selectList(
                new QueryWrapper<InboundUser>().eq("user_id", u.getId()));
        List<String> links = new java.util.ArrayList<>();
        java.util.Map<String, Boolean> lineStopped = new HashMap<>();
        java.util.Map<Long, Node> nodeCache = new HashMap<>();
        java.util.Map<Long, String> landingNames = new HashMap<>();

        for (InboundUser iu : ius) {
            if (iu.getStatus() != null && iu.getStatus() == 0) {
                continue;
            }
            Inbound in = this.getById(iu.getInboundId());
            if (in == null || iu.getGostForwardId() == null) {
                continue;
            }
            Long lid = in.getLandingId();
            String lineKey = in.getNodeId() + "|" + (lid == null ? 0L : lid);
            Boolean stopped = lineStopped.get(lineKey);
            if (stopped == null) {
                InboundLine line = getLine(u.getId(), in.getNodeId(), lid);
                stopped = (line != null && line.getStatus() != null && line.getStatus() == 0);
                lineStopped.put(lineKey, stopped);
            }
            if (stopped) {
                continue;
            }
            Node node = nodeCache.get(in.getNodeId());
            if (node == null) {
                node = nodeMapper.selectById(in.getNodeId());
                if (node == null) {
                    continue;
                }
                nodeCache.put(in.getNodeId(), node);
            }
            Forward forward = forwardMapper.selectById(iu.getGostForwardId());
            if (forward == null) {
                continue;
            }
            // 不加方括号:名字最终要显示在手机客户端一行里,中转的还要再带一个
            // 「→落地名」,方括号纯占位置。空格分隔已经够读了。
            StringBuilder prefix = new StringBuilder(node.getName());
            if (lid != null) {
                String ln = landingNames.get(lid);
                if (ln == null) {
                    com.admin.entity.Landing l = landingMapper.selectById(lid);
                    ln = (l != null && l.getName() != null) ? l.getName() : ("落地#" + lid);
                    landingNames.put(lid, ln);
                }
                prefix.append("→").append(ln);
            }
            prefix.append(" ");

            String link = buildClientLink(in, iu, node, forward, prefix.toString());
            if (link != null && !link.isEmpty()) {
                links.add(link);
            }
        }
        String joined = String.join("\n", links);
        return java.util.Base64.getEncoder()
                .encodeToString(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Clash / Mihomo 订阅(YAML)。
     *
     * 和 buildSubscription 走同一套数据,只是输出格式不同 —— Mihomo 系客户端
     * (Clash Verge Rev、ClashMeta)不认 base64 链接列表,贴进去是空的。
     * token 既可能是聚合订阅的,也可能是单条线路的,两种都支持。
     */
    @Override
    public String buildClashSubscription(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        List<InboundUser> ius;
        User aggUser = userMapper.selectOne(
                new QueryWrapper<User>().eq("all_sub_token", token).last("limit 1"));
        if (aggUser != null) {
            ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>().eq("user_id", aggUser.getId()));
        } else {
            ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>().eq("sub_token", token));
        }

        List<java.util.Map<String, Object>> proxies = new java.util.ArrayList<>();
        java.util.Set<String> usedNames = ClashUtil.newNameSet();
        java.util.Map<String, Boolean> lineStopped = new HashMap<>();
        java.util.Map<Long, Node> nodeCache = new HashMap<>();
        java.util.Map<Long, String> landingNames = new HashMap<>();

        for (InboundUser iu : ius) {
            if (iu.getStatus() != null && iu.getStatus() == 0) {
                continue;
            }
            Inbound in = this.getById(iu.getInboundId());
            if (in == null || iu.getGostForwardId() == null) {
                continue;
            }
            Long lid = in.getLandingId();
            String lineKey = in.getNodeId() + "|" + (lid == null ? 0L : lid);
            Boolean stopped = lineStopped.get(lineKey);
            if (stopped == null) {
                InboundLine line = getLine(iu.getUserId(), in.getNodeId(), lid);
                stopped = (line != null && line.getStatus() != null && line.getStatus() == 0);
                lineStopped.put(lineKey, stopped);
            }
            if (stopped) {
                continue;
            }
            Node node = nodeCache.get(in.getNodeId());
            if (node == null) {
                node = nodeMapper.selectById(in.getNodeId());
                if (node == null) {
                    continue;
                }
                nodeCache.put(in.getNodeId(), node);
            }
            Forward forward = forwardMapper.selectById(iu.getGostForwardId());
            if (forward == null) {
                continue;
            }

            // 名字规则和链接订阅保持一致:聚合订阅带线路前缀,单条不带。
            // 两种订阅里同一个节点应该叫同一个名字,不然车友对不上。
            boolean customName = in.getRemark() != null && !in.getRemark().isEmpty();
            String remark = customName ? in.getRemark() : protocolDisplayName(in.getProtocol());
            // 和链接订阅同一套规则:改过名就不加前缀(见 buildClientLink 的说明)。
            // 这两处必须一起改 —— 上面的注释本来就写着"两种订阅里同一个节点应该叫同一个名字"。
            if (aggUser != null && !customName) {
                StringBuilder prefix = new StringBuilder(node.getName());
                if (lid != null) {
                    String ln = landingNames.get(lid);
                    if (ln == null) {
                        com.admin.entity.Landing l = landingMapper.selectById(lid);
                        ln = (l != null && l.getName() != null) ? l.getName() : ("落地#" + lid);
                        landingNames.put(lid, ln);
                    }
                    prefix.append("→").append(ln);
                }
                prefix.append(" ");
                remark = prefix + remark;
            }

            String ip = (node.getDomain() != null && !node.getDomain().trim().isEmpty())
                    ? node.getDomain().trim()
                    : node.getServerIp();
            String ssMethod = null;
            if ("shadowsocks".equals(in.getProtocol())) {
                JSONObject cfg = JSON.parseObject(in.getConfigJson() == null ? "{}" : in.getConfigJson());
                ssMethod = cfg.getString("method");
            }
            java.util.Map<String, Object> proxy = ClashUtil.toProxy(
                    in.getProtocol(),
                    ClashUtil.uniqueName(remark, usedNames),
                    ip, forward.getInPort(),
                    iu.getUuid(), iu.getPassword(), in.getSni(),
                    in.getPublicKey(), in.getShortId(), ssMethod);
            if (proxy != null) {
                proxies.add(proxy);
            }
        }
        if (proxies.isEmpty()) {
            // 空 proxies 的配置 Mihomo 会直接报错。给一条 DIRECT 占位,
            // 至少订阅能导进去,车友看到的是「没有节点」而不是「订阅损坏」。
            StringBuilder empty = new StringBuilder();
            empty.append("proxies: []").append(System.lineSeparator());
            empty.append("proxy-groups: []").append(System.lineSeparator());
            empty.append("rules:").append(System.lineSeparator());
            empty.append("  - MATCH,DIRECT").append(System.lineSeparator());
            return empty.toString();
        }
        return ClashUtil.buildConfig(proxies);
    }

    /** 取(必要时生成)该车友的「全部线路」聚合订阅 token */
    private String ensureAllSubToken(User u) {
        if (u.getAllSubToken() != null && !u.getAllSubToken().isEmpty()) {
            return u.getAllSubToken();
        }
        String t = UUID.randomUUID().toString().replace("-", "");
        u.setAllSubToken(t);
        userMapper.updateById(u);
        return t;
    }

    /** 按协议为某个入站用户拼客户端链接(assignUser 与订阅共用) */
    private String buildClientLink(Inbound in, InboundUser iu, Node node, Forward forward) {
        return buildClientLink(in, iu, node, forward, "");
    }

    /**
     * 协议在客户端里显示的名字。
     * 没填备注时拿它当节点名 —— 原来兜底用的是 tag(in-1-40001 这种),
     * 占了十个字符还看不出是什么协议,单条线路订阅里六个节点全长一个样。
     */
    private static String protocolDisplayName(String protocol) {
        if (protocol == null) {
            return "VLESS";
        }
        switch (protocol) {
            case "shadowsocks": return "Shadowsocks";
            case "vmess":       return "VMess";
            case "trojan":      return "Trojan";
            case "hysteria2":   return "Hysteria2";
            case "tuic":        return "TUIC";
            case "anytls":      return "AnyTLS";
            default:            return "VLESS";
        }
    }

    /** namePrefix:聚合订阅里用来标注这个节点属于哪条线路,单条线路订阅传空串 */
    private String buildClientLink(Inbound in, InboundUser iu, Node node, Forward forward, String namePrefix) {
        boolean customName = in.getRemark() != null && !in.getRemark().isEmpty();
        String remark = customName ? in.getRemark() : protocolDisplayName(in.getProtocol());
        // 【改过名就完全听用户的】前缀本来是帮忙区分用的:车友订阅里十几条全叫 VLESS/Trojan,
        // 不标节点名根本分不清哪条是哪台机器。但用户一旦主动改过名,说明他要自己控制显示,
        // 这时候再往前面塞"面板机→澳大利亚 "就是帮倒忙了。
        // 所以只在没改过名(remark 为空、用协议名兜底)时才加前缀。
        if (!customName && namePrefix != null && !namePrefix.isEmpty()) {
            remark = namePrefix + remark;
        }
        String uuid = iu.getUuid();
        String password = iu.getPassword();
        // 转发机配了域名就用域名 —— 车友在客户端里看到的是 hk.example.com 而不是车主的 IP
        String ip = (node.getDomain() != null && !node.getDomain().trim().isEmpty())
                ? node.getDomain().trim()
                : node.getServerIp();
        Integer port = forward.getInPort();
        switch (in.getProtocol() == null ? "" : in.getProtocol()) {
            case "shadowsocks": {
                JSONObject cfg = JSON.parseObject(in.getConfigJson() == null ? "{}" : in.getConfigJson());
                return SingboxUtil.buildShadowsocksLink(ip, port, cfg.getString("method"), cfg.getString("password"), remark);
            }
            case "vmess":
                return SingboxUtil.buildVmessLink(uuid, ip, port, remark);
            case "trojan":
                return SingboxUtil.buildTrojanRealityLink(password, ip, port, in.getSni(), in.getPublicKey(), in.getShortId(), remark);
            case "hysteria2":
                return SingboxUtil.buildHysteria2Link(password, ip, port, in.getSni(), remark);
            case "tuic":
                return SingboxUtil.buildTuicLink(uuid, password, ip, port, in.getSni(), remark);
            case "anytls":
                return SingboxUtil.buildAnyTlsLink(password, ip, port, in.getSni(), remark);
            default: // vless
                return SingboxUtil.buildVlessRealityLink(uuid, ip, port, in.getSni(), in.getPublicKey(), in.getShortId(), remark);
        }
    }

    @Override
    public String buildSubscription(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        // 「全部线路」聚合订阅:一条链接给出该车友所有未停用线路的节点
        User aggUser = userMapper.selectOne(
                new QueryWrapper<User>().eq("all_sub_token", token).last("limit 1"));
        if (aggUser != null) {
            return buildAggregateSubscription(aggUser);
        }
        List<InboundUser> ius = inboundUserMapper.selectList(
                new QueryWrapper<InboundUser>().eq("sub_token", token));
        List<String> links = new java.util.ArrayList<>();
        // 线路停用状态查一次缓存起来:一条订阅里六个协议同属一条线路,
        // 挨个去查线路表纯属浪费。
        java.util.Map<String, Boolean> lineStopped = new HashMap<>();
        for (InboundUser iu : ius) {
            if (iu.getStatus() != null && iu.getStatus() == 0) {
                continue;
            }
            Inbound in = this.getById(iu.getInboundId());
            if (in == null || iu.getGostForwardId() == null) {
                continue;
            }
            // 线路被停用时这条订阅也要空掉。聚合订阅早就这么做了,单条这边一直没查 ——
            // 结果车主停用之后,对方更新订阅照样看得到节点,只是连不上(转发已暂停),
            // 反而像是节点坏了。
            Long lid = in.getLandingId();
            String lineKey = in.getNodeId() + "|" + (lid == null ? 0L : lid);
            Boolean stopped = lineStopped.get(lineKey);
            if (stopped == null) {
                InboundLine line = getLine(iu.getUserId(), in.getNodeId(), lid);
                stopped = (line != null && line.getStatus() != null && line.getStatus() == 0);
                lineStopped.put(lineKey, stopped);
            }
            if (stopped) {
                continue;
            }
            Node node = nodeMapper.selectById(in.getNodeId());
            Forward forward = forwardMapper.selectById(iu.getGostForwardId());
            if (node == null || forward == null) {
                continue;
            }
            String link = buildClientLink(in, iu, node, forward);
            if (link != null && !link.isEmpty()) {
                links.add(link);
            }
        }
        String joined = String.join("\n", links);
        return java.util.Base64.getEncoder().encodeToString(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public String getUserSubToken(Long userId) {
        if (userId == null) {
            return "";
        }
        // 兼容旧接口:订阅现在按线路(车友×机器),这里返回该车友任意一条现成 token;推荐用 getUserLines
        InboundUser iu = inboundUserMapper.selectOne(new QueryWrapper<InboundUser>()
                .eq("user_id", userId).isNotNull("sub_token").last("limit 1"));
        return (iu != null && iu.getSubToken() != null) ? iu.getSubToken() : "";
    }

    @Override
    public R getUserLines(Long userId) {
        com.alibaba.fastjson.JSONArray lines = new com.alibaba.fastjson.JSONArray();
        if (userId == null) {
            JSONObject empty = new JSONObject();
            empty.put("lines", lines);
            return R.ok(empty);
        }
        List<InboundUser> ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>().eq("user_id", userId));
        // 按【线路】= 机器 × 落地组 分组:同一台机器的直连、每个落地各算一条订阅
        java.util.Map<String, java.util.List<InboundUser>> byLine = new java.util.LinkedHashMap<>();
        java.util.Map<String, long[]> lineKey = new java.util.HashMap<>(); // key -> [nodeId, landingId(0=直连)]
        for (InboundUser iu : ius) {
            if (iu.getStatus() != null && iu.getStatus() == 0) {
                continue;
            }
            Inbound in = this.getById(iu.getInboundId());
            if (in == null) {
                continue;
            }
            long lid = in.getLandingId() != null ? in.getLandingId() : 0L;
            String key = in.getNodeId() + "|" + lid;
            byLine.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(iu);
            lineKey.putIfAbsent(key, new long[]{in.getNodeId(), lid});
        }
        for (java.util.Map.Entry<String, java.util.List<InboundUser>> e : byLine.entrySet()) {
            long[] k = lineKey.get(e.getKey());
            Long nodeId = k[0];
            Long landingId = (k[1] == 0L) ? null : k[1];
            Node node = nodeMapper.selectById(nodeId);
            String token = null;
            int count = 0;
            long lineFlow = 0L; // 这条线路已用流量 = 该线路各协议对应转发的上下行之和
            for (InboundUser iu : e.getValue()) {
                count++;
                if (token == null && iu.getSubToken() != null && !iu.getSubToken().isEmpty()) {
                    token = iu.getSubToken();
                }
                if (iu.getGostForwardId() != null) {
                    Forward f = forwardMapper.selectById(iu.getGostForwardId());
                    if (f != null) {
                        lineFlow += (f.getInFlow() == null ? 0L : f.getInFlow())
                                + (f.getOutFlow() == null ? 0L : f.getOutFlow());
                    }
                }
            }
            if (count == 0) {
                continue;
            }
            String landingName = null;
            if (landingId != null) {
                com.admin.entity.Landing l = landingMapper.selectById(landingId);
                if (l != null) {
                    landingName = l.getName();
                }
            }
            JSONObject line = new JSONObject();
            line.put("nodeId", nodeId);
            line.put("nodeName", node != null ? node.getName() : ("机器#" + nodeId));
            // landingId 要发给前端:线路的身份是「机器 × 落地」,只有名字的话
            // 两个同名落地就分不开,前端也没法指定要停哪一条。
            line.put("landingId", landingId);
            line.put("type", landingId != null ? "relay" : "direct");
            line.put("landingName", landingName);
            line.put("flow", lineFlow); // 该线路已用流量(字节)
            // 该线路自己的配额/到期(线路表);quotaGb=0/null 表示不单独限,只受账号总量约束
            InboundLine lineRec = getLine(userId, nodeId, landingId);
            line.put("quotaGb", lineRec != null ? lineRec.getFlow() : null);
            line.put("lineExpTime", lineRec != null ? lineRec.getExpTime() : null);
            line.put("lineStatus", lineRec != null ? lineRec.getStatus() : 1);
            line.put("protocolCount", count);
            line.put("subToken", token);
            lines.add(line);
        }
        // 返回结构从「数组」变成「对象」是为了带上聚合订阅 token。
        // 前端做了两种格式的兼容,老前端配新后端也不会白屏。
        JSONObject result = new JSONObject();
        result.put("lines", lines);
        if (!lines.isEmpty()) {
            User u = userMapper.selectById(userId);
            if (u != null) {
                result.put("allSubToken", ensureAllSubToken(u));
            }
        }
        return R.ok(result);
    }

    /** 只读:取这条线路的记录(没有返回 null) */
    private InboundLine getLine(Long userId, Long nodeId, Long landingId) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InboundLine> lw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InboundLine>()
                        .eq("user_id", userId).eq("node_id", nodeId);
        if (landingId != null) {
            lw.eq("landing_id", landingId);
        } else {
            lw.isNull("landing_id");
        }
        return inboundLineMapper.selectOne(lw.last("limit 1"));
    }

    /**
     * 取/建这条线路(车友 × 机器 × 落地组)的记录。线路承载:订阅 token、流量配额、到期。
     * quotaGb / expTime 传 null 表示不改动(只取现有线路);传值则写入。
     */
    private InboundLine getOrCreateLine(Long userId, Long nodeId, Long landingId, Long quotaGb, Long expTime) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InboundLine> lw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InboundLine>()
                        .eq("user_id", userId).eq("node_id", nodeId);
        if (landingId != null) {
            lw.eq("landing_id", landingId);
        } else {
            lw.isNull("landing_id");
        }
        InboundLine line = inboundLineMapper.selectOne(lw.last("limit 1"));
        if (line == null) {
            line = new InboundLine();
            line.setUserId(userId);
            line.setNodeId(nodeId);
            line.setLandingId(landingId);
            line.setSubToken(getOrCreateLineSubToken(userId, nodeId, landingId));
            line.setFlow(quotaGb);
            line.setExpTime(expTime);
            line.setStatus(1);
            line.setCreatedTime(System.currentTimeMillis());
            line.setUpdatedTime(System.currentTimeMillis());
            inboundLineMapper.insert(line);
            return line;
        }
        boolean changed = false;
        if (quotaGb != null) {
            line.setFlow(quotaGb);
            changed = true;
        }
        if (expTime != null) {
            line.setExpTime(expTime);
            changed = true;
        }
        if (line.getSubToken() == null || line.getSubToken().isEmpty()) {
            line.setSubToken(getOrCreateLineSubToken(userId, nodeId, landingId));
            changed = true;
        }
        if (changed) {
            line.setStatus(1); // 重新分配/续费 → 恢复正常
            line.setUpdatedTime(System.currentTimeMillis());
            inboundLineMapper.updateById(line);
        }
        return line;
    }

    /**
     * 取某车友在某条线路(机器 × 落地组)的订阅 token;没有就生成,回填给他在该组的所有 inbound_user。
     * landingId=null → 直连组(该机 landing_id IS NULL);非空 → 该落地的中转组。同机直连/各落地各一条 token。
     */
    private String getOrCreateLineSubToken(Long userId, Long nodeId, Long landingId) {
        QueryWrapper<Inbound> qw = new QueryWrapper<Inbound>().eq("node_id", nodeId);
        if (landingId != null) {
            qw.eq("landing_id", landingId);
        } else {
            qw.isNull("landing_id");
        }
        List<Long> inboundIds = new java.util.ArrayList<>();
        for (Inbound in : this.list(qw)) {
            inboundIds.add(in.getId());
        }
        if (inboundIds.isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        List<InboundUser> ius = inboundUserMapper.selectList(new QueryWrapper<InboundUser>()
                .eq("user_id", userId).in("inbound_id", inboundIds));
        String token = null;
        for (InboundUser iu : ius) {
            if (iu.getSubToken() != null && !iu.getSubToken().isEmpty()) {
                token = iu.getSubToken();
                break;
            }
        }
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "");
        }
        for (InboundUser iu : ius) {
            if (!token.equals(iu.getSubToken())) {
                iu.setSubToken(token);
                inboundUserMapper.updateById(iu);
            }
        }
        return token;
    }

    @Override
    public R unassignUser(Long inboundUserId) {
        InboundUser iu = inboundUserMapper.selectById(inboundUserId);
        if (iu == null) {
            return R.err("记录不存在");
        }
        if (iu.getGostForwardId() != null) {
            forwardService.deleteForward(iu.getGostForwardId());
        }
        inboundUserMapper.deleteById(inboundUserId);
        Inbound in = this.getById(iu.getInboundId());
        if (in != null) {
            pushNodeSingbox(in.getNodeId());
        }
        return R.ok();
    }

    /**
     * 停用 / 恢复某个车友的一条线路(机器 × 落地)。
     *
     * 停用不删数据:该线路的转发停掉、订阅里不再出现,流量和到期原样留着,
     * 想恢复就恢复。车主临时不想给某人用某台机器时用这个 —— 比删掉再重新
     * 分配安全得多,重分配会换掉 UUID 和端口,等于让对方重新导一次订阅。
     *
     * 注意和「账号总闸」的区别:User.status 一关是这个人所有线路一起停,
     * 这里只动一条。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R setLineStatus(Long userId, Long nodeId, Long landingId, Integer status) {
        if (userId == null || nodeId == null || status == null) {
            return R.err("参数不全");
        }
        InboundLine line = getLine(userId, nodeId, landingId);
        if (line == null) {
            return R.err("线路不存在");
        }
        line.setStatus(status);
        inboundLineMapper.updateById(line);

        // 转发跟着一起停/起:光改线路标记的话订阅里是没了,但对方手上已经导入的
        // 旧链接还能连 —— 端口还开着,gost 照转不误。
        for (InboundUser iu : lineInboundUsers(userId, nodeId, landingId)) {
            if (iu.getGostForwardId() == null) {
                continue;
            }
            try {
                if (status != null && status == 0) {
                    forwardService.pauseForward(iu.getGostForwardId());
                } else {
                    forwardService.resumeForward(iu.getGostForwardId());
                }
            } catch (Exception e) {
                log.warn("线路[" + userId + "/" + nodeId + "/" + landingId + "] 转发["
                        + iu.getGostForwardId() + "] 状态切换失败: " + e.getMessage());
            }
        }
        return R.ok();
    }

    /**
     * 彻底收回某个车友的一条线路:删掉该线路下所有协议的分配记录和对应转发,
     * 端口一并释放。和停用不同,这个不可逆 —— 之后要再给他用,得重新分配,
     * UUID 和端口都会是新的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteLine(Long userId, Long nodeId, Long landingId) {
        if (userId == null || nodeId == null) {
            return R.err("参数不全");
        }
        List<InboundUser> ius = lineInboundUsers(userId, nodeId, landingId);
        if (ius.isEmpty()) {
            return R.err("线路不存在");
        }
        for (InboundUser iu : ius) {
            if (iu.getGostForwardId() != null) {
                try {
                    forwardService.deleteForward(iu.getGostForwardId());
                } catch (Exception e) {
                    // 转发可能早就被手工删了。这里不能中断:剩下的分配记录不清掉的话,
                    // 线路会半死不活地卡在订阅里。
                    log.warn("删除转发[" + iu.getGostForwardId() + "]失败(继续清理): " + e.getMessage());
                }
            }
            inboundUserMapper.deleteById(iu.getId());
        }
        InboundLine line = getLine(userId, nodeId, landingId);
        if (line != null) {
            inboundLineMapper.deleteById(line.getId());
        }
        pushNodeSingbox(nodeId);
        return R.ok();
    }

    /** 给协议改显示名:只写 Inbound.remark。订阅链接按需从 remark 生成,不必重推 sing-box。 */
    @Override
    public R renameInbound(Long id, String remark) {
        if (id == null) {
            return R.err("参数不全");
        }
        Inbound in = this.getById(id);
        if (in == null) {
            return R.err("协议不存在");
        }
        in.setRemark(remark == null ? "" : remark.trim());
        this.updateById(in);
        return R.ok();
    }

    /** 该车友在这条线路(机器 × 落地)下的所有协议分配记录 */
    private List<InboundUser> lineInboundUsers(Long userId, Long nodeId, Long landingId) {
        List<InboundUser> all = inboundUserMapper.selectList(
                new QueryWrapper<InboundUser>().eq("user_id", userId));
        List<InboundUser> hit = new java.util.ArrayList<>();
        for (InboundUser iu : all) {
            Inbound in = this.getById(iu.getInboundId());
            if (in == null || !nodeId.equals(in.getNodeId())) {
                continue;
            }
            Long lid = in.getLandingId();
            boolean same = (landingId == null) ? (lid == null) : landingId.equals(lid);
            if (same) {
                hit.add(iu);
            }
        }
        return hit;
    }

    // -------- helpers --------

    /** 汇总该节点所有入站 + 各入站用户,推 sing-box 配置到节点 */
    private R pushNodeSingbox(Long nodeId) {
        List<Inbound> inbounds = this.list(new QueryWrapper<Inbound>().eq("node_id", nodeId));
        Map<Long, List<InboundUser>> usersByInbound = new HashMap<>();
        Map<Long, String> landingOutbounds = new HashMap<>();
        for (Inbound in : inbounds) {
            usersByInbound.put(in.getId(),
                    inboundUserMapper.selectList(new QueryWrapper<InboundUser>().eq("inbound_id", in.getId())));
            // 中转:收集该入站用到的落地出站(去重,一条落地查一次)
            Long lid = in.getLandingId();
            if (lid != null && !landingOutbounds.containsKey(lid)) {
                com.admin.entity.Landing landing = landingMapper.selectById(lid);
                if (landing != null && landing.getOutboundJson() != null) {
                    landingOutbounds.put(lid, landing.getOutboundJson());
                }
            }
        }
        GostDto r = SingboxUtil.SetSingboxConfig(nodeId, inbounds, usersByInbound, landingOutbounds, null);
        if (r == null || !"OK".equals(r.getMsg())) {
            return R.err("下发 sing-box 配置失败:" + (r != null && r.getMsg() != null ? r.getMsg() : "节点无响应/超时"));
        }
        return R.ok();
    }

    /** 给 hybrid 转发分配高段公网口(20000-39999),避开被占的低端口 + sing-box 段(40000+) */
    private Integer allocateHybridPort(Long nodeId) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        List<Tunnel> tunnels = tunnelMapper.selectList(new QueryWrapper<Tunnel>().eq("in_node_id", nodeId));
        if (tunnels != null && !tunnels.isEmpty()) {
            java.util.List<Long> tids = new java.util.ArrayList<>();
            for (Tunnel t : tunnels) {
                tids.add(t.getId());
            }
            List<Forward> fs = forwardMapper.selectList(new QueryWrapper<Forward>().in("tunnel_id", tids));
            for (Forward f : fs) {
                if (f.getInPort() != null) {
                    used.add(f.getInPort());
                }
            }
        }
        for (int p = 20000; p <= 39999; p++) {
            if (!used.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 车友专属限速器名(每车友唯一)。避开规则 id 的小数字段(900000000 偏移),用于 gost 服务级 `$` 限速。
     * gost 的 UDP 转发(Hy2/TUIC)只认 `$`、不认 per-IP;每车友独立一个限速器 → TCP+UDP 都限住、车友间互不影响。
     */
    private Integer perUserLimiterName(Long userId) {
        // 基数与 CheckGostConfigAsync.PER_USER_LIMITER_BASE 共用:
        // 那边靠这个基数放行,不把这类限速器当孤儿删掉(删了就等于没限速)
        return (int) (com.admin.common.task.CheckGostConfigAsync.PER_USER_LIMITER_BASE + userId);
    }

    /**
     * 建 hybrid 转发,端口被占(DB 里占了 / 或节点 OS 层被残留服务占了)就自动往上找下一个可用,直到成功。
     * createForwardForUser 失败时会自己 removeById 清理,所以重试很干净。
     */
    /** 一次分配里最多试几个端口。每试一个都是一次节点往返(最多 10 秒),
     *  不设上限的话最坏要试两万次 —— 用户看到的就是面板卡死、CPU 打满。 */
    private static final int MAX_PORT_TRIES = 25;

    private R createForwardAutoPort(ForwardDto fdto, User user, Long nodeId) {
        return createForwardAutoPort(fdto, user, nodeId, new java.util.HashSet<>());
    }

    /**
     * 自动挑端口建转发。
     *
     * knownBusy:本次分配过程中已经确认「机器上被别的程序占了」的端口。
     * allocateHybridPort 只查得到数据库里的占用,查不到 OS 层的,所以那些端口
     * 每个协议都会重新踩一遍 —— 一台机 6 个协议就是 6 倍的无效往返。
     * 把踩过的坑记下来传给后面的协议,同一个端口只吃一次亏。
     */
    private R createForwardAutoPort(ForwardDto fdto, User user, Long nodeId, java.util.Set<Integer> knownBusy) {
        Integer start = allocateHybridPort(nodeId);
        int from = (start != null) ? start : 20000;
        int tried = 0;
        int lastTried = from;
        for (int p = from; p <= 39999 && tried < MAX_PORT_TRIES; p++) {
            if (knownBusy.contains(p)) {
                continue; // 本次分配里已经确认被占,不用再问节点一次
            }
            tried++;
            lastTried = p;
            fdto.setInPort(p);
            R fr = forwardService.createForwardForUser(fdto, user.getId().intValue(), user.getUser());
            if (fr.getCode() == 0 && fr.getData() != null) {
                return fr;
            }
            String msg = fr.getMsg() == null ? "" : fr.getMsg();
            // 端口被占(DB 已用 / OS 已用 / 不在范围)→ 记下来换下一个;其他错误直接返回
            if (msg.contains("already in use") || msg.contains("已被占用") || msg.contains("不在允许范围")) {
                knownBusy.add(p);
                continue;
            }
            return fr;
        }
        return R.err("连续试了 " + tried + " 个端口(" + from + "-" + lastTried
                + ")都被占用,分配中止。请检查这台机器上是否有其它程序占着这段端口,"
                + "或在转发机设置里把端口范围调开。");
    }

    /** 确保节点有一条端口转发隧道(入口机=该节点),没有则建 */
    private Tunnel ensurePortForwardTunnel(Long nodeId) {
        String name = "inbound-tunnel-node" + nodeId;
        // 【为什么要按名字认,而不是"这台机上随便哪条端口转发隧道"】
        // 原来的条件是 in_node_id=nodeId AND type=1 limit 1 —— 只要这台机上有任何一条端口转发
        // 隧道就拿来用,包括用户自己手建的那条。于是协议/车友的转发全被塞进用户的隧道里。
        // 用户哪天把那条隧道连带转发一起删了,这些转发跟着没,inbound_user 就变成
        // 指向不存在转发的孤儿:链接空掉、订阅空掉,而「一键全协议」看到记录还在只会跳过 ——
        // 表现就是"协议都在,但一条链接都出不来",而且怎么点都修不回来。
        // 三月就是这么踩的:隧道页那条手建的「香港端口转发」里躺着 12 条协议/车友转发。
        Tunnel tunnel = tunnelMapper.selectOne(new QueryWrapper<Tunnel>()
                .eq("in_node_id", nodeId).eq("type", TUNNEL_TYPE_PORT_FORWARD)
                .eq("name", name).last("limit 1"));
        if (tunnel != null) {
            return tunnel;
        }
        TunnelDto tdto = new TunnelDto();
        tdto.setName(name);
        tdto.setInNodeId(nodeId);
        tdto.setType(TUNNEL_TYPE_PORT_FORWARD);
        tdto.setFlow(1);
        tdto.setTrafficRatio(BigDecimal.ONE);
        tdto.setProtocol("tls");
        R r = tunnelService.createTunnel(tdto);
        if (r.getCode() != 0) {
            return null;
        }
        return tunnelMapper.selectOne(new QueryWrapper<Tunnel>()
                .eq("in_node_id", nodeId).eq("type", TUNNEL_TYPE_PORT_FORWARD).last("limit 1"));
    }

    /** 分配 sing-box 本机监听口(40000+,避开 gost 公网口段) */
    private Integer allocateListenPort(Long nodeId) {
        List<Inbound> inbounds = this.list(new QueryWrapper<Inbound>().eq("node_id", nodeId));
        int max = SINGBOX_LISTEN_BASE - 1;
        for (Inbound in : inbounds) {
            if (in.getListenPort() != null && in.getListenPort() > max) {
                max = in.getListenPort();
            }
        }
        return max + 1;
    }

    /** 随机 8 位十六进制 shortId */
    private String randomShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
