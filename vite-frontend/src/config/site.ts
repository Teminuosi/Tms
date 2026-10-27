import { getConfigByName, getConfigs } from '@/api';

export type SiteConfig = typeof siteConfig;

// 缓存相关常量
const CACHE_PREFIX = 'vite_config_';
const VERSION = "1.0.1";
const APP_VERSION = "1.0.1";

const getInitialConfig = () => {
  if (typeof window === 'undefined') {
    return {
      name: "TMS",
      version: VERSION,
      app_version: APP_VERSION,
    };
  }

  const cachedAppName = localStorage.getItem(CACHE_PREFIX + 'app_name');
    if (cachedAppName) {
      return {
        name: cachedAppName,
        version: VERSION,
        app_version: APP_VERSION,
      };
    }
  return {
    name: "TMS",
    version: VERSION,
    app_version: APP_VERSION,
  };
};

export const siteConfig = getInitialConfig();

// 管理员菜单「客户端下载」:指向博客上的固定页面,不写死安装包地址。
// 面板不会自动更新(要面板主点「更新面板」),写死的话以后换版本 / 换地址,旧面板上的链接全失效;
// 博客页面按云端配置的最新版本拼 GitHub 下载地址,发版后自动跟上。
export const CLIENT_DOWNLOAD_URL = 'https://3yuedaohang.com/tms-download.html?ref=panel';

// 缓存工具函数
export const configCache = {
  // 获取缓存的配置
  get: (key: string): string | null => {
    const cacheKey = CACHE_PREFIX + key;
      return localStorage.getItem(cacheKey);
  },

  // 设置缓存的配置
  set: (key: string, value: string): void => {
    const cacheKey = CACHE_PREFIX + key;
      localStorage.setItem(cacheKey, value);
  },

  // 删除指定配置的缓存
  remove: (key: string): void => {
    const cacheKey = CACHE_PREFIX + key;
    localStorage.removeItem(cacheKey);
  },

  // 清空所有配置缓存
  clear: (): void => {
   // 获取所有localStorage的key
   const keys = Object.keys(localStorage);
   keys.forEach(key => {
     if (key.startsWith(CACHE_PREFIX)) {
       localStorage.removeItem(key);
     }
   });
  }
};

// 获取单个配置（优先从缓存）
export const getCachedConfig = async (key: string): Promise<string | null> => {
  const cachedValue = configCache.get(key);
  if (cachedValue !== null) {
    return cachedValue;
  }

  const response = await getConfigByName(key);
  if (response.code === 0 && response.data?.value) {
    const value = response.data.value;
    configCache.set(key, value);
    return value;
  }

  return null;
};

// 获取所有配置（优先从缓存）
export const getCachedConfigs = async (): Promise<Record<string, string>> => {
  // 尝试从缓存获取所有配置
  const configKeys = ['app_name'];
  const cachedConfigs: Record<string, string> = {};
  let hasCachedData = false;

  configKeys.forEach(key => {
    const cachedValue = configCache.get(key);
    if (cachedValue !== null) {
      cachedConfigs[key] = cachedValue;
      hasCachedData = true;
    }
  });



  // 从API获取最新配置
  try {
    const response = await getConfigs();
    if (response.code === 0 && response.data) {
      const configs = response.data;
      // 将所有配置存入缓存
      Object.entries(configs).forEach(([key, value]) => {
        configCache.set(key, value as string);
      });
      return configs;
    }
  } catch (error) {
    // API失败时返回缓存的数据
    if (hasCachedData) {
      return cachedConfigs;
    }
  }

  return {};
};

/** 面板名变化时广播,让已渲染的头部/侧栏跟着刷新 */
export const SITE_CONFIG_UPDATED = 'site-config-updated';

/**
 * 动态更新网站配置(面板名)。
 * 【必须直接问后端,不能走 getCachedConfig】——它只要本地有缓存就永远不再请求,
 * 结果是:管理员改了名字只清掉自己浏览器的缓存,其它人(车友)那台一直显示旧名字。
 * 这里采用"先用缓存渲染、后台再校验刷新"的做法。
 */
export const updateSiteConfig = async () => {
  try {
    const response = await getConfigByName('app_name');
    const fresh = response.code === 0 ? response.data?.value : null;
    if (fresh) {
      configCache.set('app_name', fresh);
      if (fresh !== siteConfig.name) {
        siteConfig.name = fresh;
        document.title = fresh;
        window.dispatchEvent(new Event(SITE_CONFIG_UPDATED));
      }
    }
  } catch (error) {
    // 网络失败就先用缓存里的值,下次再校验
  }
};

// 清除配置缓存的工具函数
// 缓存清除时机：
// 1. 配置更新时：调用此函数清除所有缓存
// 2. 退出登录时：safeLogout()中的localStorage.clear()会自动清除
export const clearConfigCache = (keys?: string[]) => {
  if (keys && keys.length > 0) {
    // 删除指定的配置缓存
    keys.forEach(key => configCache.remove(key));
  } else {
    // 清空所有配置缓存
    configCache.clear();
  }
};

// 在页面加载时异步更新配置（如果有更新的话）
if (typeof window !== 'undefined') {
  // 延迟执行，避免阻塞初始渲染
  setTimeout(() => {
    updateSiteConfig();
  }, 200);
}
