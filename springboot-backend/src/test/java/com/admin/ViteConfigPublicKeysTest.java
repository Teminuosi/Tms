package com.admin;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.controller.BaseController;
import com.admin.controller.ViteConfigController;
import com.admin.entity.User;
import com.admin.service.ViteConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/v1/config/get 免登录,但未登录 / 非管理员只能读白名单里的键 —— vite_config.ip 存着面板的公网 IP,
 * 原来任何人一个请求就能拿到。管理员照旧什么键都能读(网页「网站配置」页要用)。
 */
class ViteConfigPublicKeysTest {

    static ViteConfigController ctl;
    static ViteConfigService svc;
    static Object oldSecret;

    @BeforeAll
    static void setUp() throws Exception {
        Field f = JwtUtil.class.getDeclaredField("SECRET_KEY");
        f.setAccessible(true);
        oldSecret = f.get(null);
        f.set(null, "unit-test-secret-key-0123456789abcdef");
        svc = Mockito.mock(ViteConfigService.class);
        Mockito.when(svc.getConfigByName(Mockito.anyString())).thenAnswer((inv) -> R.ok("value-of-" + inv.getArgument(0)));
        ctl = new ViteConfigController();
        Field s = BaseController.class.getDeclaredField("viteConfigService");
        s.setAccessible(true);
        s.set(ctl, svc);
    }

    // 测完把密钥还原,别影响排在后面、靠 Spring 注入密钥的测试
    @AfterAll
    static void tearDown() throws Exception {
        Field f = JwtUtil.class.getDeclaredField("SECRET_KEY");
        f.setAccessible(true);
        f.set(null, oldSecret);
    }

    static String token(int roleId) {
        User u = new User();
        u.setId(roleId == 0 ? 1L : 7L);
        u.setUser(roleId == 0 ? "admin_user" : "rider");
        u.setRoleId(roleId);
        return JwtUtil.generateToken(u);
    }

    static R get(String name, String auth) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (auth != null) req.addHeader("Authorization", auth);
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        return ctl.getConfigByName(p, req);
    }

    static boolean denied(R r) { return r.getCode() != 0 && "配置不存在".equals(r.getMsg()); }

    @Test
    void anonymousCanReadLoginPageKeysOnly() {
        assertFalse(denied(get("app_name", null)));
        assertFalse(denied(get("captcha_enabled", null)));
        assertFalse(denied(get("captcha_type", null)));
        assertFalse(denied(get("sub_url", null)));
        assertTrue(denied(get("ip", null)), "面板 IP 不能给未登录的人");
        assertTrue(denied(get("anything_else", null)));
    }

    @Test
    void riderTokenIsNotAdmin() {
        assertTrue(denied(get("ip", token(1))));
        assertFalse(denied(get("app_name", token(1))));
    }

    @Test
    void adminReadsEverything() {
        assertFalse(denied(get("ip", token(0))));
    }

    @Test
    void forgedOrBrokenTokensAreNotAdmin() {
        String real = token(1);
        String[] parts = real.split("\\.");
        // 把载荷里的 role_id 改成 0 但签名不变 → 验签失败
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"role_id\":1", "\"role_id\":0");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
        assertTrue(denied(get("ip", forged)));
        assertTrue(denied(get("ip", "garbage")));
        assertTrue(denied(get("ip", "a.b")));
        assertTrue(denied(get("ip", "")));
    }

    @Test
    void missingNameDoesNotThrow() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        R r = ctl.getConfigByName(new HashMap<>(), req);
        assertNotEquals(0, r.getCode());
    }
}
