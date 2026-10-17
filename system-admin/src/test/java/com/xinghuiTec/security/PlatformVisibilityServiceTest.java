package com.xinghuiTec.security;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.domain.vo.UserInfoVO;
import com.xinghuiTec.service.impl.LoginServiceImpl;
import com.xinghuiTec.utils.RedisCacheUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformVisibilityServiceTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantNeverReceivesMisassignedPlatformCodesOrCachedRoutes() {
        authenticate("000001");
        var visibility = new PlatformVisibilityService(new PlatformAccessService(true));
        var rawInfo = new UserInfoVO(new SysUser(), List.of(), List.of(
                "system:user:list", "system:config:list", "monitor:job:run",
                "monitor:operlog:clean", "system:tenant:list",
                "system:dict:list", "system:dict:item:edit",
                "system:notificationTemplate:list", "system:notificationTemplate:edit",
                "ai:model:list", "ai:model:query", "ai:prompt:list", "ai:prompt:add",
                "system:news:list", "system:businessConfig:query", "system:businessConfig:edit",
                "lowcode:form:list", "lowcode:form:publish",
                "lowcode:record:list", "lowcode:record:add"));
        var normal = menu(101L, "system:user:list", "C");
        var config = menu(103L, "system:config:list", "C");
        var dictionary = menu(104L, "system:dict:list", "C");
        var notificationTemplate = menu(105L, "system:notificationTemplate:list", "C");
        var aiModel = menu(106L, "ai:model:list", "C");
        var aiPrompt = menu(107L, "ai:prompt:list", "C");
        var ai = menu(4L, null, "M");
        ai.setChildren(List.of(aiModel, aiPrompt));
        var monitor = menu(2L, null, "M");
        monitor.setChildren(List.of(menu(200L, "monitor:job:list", "C"),
                menu(202L, "monitor:operlog:list", "C")));
        var lowcode = menu(7160L, null, "M");
        lowcode.setChildren(List.of(menu(7161L, "lowcode:form:list", "C"),
                menu(7162L, "lowcode:record:list", "C")));
        List<SysMenuVO> rawMenus = List.of(normal, config, dictionary, notificationTemplate, ai, monitor,
                menu(306L, "system:news:list", "C"),
                menu(307L, "system:businessConfig:query", "C"), lowcode);

        var cache = mock(RedisCacheUtils.class);
        when(cache.getCacheObject(anyString())).thenReturn(rawInfo);
        when(cache.<SysMenuVO>getCacheList(anyString())).thenReturn(rawMenus);
        var loginService = new LoginServiceImpl();
        ReflectionTestUtils.setField(loginService, "redisCacheUtils", cache);
        ReflectionTestUtils.setField(loginService, "platformVisibilityService", visibility);

        var visibleInfo = loginService.getUserInfo(42L);
        var visibleMenus = loginService.getUserRouter(42L);
        assertEquals(List.of("system:user:list", "lowcode:record:list", "lowcode:record:add"),
                visibleInfo.getPermissions());
        assertEquals(List.of(normal.getMenuId(), lowcode.getMenuId()),
                visibleMenus.stream().map(SysMenuVO::getMenuId).toList());
        assertEquals(20, rawInfo.getPermissions().size());
        assertEquals(9, rawMenus.size());
        assertEquals(2, lowcode.getChildren().size(), "filtering must not mutate cached form menus");
        assertEquals(List.of(7162L), visibleMenus.get(1).getChildren().stream()
                .map(SysMenuVO::getMenuId).toList());
    }

    @Test
    void tenantCannotReceiveAnyNewAiManagementGroupOrAction() {
        authenticate("000001");
        var visibility = new PlatformVisibilityService(new PlatformAccessService(true));
        var codes = List.of("ai:config:list", "ai:application:publish", "ai:template:edit", "ai:data:approve", "ai:request:export");
        var directory = menu(202610160180000001L, null, "M");
        directory.setChildren(codes.stream().map(code -> menu(106L, code, "C")).toList());
        assertTrue(visibility.visibleInfo(new UserInfoVO(new SysUser(), List.of(), codes)).getPermissions().isEmpty());
        assertTrue(visibility.visibleMenus(List.of(directory)).isEmpty());
    }

    @Test
    void tenantRetainsSceneInvocationButNeverSceneAdministrationOrMonitorNavigation() {
        authenticate("000001");
        var visibility = new PlatformVisibilityService(new PlatformAccessService(true));
        var codes = List.of("ai:scene:invoke", "ai:scene:list", "ai:scene:query", "ai:scene:edit",
                "ai:scene:futureManagement", "ai:monitor:enter", "lowcode:record:query");
        assertEquals(List.of("ai:scene:invoke", "lowcode:record:query"),
                visibility.visibleInfo(new UserInfoVO(new SysUser(), List.of(), codes)).getPermissions());
        var directory = menu(202610160180000001L, null, "M");
        directory.setChildren(List.of(menu(107L, "ai:scene:list", "C"),
                menu(202610160180000003L, "ai:monitor:enter", "C")));
        assertTrue(visibility.visibleMenus(List.of(directory)).isEmpty());
        assertEquals(2, directory.getChildren().size(), "cached menu input must remain unchanged");
    }

    @Test
    void platformAndNonTenantAuthorizedUserKeepMenus() {
        var menus = List.of(menu(103L, "system:config:list", "C"));
        authenticate("000000");
        assertSame(menus, new PlatformVisibilityService(
                new PlatformAccessService(true)).visibleMenus(menus));
        authenticate("000001");
        assertSame(menus, new PlatformVisibilityService(
                new PlatformAccessService(false)).visibleMenus(menus));
    }

    private static SysMenuVO menu(Long id, String permission, String type) {
        var menu = new SysMenuVO();
        menu.setMenuId(id);
        menu.setPerms(permission);
        menu.setMenuType(type);
        return menu;
    }

    private static void authenticate(String tenant) {
        var user = new SysUser();
        user.setUserId(42L);
        user.setTenantId(tenant);
        var loginUser = new LoginUser(user, List.of());
        loginUser.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
