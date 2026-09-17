import Network from './network';

// 登陆相关接口
export interface LoginData {
  username: string;
  password: string;
  captchaId: string;
}

export interface LoginResponse {
  token: string;
  role_id: number;
  name: string;
  requirePasswordChange?: boolean;
}

export const login = (data: LoginData) => Network.post<LoginResponse>("/user/login", data);

// 用户CRUD操作 - 全部使用POST请求
export const createUser = (data: any) => Network.post("/user/create", data);
export const getAllUsers = (pageData: any = {}) => Network.post("/user/list", pageData);
export const updateUser = (data: any) => Network.post("/user/update", data);
export const deleteUser = (id: number) => Network.post("/user/delete", { id });
export const getUserPackageInfo = () => Network.post("/user/package");

// 转发机CRUD操作 - 全部使用POST请求
export const createNode = (data: any) => Network.post("/node/create", data);
export const getNodeList = () => Network.post("/node/list");
export const updateNode = (data: any) => Network.post("/node/update", data);
export const deleteNode = (id: number) => Network.post("/node/delete", { id });
export const getNodeInstallCommand = (id: number) => Network.post("/node/install", { id });
export const checkNodeStatus = (nodeId?: number) => {
  const params = nodeId ? { nodeId } : {};
  return Network.post("/node/check-status", params);
};

// 隧道CRUD操作 - 全部使用POST请求
export const createTunnel = (data: any) => Network.post("/tunnel/create", data);
export const getTunnelList = () => Network.post("/tunnel/list");
export const getTunnelById = (id: number) => Network.post("/tunnel/get", { id });
export const updateTunnel = (data: any) => Network.post("/tunnel/update", data);
export const deleteTunnel = (id: number) => Network.post("/tunnel/delete", { id });
export const diagnoseTunnel = (tunnelId: number) => Network.post("/tunnel/diagnose", { tunnelId });

// 用户隧道权限管理操作 - 全部使用POST请求
export const assignUserTunnel = (data: any) => Network.post("/tunnel/user/assign", data);
export const getUserTunnelList = (queryData: any = {}) => Network.post("/tunnel/user/list", queryData);
export const removeUserTunnel = (params: any) => Network.post("/tunnel/user/remove", params);
export const updateUserTunnel = (data: any) => Network.post("/tunnel/user/update", data);
export const userTunnel = () => Network.post("/tunnel/user/tunnel");

// 转发CRUD操作 - 全部使用POST请求
export const createForward = (data: any) => Network.post("/forward/create", data);
export const getForwardList = () => Network.post("/forward/list");
export const updateForward = (data: any) => Network.post("/forward/update", data);
export const deleteForward = (id: number) => Network.post("/forward/delete", { id });
export const forceDeleteForward = (id: number) => Network.post("/forward/force-delete", { id });

// 转发服务控制操作 - 通过Java后端接口
export const pauseForwardService = (forwardId: number) => Network.post("/forward/pause", { id: forwardId });
export const resumeForwardService = (forwardId: number) => Network.post("/forward/resume", { id: forwardId });

// 转发诊断操作
export const diagnoseForward = (forwardId: number) => Network.post("/forward/diagnose", { forwardId });

// 转发排序操作
export const updateForwardOrder = (data: { forwards: Array<{ id: number; inx: number }> }) => Network.post("/forward/update-order", data);

// 限速规则CRUD操作 - 全部使用POST请求
export const createSpeedLimit = (data: any) => Network.post("/speed-limit/create", data);
export const getSpeedLimitList = () => Network.post("/speed-limit/list");
export const updateSpeedLimit = (data: any) => Network.post("/speed-limit/update", data);
export const deleteSpeedLimit = (id: number) => Network.post("/speed-limit/delete", { id });

