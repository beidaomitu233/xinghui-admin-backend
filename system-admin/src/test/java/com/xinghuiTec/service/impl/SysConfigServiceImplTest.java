package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.entity.SysConfig;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.RedisCacheUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SysConfigServiceImplTest {
    @Test
    void whitelistRejectsUnknownAndInvalidValuesBeforeDatabaseWrite() {
        var service = new SysConfigServiceImpl();
        assertThrows(ServiceException.class, () ->
                service.addManaged(new ConfigChangeRequest("ai.model.apiKey", "secret")));
        assertThrows(ServiceException.class, () ->
                service.addManaged(new ConfigChangeRequest("sys.index.footerText", "<script>")));
        assertThrows(ServiceException.class, () ->
                service.addManaged(new ConfigChangeRequest("sys.index.sideTheme", "theme-dark")));
    }

    @Test
    void updateInvalidatesSharedCacheAndSubsequentReadSeesNewValue() {
        var service = spy(new SysConfigServiceImpl());
        var cache = mock(RedisCacheUtils.class);
        ReflectionTestUtils.setField(service, "redisCacheUtils", cache);
        var row = new SysConfig();
        row.setConfigId(3);
        row.setTenantId("000000");
        row.setConfigKey("sys.index.sideTheme");
        row.setConfigName("侧边栏主题");
        row.setConfigValue("theme-dark");
        doReturn(row).when(service).getOne(any(Wrapper.class));
        doReturn(true).when(service).updateById(any(SysConfig.class));

        service.updateManaged(3, new ConfigChangeRequest("sys.index.sideTheme", "theme-light"));
        verify(cache).deleteObject("global:sys_config:sys.index.sideTheme");
        assertEquals("theme-light", service.getPublishedValue("sys.index.sideTheme"));
        verify(cache).setCacheObject("global:sys_config:sys.index.sideTheme",
                "theme-light", 60, TimeUnit.SECONDS);
    }

    @Test
    void sensitiveLegacyRowCannotBeReadThroughManagedDetail() {
        var service = spy(new SysConfigServiceImpl());
        var row = new SysConfig();
        row.setConfigId(2);
        row.setTenantId("000000");
        row.setConfigKey("sys.user.initPassword");
        row.setConfigValue("legacy-secret");
        doReturn(row).when(service).getOne(any(Wrapper.class));
        assertThrows(ServiceException.class, () -> service.getManaged(2));
        assertThrows(ServiceException.class, () ->
                service.getPublishedValue("sys.user.initPassword"));
    }
}
