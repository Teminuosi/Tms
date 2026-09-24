/**
 * 认出「活已经干了,只差最后一步」的那类后端返回。
 *
 * 【问题出在哪】R 只有两档:code 0 = 成功,其它 = 失败。
 * 可现实里有一大类【半成功】—— 东西已经入库/已经建了几个,只是最后一步没成。
 * 后端没地方放它,只能塞进 R.err,消息里自己写明:
 *
 *   InboundServiceImpl:255  一键添加已入库,但下发 sing-box 配置失败:…
 *   InboundServiceImpl:295  中转已入库,但下发 sing-box 配置失败:…
 *   InboundServiceImpl:248  一键添加中断(tuic):…(已成功 3 个)
 *   LandingController       落地已改,但这些机器的配置没推成功:…
 *
 * 前端一律 toast.error 画红条,而且【不刷新列表、不关弹窗】。后果比红条本身糟:
 * 用户看不到已经建好的那几条,十有八九再点一次 —— 于是重复建、撞端口。
 *
 * 【为什么不改后端返回码】用户的面板版本参差不齐。改前端,老后端也立刻受益;
 * 而且客户端「指挥舱」已经用同一套判据修过了(renderer/src/partial-success.mjs),
 * 两边保持一致,免得同一个返回在两处显示成不同颜色。
 */

// 命中任一即判为半成功。[,,]?\s* —— 中英文逗号都可能出现,后面还可能跟空格。
const PATTERNS: RegExp[] = [
  /已入库[,,]?\s*但/, // 一键添加已入库,但下发 sing-box 配置失败
  /已改[,,]?\s*但/, // 落地已改,但这些机器的配置没推成功
  /已分配[,,]?\s*但/,
  /已保存[,,]?\s*但/,
  /已成功\s*\d+\s*个/, // …中断(tuic):…(已成功 3 个)
  /已建好\s*\d+\s*个/,
];

/** 这条错误消息其实是「半成功」吗? */
export function isPartialSuccess(msg?: string | null): boolean {
  const s = String(msg ?? "");
  if (!s) return false;
  return PATTERNS.some((re) => re.test(s));
}

/**
 * 按返回结果给提示,并告诉调用方「该不该当成功处理」(关弹窗 + 刷列表)。
 *
 * 半成功必须当成功那样收尾 —— 东西真的建出来了,列表不刷新等于让用户对着过期的界面操作。
 * 返回 true = 调用方应当关弹窗并刷新。
 */
export function toastResult(
  res: { code?: number; msg?: string },
  okText: string,
  failPrefix: string,
  toast: {
    success: (m: string) => void;
    error: (m: string) => void;
    (m: string, o?: any): void;
  },
): boolean {
  if (res?.code === 0) {
    toast.success(okText);
    return true;
  }
  const msg = res?.msg || "";
  if (isPartialSuccess(msg)) {
    // 黄条:红色的潜台词是"什么都没发生,重试即可",而事实相反 ——
    // 再点一次只会重复建或者撞端口。后端原话一字不改地保留,
    // 那里写着具体是哪台机器、哪个协议没成。
    toast(`⚠️ 做了一部分:${msg}`, { duration: 8000 });
    return true;
  }
  toast.error(msg || failPrefix);
  return false;
}
