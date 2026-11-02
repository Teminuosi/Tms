package com.admin;

import com.admin.common.lang.R;
import com.admin.common.utils.SubUrlRule;
import com.admin.entity.ViteConfig;
import com.admin.service.impl.ViteConfigServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * 网站配置 sub_url(订阅地址,给车友的):保存时校验 + 规范化;其它键照旧。
 */
class SubUrlRuleTest {

    @Test
    void normalize() {
        assertEquals("https://sub.example.com", SubUrlRule.normalize("  https://sub.example.com/// "));
        assertEquals("", SubUrlRule.normalize(null));
        assertEquals("", SubUrlRule.normalize(" / "));
        assertEquals("https://sub.example.com:8443/p", SubUrlRule.normalize("https://sub.example.com:8443/p/"));
    }

    @Test
    void accepts() {
        for (String s : new String[]{
                "https://sub.example.com", "http://sub.example.com", "HTTPS://Sub.Example.com",
                "https://sub.example.com:8443", "https://sub.example.com/prefix", "https://sub.example.com/a/b",
                "http://203.0.113.5:6365", "https://xn--fiqs8s.example.com", "https://[2001:db8::1]:8443/p",
                "https://sub.example.com/a%20b", "https://sub.example.com/!$&()*+,=", "https://localhost"}) {
            assertNull(SubUrlRule.check(s), s);
        }
    }

    @Test
    void rejects() {
        String[] bad = {
                "", "sub.example.com", "ftp://sub.example.com", "https://", "https:///x",
                "https://sub.example.com?x=1", "https://sub.example.com/#a", "https://sub.example.com/a b",
                "https://sub.example.com\n", "https://u:p@sub.example.com", "https://sub.example.com:0",
                "https://sub.example.com:99999", "https://sub.example.com/\"x", "https://sub.example.com/<x>",
                "javascript:alert(1)", "https://" + "a".repeat(200) + ".com", "https://中文.com",
                "https://sub.example.com/x\\y", "https://sub.example.com/`x`",
                // 显式语法(和客户端 tmsSubUrl.cjs 一致)
                "https://sub_x.example.com", "https://-sub.example.com", "https://sub..example.com", "https://.example.com",
                "https://xn--.com", "http://999.1.1.1", "http://1.2.3", "http://0x7f.1", "https://sub.example.com:",
                "https://sub.example.com/%zz", "https://sub.example.com/{x}", "https://sub.example.com/[x]",
                "https://[fe80::1%25eth0]", "https://a$b.com", "https://" + "a".repeat(64) + ".com",
        };
        for (String s : bad) {
            String why = SubUrlRule.check(s);
            assertNotNull(why, "should reject: " + s);
            assertFalse(why.isEmpty());
        }
        assertTrue(SubUrlRule.check("sub.example.com").contains("http://"));
        assertTrue(SubUrlRule.check("https://a.com?x").contains("?"));
        assertTrue(SubUrlRule.check("https://" + "a".repeat(200) + ".com").contains("200"));
    }

    // ---------- 服务层:update / update-single ----------

    private static ViteConfigServiceImpl svc(List<ViteConfig> saved) {
        ViteConfigServiceImpl s = Mockito.spy(new ViteConfigServiceImpl());
        Mockito.doReturn(null).when(s).getOne(any());
        Mockito.doAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return true;
        }).when(s).save(any(ViteConfig.class));
        return s;
    }

    @Test
    void batchRejectsBadSubUrlAndWritesNothing() {
        List<ViteConfig> saved = new java.util.ArrayList<>();
        ViteConfigServiceImpl s = svc(saved);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app_name", "X");
        m.put("sub_url", "sub.example.com");
        R r = s.updateConfigs(m);
        assertNotEquals(0, r.getCode());
        assertTrue(r.getMsg().contains("http"));
        assertTrue(saved.isEmpty(), "invalid sub_url must not write any key");
    }

    @Test
    void batchNormalizesSubUrlOtherKeysUntouched() {
        List<ViteConfig> saved = new java.util.ArrayList<>();
        ViteConfigServiceImpl s = svc(saved);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app_name", "  My Panel/ ");
        m.put("ip", "203.0.113.5:6365");
        m.put("sub_url", " https://sub.example.com/ ");
        R r = s.updateConfigs(m);
        assertEquals(0, r.getCode());
        Map<String, String> got = new LinkedHashMap<>();
        for (ViteConfig c : saved) got.put(c.getName(), c.getValue());
        assertEquals("  My Panel/ ", got.get("app_name"), "other keys stored as-is");
        assertEquals("203.0.113.5:6365", got.get("ip"));
        assertEquals("https://sub.example.com", got.get("sub_url"));
    }

    @Test
    void batchAllowsClearing() {
        List<ViteConfig> saved = new java.util.ArrayList<>();
        ViteConfigServiceImpl s = svc(saved);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sub_url", "  ");
        assertEquals(0, s.updateConfigs(m).getCode());
        assertEquals("", saved.get(0).getValue());
    }

    @Test
    void batchWithoutSubUrlUnchanged() {
        List<ViteConfig> saved = new java.util.ArrayList<>();
        ViteConfigServiceImpl s = svc(saved);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app_name", "A");
        m.put("captcha_enabled", "false");
        assertEquals(0, s.updateConfigs(m).getCode());
        assertEquals(2, saved.size());
        assertEquals("A", saved.get(0).getValue());
    }

    @Test
    void singleValidates() {
        List<ViteConfig> saved = new java.util.ArrayList<>();
        ViteConfigServiceImpl s = svc(saved);
        R bad = s.updateConfig("sub_url", "https://sub.example.com/?a=1");
        assertNotEquals(0, bad.getCode());
        assertTrue(bad.getMsg().contains("?"));
        assertTrue(saved.isEmpty());

        assertEquals(0, s.updateConfig("sub_url", "https://sub.example.com/").getCode());
        assertEquals("https://sub.example.com", saved.get(0).getValue());

        // 其它键:原样
        assertEquals(0, s.updateConfig("app_name", "no-scheme ok?#").getCode());
        assertEquals("no-scheme ok?#", saved.get(1).getValue());
    }

    @Test
    void singleUpdatesExisting() {
        ViteConfigServiceImpl s = Mockito.spy(new ViteConfigServiceImpl());
        ViteConfig existing = new ViteConfig();
        existing.setName("sub_url");
        existing.setValue("https://old.example.com");
        Mockito.doReturn(existing).when(s).getOne(any());
        Mockito.doReturn(true).when(s).updateById(any(ViteConfig.class));
        assertEquals(0, s.updateConfig("sub_url", "https://new.example.com//").getCode());
        ArgumentCaptor<ViteConfig> cap = ArgumentCaptor.forClass(ViteConfig.class);
        Mockito.verify(s).updateById(cap.capture());
        assertEquals("https://new.example.com", cap.getValue().getValue());
    }
}