// 协议入站(合体面板:协议搭建 + 限速)
export const createInbound = (data: any) => Network.post("/inbound/create", data);
export const oneClickInbound = (nodeId: number, sni?: string) => Network.post("/inbound/one-click", { nodeId, sni });
export const getInboundList = () => Network.post("/inbound/list");
export const deleteInbound = (id: number) => Network.post("/inbound/delete", { id });
export const deleteInboundsByNode = (nodeId: number, relay?: boolean, landingId?: number) => Network.post("/inbound/delete-by-node", { nodeId, relay, landingId });
export const assignInboundUser = (data: any) => Network.post("/inbound/assign", data);
export const assignAllToUser = (data: any) => Network.post("/inbound/assign-all", data);
// 「我自己用」:把这台机器/这条中转的协议开给当前登录的管理员自己(不限速/不限量/不到期)
export const assignSelf = (data: any) => Network.post("/inbound/assign-self", data);
export const unassignInboundUser = (id: number) => Network.post("/inbound/unassign", { id });
// 按【线路】操作(线路 = 车友 × 机器 × 落地;landingId 传 null 表示这台机器的直连)。
// unassignInboundUser 取消的是单个协议,一条线路有六个,挨个点太蠢。
export const setLineStatus = (userId: number, nodeId: number, landingId: number | null, status: number) =>
  Network.post("/inbound/line-status", { userId, nodeId, landingId, status });
export const deleteLine = (userId: number, nodeId: number, landingId: number | null) =>
  Network.post("/inbound/line-delete", { userId, nodeId, landingId });
// 续费:改这条线路的额度/到期/限速。字段给 null = 该项不动。
// 不走「删了重分」是因为重分会换 UUID 和端口,车友手上的订阅会作废。
export const updateLine = (
  userId: number, nodeId: number, landingId: number | null,
  patch: { flow?: number | null; expTime?: number | null; speedId?: number | null },
) => Network.post("/inbound/line-update", { userId, nodeId, landingId, ...patch });
export const getUserSub = (userId: number) => Network.post("/inbound/user-sub", { userId });
// 按库里的全量重新下发这台机器的 sing-box 配置(整份覆盖,重复点没副作用)
export const pushNodeConfig = (nodeId: number) => Network.post("/inbound/push-config", { nodeId });

// 中转(前置机协议 + 落地出口):落地内联粘贴、测试、搭建
export const oneClickRelay = (nodeId: number, link: string, name?: string, sni?: string) => Network.post("/inbound/one-click-relay", { nodeId, link, name, sni });
export const testLanding = (nodeId: number, link: string) => Network.post("/landing/test", { nodeId, link });
export const getLandingList = () => Network.post("/landing/list");
// 落地独立管理(「落地管理」页)。以前只能在搭中转时顺手建一条,建完就再也改不了 ——
// 粉丝原话:「创建完成后落地出口(socks5)配置无法二次更改,只能删掉中转重建,
// 然后又要去指挥舱删不需要的协议」。后端 create/update/delete 一直都在,只是前端没接。
export const createLanding = (data: { name: string; link: string; remark?: string }) =>
  Network.post("/landing/create", data);
// ⚠️ update 成功时后端会把用到这条落地的机器全部重推一遍 sing-box 配置;
// 只要有一台没推上就返回 code!=0,但【库里已经改了】—— 调用方别当成「保存失败」。
export const updateLanding = (data: { id: number; name: string; link: string; remark?: string }) =>
  Network.post("/landing/update", data);
// 被中转协议占用时后端会拒绝并说明占用数量,不做级联删除
export const deleteLanding = (id: number) => Network.post("/landing/delete", { id });

// 订阅按线路(车友×机器):一个车友的所有订阅线路
export const getUserLines = (userId: number) => Network.post("/inbound/user-lines", { userId });
// 车友自助:取我自己的订阅线路(不需要管理员权限)
export const getMyLines = () => Network.post("/inbound/my-lines");

// 版本信息 / 更新检查(后端拿构建时注入的 commit 跟 GitHub main 比)
export const getVersionInfo = () => Network.post("/version/info");

// 修改密码接口
export const updatePassword = (data: any) => Network.post("/user/updatePassword", data);

// 重置流量接口
export const resetUserFlow = (data: { id: number; type: number }) => Network.post("/user/reset", data);

// 网站配置相关接口
export const getConfigs = () => Network.post("/config/list");
export const getConfigByName = (name: string) => Network.post("/config/get", { name });
export const updateConfigs = (configMap: Record<string, string>) => Network.post("/config/update", configMap);
export const updateConfig = (name: string, value: string) => Network.post("/config/update-single", { name, value });


// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) => Network.post("/captcha/verify", data); 