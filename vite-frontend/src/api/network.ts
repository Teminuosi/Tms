import axios, { AxiosResponse } from 'axios';
import { getPanelAddresses, isWebViewFunc} from '@/utils/panel';


interface PanelAddress {
  name: string;
  address: string;   
  inx: boolean;
}

const setPanelAddressesFunc = (newAddress: PanelAddress[]) => {
  newAddress.forEach(item => {
    if (item.inx) {
      baseURL = `${item.address}/api/v1/`;
      axios.defaults.baseURL = baseURL;
    }
  });
}

function getWebViewPanelAddress() {
  (window as any).setAddresses = setPanelAddressesFunc
  getPanelAddresses("setAddresses");
};

let baseURL: string = '';

export const reinitializeBaseURL = () => {
  if (isWebViewFunc()) {
    getWebViewPanelAddress();
  } else {
    baseURL = import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/';
    axios.defaults.baseURL = baseURL;
  }
};

reinitializeBaseURL();


interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

// 处理token失效的逻辑
function handleTokenExpired() {
  // 清除localStorage中的token
  window.localStorage.removeItem('token');
  window.localStorage.removeItem('role_id');
  window.localStorage.removeItem('name');
  
  // 跳转到登录页面
  if (window.location.pathname !== '/') {
    window.location.href = '/';
  }
}

// 检查响应是否为token失效
function isTokenExpired(response: ApiResponse) {
  return response && response.code === 401 && 
         (response.msg === '未登录或token已过期' || 
          response.msg === '无效的token或token已过期' ||
          response.msg === '无法获取用户权限信息');
}

/**
 * 慢接口的超时。
 *
 * 这些接口要跟【节点】来回通信:一台机器 6 个协议,每个协议都要下发 gost 服务、
 * 推限速器,而每次节点往返最多等 10 秒(后端 WebSocketServer.send_msg 的设定)。
 * 节点稍微慢一点,累加起来就轻松超过 30 秒 —— 用户看到的就是
 * "一点分配就卡住,过一会儿报 timeout of 30000ms exceeded"。
 *
 * 这里只放宽超时,不改变任何业务逻辑;真正减少往返次数的优化在后端做。
 */
const SLOW_PATHS = [
  '/inbound/assign-all',
  '/inbound/assign-self',
  '/inbound/assign',
  '/inbound/one-click',
  '/inbound/one-click-relay',
  '/inbound/delete-by-node',
  '/landing/test',
  '/node/install',
  '/node/enable-ipv6',
  '/node/disable-ipv6',
  '/forward/create',
  '/forward/update',
  '/tunnel/diagnose',
  '/forward/diagnose',
];
const DEFAULT_TIMEOUT = 30000;
const SLOW_TIMEOUT = 180000;

function timeoutFor(path: string): number {
  return SLOW_PATHS.some((p) => path.startsWith(p)) ? SLOW_TIMEOUT : DEFAULT_TIMEOUT;
}

const Network = {
  get: function<T = any>(path: string = '', data: any = {}): Promise<ApiResponse<T>> {
    return new Promise(function(resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === '') {
        resolve({"code": -1, "msg": " - 请先设置面板地址", "data": null as T});
        return;
      }

      axios.get(path, {
        params: data,
        timeout: timeoutFor(path),
        headers: {
          "Authorization": window.localStorage.getItem('token')
        }
      })
        .then(function(response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();
            return;
          }
          resolve(response.data);
        })
                 .catch(function(error: any) {
           console.error('GET请求错误:', error);
           
           // 检查是否是401错误（token失效）
           if (error.response && error.response.status === 401) {
             handleTokenExpired();
             return;
           }
           
           resolve({"code": -1, "msg": error.message || "网络请求失败", "data": null as T});
         });
    });
  },

  post: function<T = any>(path: string = '', data: any = {}): Promise<ApiResponse<T>> {
    return new Promise(function(resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === '') {
        resolve({"code": -1, "msg": " - 请先设置面板地址", "data": null as T});
        return;
      }

      axios.post(path, data, {
        timeout: timeoutFor(path),
        headers: {
          "Authorization": window.localStorage.getItem('token'),
          "Content-Type": "application/json"
        }
      })
        .then(function(response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();
            return;
          }
          resolve(response.data);
        })
                 .catch(function(error: any) {
           console.error('POST请求错误:', error);
           
           // 检查是否是401错误（token失效）
           if (error.response && error.response.status === 401) {
             handleTokenExpired();
             return;
           }
           
           resolve({"code": -1, "msg": error.message || "网络请求失败", "data": null as T});
         });
    });
  }
};

export default Network; 