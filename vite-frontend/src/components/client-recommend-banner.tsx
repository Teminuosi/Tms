import { useState } from "react";

import { CLIENT_DOWNLOAD_URL } from "@/config/site";
import { isAdmin } from "@/utils/auth";

// 关掉后记在浏览器里,不再显示
const DISMISS_KEY = "tms_client_banner_dismissed";

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISS_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * 管理页顶部的「推荐使用 TMS 客户端」提示条。
 * 只给管理员看;车友(子账号)什么都不渲染,页面和原来完全一样。
 */
export default function ClientRecommendBanner() {
  const [dismissed, setDismissed] = useState<boolean>(readDismissed);

  if (dismissed || !isAdmin()) return null;

  const close = () => {
    setDismissed(true);
    try {
      localStorage.setItem(DISMISS_KEY, "1");
    } catch {
      // 存不了就只在本次关掉
    }
  };

  return (
    <div className="mx-4 mt-3 lg:mx-6 flex items-start gap-3 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-950/40 dark:text-blue-200">
      <span className="flex-1 leading-6">
        推荐使用 TMS 客户端管理:常用功能都在客户端里,更省事。
        <a
          className="ml-2 font-medium underline underline-offset-2 hover:opacity-80"
          href={CLIENT_DOWNLOAD_URL}
          rel="noopener noreferrer"
          target="_blank"
        >
          下载 TMS 客户端
        </a>
      </span>
      <button
        aria-label="关闭提示"
        className="shrink-0 leading-6 px-1 text-blue-500 hover:text-blue-700 dark:text-blue-300 dark:hover:text-blue-100"
        type="button"
        onClick={close}
      >
        ✕
      </button>
    </div>
  );
}
