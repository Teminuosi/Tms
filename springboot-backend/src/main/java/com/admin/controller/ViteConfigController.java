package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 *  网站配置控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-07-24
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/config")
public class ViteConfigController extends BaseController {

    /**
     * 未登录 / 非管理员能读到的配置项(登录页、头部要用的)。
     * 【为什么要白名单】装机时 vite_config.ip 存的是「面板公网 IP:6365」,原来 /get 免登录、什么键都给,
     * /list 车友登录后也能读 —— 任何人一个请求就能拿到面板的真实 IP(2026-11-01 调研查出)。
     * 只有网页「网站配置」页(管理员)读全部配置。
     */
    static final Set<String> PUBLIC_KEYS = new HashSet<>(Arrays.asList("app_name", "captcha_enabled", "captcha_type", "sub_url"));

    /**
     * 获取所有网站配置:只给管理员(网页里只有「网站配置」页在用)
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R getConfigs() {
        return viteConfigService.getConfigs();
    }

    /**
     * 根据配置名获取配置值。免登录(登录页要读面板名),但非管理员只能读白名单里的键,
     * 其余一律和「配置不存在」同样的回复,不暴露有没有这个键
     */
    @LogAnnotation
    @PostMapping("/get")
    public R getConfigByName(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Object raw = params == null ? null : params.get("name");
        String name = raw == null ? "" : raw.toString();
        if (!PUBLIC_KEYS.contains(name) && !isAdmin(request.getHeader("Authorization"))) {
            return R.err("配置不存在");
        }
        return viteConfigService.getConfigByName(name);
    }

    // 这个接口不过 JWT 拦截器,要自己验签;任何异常都当不是管理员
    static boolean isAdmin(String token) {
        try {
            return JwtUtil.validateToken(token) && Integer.valueOf(0).equals(JwtUtil.getRoleIdFromToken(token));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 批量更新网站配置
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R updateConfigs(@RequestBody Map<String, String> configMap) {
        return viteConfigService.updateConfigs(configMap);
    }

    /**
     * 更新单个配置项
     * 需要管理员权限
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/update-single")
    public R updateConfig(@RequestBody Map<String, Object> params) {
        String name = params.get("name").toString();
        String value = params.get("value").toString();
        return viteConfigService.updateConfig(name, value);
    }

}
