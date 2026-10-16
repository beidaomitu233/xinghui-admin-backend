package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.entity.SysConfig;
import com.xinghuiTec.domain.vo.ConfigView;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.mapper.SysConfigMapper;
import com.xinghuiTec.service.SysConfigService;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Global, deliberately small parameter catalog. Unknown legacy keys are never exposed. */
@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {
    private static final Logger LOG = LoggerFactory.getLogger(SysConfigServiceImpl.class);
    private record Spec(String name, Set<String> choices, boolean creatable) {}

    private static final Map<String, Spec> CATALOG = Map.of(
            "sys.index.skinName", new Spec("默认皮肤", Set.of("skin-blue", "skin-green", "skin-purple", "skin-red", "skin-yellow"), false),
            "sys.index.sideTheme", new Spec("侧边栏主题", Set.of("theme-dark", "theme-light"), false),
            "sys.account.captchaOnOff", new Spec("验证码配置记录", Set.of("true", "false"), false),
            "sys.account.registerUser", new Spec("用户注册配置记录", Set.of("true", "false"), false),
            "sys.index.footerText", new Spec("页脚文案", Set.of(), true));
    private static final String CACHE_PREFIX = TenantConstants.GLOBAL_REDIS_KEY + "sys_config:";
    private static final String CAPTCHA_KEY = "sys.account.captchaOnOff";
    private static final String CAPTCHA_EFFECTIVE_CACHE = CACHE_PREFIX + "effectiveCaptcha";
    private static final Pattern PLAIN_TEXT = Pattern.compile("[^<>\\p{Cntrl}]{1,100}");

    @Resource
    private RedisCacheUtils redisCacheUtils;

    @Override
    public List<ConfigView> listManaged() {
        return list(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .in(SysConfig::getConfigKey, CATALOG.keySet())
                .orderByAsc(SysConfig::getConfigId)).stream().map(this::view).toList();
    }

    @Override
    public ConfigView getManaged(Integer id) {
        return view(requireManaged(id));
    }

    @Override
    public String getPublishedValue(String key) {
        requireSpec(key);
        String cached = redisCacheUtils.getCacheObject(CACHE_PREFIX + key);
        if (cached != null) {
            return cached;
        }
        SysConfig row = getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .eq(SysConfig::getConfigKey, key));
        if (row == null) {
            throw new ServiceException("参数不存在");
        }
        redisCacheUtils.setCacheObject(CACHE_PREFIX + key, row.getConfigValue(), 60, TimeUnit.SECONDS);
        return row.getConfigValue();
    }

    @Override
    public boolean isCaptchaEnabled(boolean legacyDefault) {
        Boolean cached = redisCacheUtils.getCacheObject(CAPTCHA_EFFECTIVE_CACHE);
        if (cached != null) {
            return cached;
        }
        SysConfig row = getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .eq(SysConfig::getConfigKey, CAPTCHA_KEY));
        if (row == null || !Integer.valueOf(1).equals(row.getRuntimeActive())) {
            return legacyDefault;
        }
        boolean effective = Boolean.parseBoolean(row.getConfigValue());
        redisCacheUtils.setCacheObject(CAPTCHA_EFFECTIVE_CACHE, effective, 60, TimeUnit.SECONDS);
        return effective;
    }

    @Override
    @Transactional
    public Integer addManaged(ConfigChangeRequest request) {
        Spec spec = requireSpec(request.configKey());
        if (!spec.creatable()) {
            throw new ServiceException("此参数仅允许修改现有记录");
        }
        validateValue(request.configKey(), request.configValue());
        SysConfig existing = getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, request.configKey()));
        if (existing != null) {
            throw new ServiceException("参数键已存在");
        }
        SysConfig row = new SysConfig();
        row.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
        row.setConfigKey(request.configKey());
        row.setConfigName(spec.name());
        row.setConfigValue(request.configValue());
        row.setConfigType("N");
        row.setRuntimeActive(1);
        row.setRemark("");
        if (!save(row)) {
            throw new ServiceException("新增参数失败");
        }
        invalidateAfterCommit(request.configKey());
        return row.getConfigId();
    }

    @Override
    @Transactional
    public void updateManaged(Integer id, ConfigChangeRequest request) {
        SysConfig row = requireManaged(id);
        if (!row.getConfigKey().equals(request.configKey())) {
            throw new ServiceException("参数键不可修改");
        }
        validateValue(request.configKey(), request.configValue());
        row.setConfigValue(request.configValue());
        if (CAPTCHA_KEY.equals(row.getConfigKey())) {
            row.setRuntimeActive(1);
        }
        if (!updateById(row)) {
            throw new ServiceException("修改参数失败");
        }
        invalidateAfterCommit(row.getConfigKey());
    }

    @Override
    @Transactional
    public void removeManaged(Integer id) {
        SysConfig row = requireManaged(id);
        Spec spec = requireSpec(row.getConfigKey());
        if (!spec.creatable() || "Y".equals(row.getConfigType())) {
            throw new ServiceException("内置参数不可删除");
        }
        if (baseMapper.deleteOptionalFooter(id) != 1) {
            throw new ServiceException("删除参数失败");
        }
        invalidateAfterCommit(row.getConfigKey());
    }

    private SysConfig requireManaged(Integer id) {
        SysConfig row = getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigId, id)
                .eq(SysConfig::getTenantId, TenantConstants.DEFAULT_TENANT_ID));
        if (row == null || !CATALOG.containsKey(row.getConfigKey())) {
            throw new ServiceException("参数不存在");
        }
        return row;
    }

    private ConfigView view(SysConfig row) {
        Spec spec = requireSpec(row.getConfigKey());
        return new ConfigView(row.getConfigId(), row.getConfigKey(), spec.name(),
                row.getConfigValue(), Integer.valueOf(1).equals(row.getRuntimeActive()),
                spec.creatable() && !"Y".equals(row.getConfigType()));
    }

    private Spec requireSpec(String key) {
        Spec spec = CATALOG.get(key);
        if (spec == null) {
            throw new ServiceException("参数键不在可管理白名单");
        }
        return spec;
    }

    private void validateValue(String key, String value) {
        Spec spec = requireSpec(key);
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new ServiceException("参数值不能为空且不能超过 500 字符");
        }
        if (!spec.choices().isEmpty() && !spec.choices().contains(value)) {
            throw new ServiceException("参数值不在允许范围");
        }
        if (spec.choices().isEmpty() && !PLAIN_TEXT.matcher(value).matches()) {
            throw new ServiceException("页脚文案只允许 1～100 个纯文本字符");
        }
    }

    private void invalidateAfterCommit(String key) {
        Runnable invalidate = () -> {
            try {
                redisCacheUtils.deleteObject(CACHE_PREFIX + key);
                if (CAPTCHA_KEY.equals(key)) {
                    redisCacheUtils.deleteObject(CAPTCHA_EFFECTIVE_CACHE);
                }
            } catch (RuntimeException e) {
                // The DB transaction has committed; the 60-second TTL bounds stale reads.
                LOG.error("系统参数缓存失效失败，key={}", key, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }
}
