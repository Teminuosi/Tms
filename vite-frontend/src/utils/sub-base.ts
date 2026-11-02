import { getConfigByName } from "@/api";

/**
 * 给车友看 / 复制 / 出二维码的订阅链接用哪个地址开头。
 *
 * 管理员在「网站配置 → 订阅地址(给车友的)」填了(如 https://sub.example.com),就用它;
 * 没填、面板太旧、值不像个地址 → 用当前网页地址(window.location.origin),和以前一模一样。
 *
 * 【必须每次直接问后端,不能走 getCachedConfig】它只要本地有缓存就永远不再请求 ——
 * 管理员改了 / 清了订阅地址,别人浏览器里会一直拿旧的。sub_url 在免登录白名单里,车友也读得到。
 *
 * 【这次没读到 ≠ 没设】网络抖一下就回落网页地址,会把面板地址复制给车友。
 * 所以只有面板明确答复「没设」(配置不存在 / 空值)才用网页地址;读失败时用上次读到的值,从来没读到过才用网页地址。
 *
 * 用法:在拿 token 的同一个异步流程里 await 它,和 token 一起放进状态,
 * 别先用网页地址渲染、再换 —— 中间那一下复制出去的就是面板地址。
 */
const LAST_KEY = "vite_sub_url_last";
let lastKnown: string | null = null;

const readLast = (): string => {
  if (lastKnown !== null) return lastKnown;
  try {
    return window.localStorage.getItem(LAST_KEY) || "";
  } catch {
    return "";
  }
};

const writeLast = (v: string) => {
  lastKnown = v;
  try {
    if (v) window.localStorage.setItem(LAST_KEY, v);
    else window.localStorage.removeItem(LAST_KEY);
  } catch {
    // 存不了就只放内存
  }
};

const usable = (raw: unknown): string => {
  const v = String(raw ?? "")
    .trim()
    .replace(/\/+$/, "");

  return v && /^https?:\/\/[^\s/?#]+/i.test(v) && !/[?#\s]/.test(v) ? v : "";
};

export const getSubBase = async (): Promise<string> => {
  const origin = window.location.origin;

  try {
    const res: any = await getConfigByName("sub_url");

    if (res && res.code === 0) {
      const v = usable(res.data?.value);

      writeLast(v);

      return v || origin;
    }
    if (res && res.msg === "配置不存在") {
      writeLast("");

      return origin;
    }
  } catch {
    // 落到下面:按读失败处理
  }

  return readLast() || origin;
};
