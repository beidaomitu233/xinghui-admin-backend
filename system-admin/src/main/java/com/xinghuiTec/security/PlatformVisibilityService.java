package com.xinghuiTec.security;

import cn.hutool.core.bean.BeanUtil;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.domain.vo.UserInfoVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Shapes the current user's menu and permission response for platform-only resources. */
@Component
public class PlatformVisibilityService {
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "system:config:", "monitor:job:", "monitor:jobLog:",
            "monitor:operlog:", "system:tenant:", "system:tenantPackage:",
            "system:dict:", "system:notificationTemplate:", "ai:model:", "ai:prompt:", "system:wechat:",
            "system:news:", "system:businessConfig:", "lowcode:form:",
            "ai:config:", "ai:application:", "ai:template:", "ai:data:", "ai:request:", "ai:workspace:", "ai:monitor:");

    private final PlatformAccessService platformAccess;

    public PlatformVisibilityService(PlatformAccessService platformAccess) {
        this.platformAccess = platformAccess;
    }

    public UserInfoVO visibleInfo(UserInfoVO info) {
        if (info == null || platformAccess.isPlatformIdentity()) {
            return info;
        }
        List<String> permissions = info.getPermissions() == null ? List.of()
                : info.getPermissions().stream().filter(this::isVisible).toList();
        return new UserInfoVO(info.getUser(), info.getRoles(), permissions);
    }

    public List<SysMenuVO> visibleMenus(List<SysMenuVO> menus) {
        if (menus == null || platformAccess.isPlatformIdentity()) {
            return menus;
        }
        List<SysMenuVO> visible = new ArrayList<>();
        for (SysMenuVO menu : menus) {
            if (!isVisible(menu.getPerms())) {
                continue;
            }
            SysMenuVO copy = BeanUtil.copyProperties(menu, SysMenuVO.class);
            List<SysMenuVO> children = menu.getChildren();
            if (children != null) {
                copy.setChildren(visibleMenus(children));
                if ("M".equals(menu.getMenuType()) && !children.isEmpty()
                        && copy.getChildren().isEmpty()) {
                    continue;
                }
            }
            visible.add(copy);
        }
        return visible;
    }

    private boolean isVisible(String permission) {
        if (permission == null) {
            return true;
        }
        // Calling an approved scene is a business permission. Scene administration remains platform-only,
        // including future management actions accidentally assigned to a tenant role.
        if (permission.startsWith("ai:scene:")) {
            return "ai:scene:invoke".equals(permission);
        }
        return PLATFORM_PREFIXES.stream().noneMatch(permission::startsWith);
    }
}
