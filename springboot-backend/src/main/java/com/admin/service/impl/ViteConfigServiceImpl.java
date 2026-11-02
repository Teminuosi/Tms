package com.admin.service.impl;

import com.admin.entity.ViteConfig;
import com.admin.mapper.ViteConfigMapper;
import com.admin.service.ViteConfigService;
import com.admin.common.lang.R;
import com.admin.common.utils.SubUrlRule;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  网站配置服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-07-24
 */
@Service
public class ViteConfigServiceImpl extends ServiceImpl<ViteConfigMapper, ViteConfig> implements ViteConfigService {

    // ========== 常量定义 ==========
    
    /** 成功响应消息 */
    private static final String SUCCESS_UPDATE_MSG = "配置更新成功";
    
    /** 错误响应消息 */
    private static final String ERROR_UPDATE_MSG = "配置更新失败";
    private static final String ERROR_CONFIG_NOT_FOUND = "配置不存在";
    private static final String ERROR_CONFIG_NAME_REQUIRED = "配置名称不能为空";
    private static final String ERROR_CONFIG_VALUE_REQUIRED = "配置值不能为空";

    // ========== 公共接口实现 ==========

    /**
     * 获取所有网站配置
     * 
     * @return 包含所有配置的Map
     */
    @Override
    public R getConfigs() {
        List<ViteConfig> configList = this.list();
        Map<String, String> configMap = new HashMap<>();
        
        for (ViteConfig config : configList) {
            configMap.put(config.getName(), config.getValue());
        }
        
        return R.ok(configMap);
    }

    /**
     * 根据配置名称获取配置值
     * 
     * @param name 配置名称
     * @return 配置响应对象
     */
    @Override
    public R getConfigByName(String name) {
        if (!StringUtils.hasText(name)) {
            return R.err(ERROR_CONFIG_NAME_REQUIRED);
        }

        QueryWrapper<ViteConfig> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("name", name);
        ViteConfig config = this.getOne(queryWrapper);

        if (config == null) {
            return R.err(ERROR_CONFIG_NOT_FOUND);
        }

        return R.ok(config);
    }

    /**
     * 批量更新网站配置
     * 
     * @param configMap 配置Map
     * @return 更新结果响应
     */
    @Override
    public R updateConfigs(Map<String, String> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return R.err("配置数据不能为空");
        }

        // 订阅地址先校验,不合法整批不写(网页「网站配置」是整表一起提交的,别写一半)。
        // 这里允许空 = 清掉(回到用面板地址);其它键一律照旧
        String subUrl = null;
        if (configMap.containsKey(SubUrlRule.KEY)) {
            subUrl = SubUrlRule.normalize(configMap.get(SubUrlRule.KEY));
            if (!subUrl.isEmpty()) {
                String why = SubUrlRule.check(subUrl);
                if (why != null) {
                    return R.err(why);
                }
            }
        }

        try {
            for (Map.Entry<String, String> entry : configMap.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();

                if (!StringUtils.hasText(name)) {
                    continue; // 跳过无效的配置名
                }
                if (SubUrlRule.KEY.equals(name)) {
                    value = subUrl;
                }

                updateOrCreateConfig(name, value);
            }
            return R.ok(SUCCESS_UPDATE_MSG);
        } catch (Exception e) {
            return R.err(ERROR_UPDATE_MSG + ": " + e.getMessage());
        }
    }

    /**
     * 更新单个配置项
     * 
     * @param name 配置名
     * @param value 配置值
     * @return 更新结果响应
     */
    @Override
    public R updateConfig(String name, String value) {
        // 1. 验证必填字段
        if (!StringUtils.hasText(name)) {
            return R.err(ERROR_CONFIG_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(value)) {
            return R.err(ERROR_CONFIG_VALUE_REQUIRED);
        }
        if (SubUrlRule.KEY.equals(name)) {
            value = SubUrlRule.normalize(value);
            String why = SubUrlRule.check(value);
            if (why != null) {
                return R.err(why);
            }
        }

        try {
            updateOrCreateConfig(name, value);
            return R.ok(SUCCESS_UPDATE_MSG);
        } catch (Exception e) {
            return R.err(ERROR_UPDATE_MSG + ": " + e.getMessage());
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 更新或创建配置项
     * 如果配置存在则更新，不存在则创建
     */
    private void updateOrCreateConfig(String name, String value) {
        QueryWrapper<ViteConfig> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("name", name);
        ViteConfig existingConfig = this.getOne(queryWrapper);

        if (existingConfig != null) {
            // 更新现有配置
            existingConfig.setValue(value);
            existingConfig.setTime(System.currentTimeMillis());
            this.updateById(existingConfig);
        } else {
            // 创建新配置
            ViteConfig newConfig = new ViteConfig();
            newConfig.setName(name);
            newConfig.setValue(value);
            newConfig.setTime(System.currentTimeMillis());
            this.save(newConfig);
        }
    }

}
