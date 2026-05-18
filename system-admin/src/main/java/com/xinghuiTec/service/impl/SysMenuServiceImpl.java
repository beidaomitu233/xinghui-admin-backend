package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import com.xinghuiTec.domain.dto.SysMenuQueryDTO;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.service.SysMenuService;
import com.xinghuiTec.service.SysRoleMenuService;
import com.xinghuiTec.utils.TreeUtils;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * 菜单权限表(SysMenu)服务实现类
 * 实现菜单管理的核心业务逻辑，包括：
 * 1. 菜单的增删改查
 * 2. 菜单树形结构构建（使用通用TreeUtils工具类）
 * 3. 菜单数据校验
 * 
 * 优化说明：
 * - 使用MyBatis-Plus的LambdaQueryWrapper替代自定义SQL
 * - 使用通用TreeUtils工具类构建树形结构
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@Service
@Slf4j
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    /**
     * 注入角色菜单关联服务
     * 用于检查菜单是否被角色关联
     */
    @Resource
    private SysRoleMenuService sysRoleMenuService;

    /**
     * 注入Redis缓存工具类
     * 用于清除用户路由缓存
     */
    @Resource
    private com.xinghuiTec.utils.RedisCacheUtils redisCacheUtils;

    /**
     * 根据条件查询菜单列表
     * 使用MyBatis-Plus的LambdaQueryWrapper实现动态条件查询
     *
     * @param queryDTO 查询条件DTO
     * @return 菜单列表（按parent_id和order_num排序）
     */
    @Override
    public List<SysMenu> getMenuList(SysMenuQueryDTO queryDTO) {
        // 使用 LambdaQueryWrapper 构建动态查询条件
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();

        // 菜单名称模糊查询（如果不为空）
        wrapper.like(StringUtils.hasText(queryDTO.getMenuName()),
                SysMenu::getMenuName, queryDTO.getMenuName());

        // 菜单状态精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getStatus()),
                SysMenu::getStatus, queryDTO.getStatus());

        // 菜单类型精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getMenuType()),
                SysMenu::getMenuType, queryDTO.getMenuType());

        // 显示状态精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getVisible()),
                SysMenu::getVisible, queryDTO.getVisible());

        // 按照父菜单ID和排序号排序
        wrapper.orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum);

        return this.list(wrapper);
    }

    /**
     * 获取菜单树形结构
     * 使用通用TreeUtils工具类构建树形结构
     *
     * @return 菜单树形结构列表（顶级菜单及其子菜单）
     */
    @Override
    public List<SysMenuVO> getMenuTree(SysMenuQueryDTO queryDTO) {
        // 1. 查询所有正常状态的菜单，按orderNum排序
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();

        // 菜单名称模糊查询（如果不为空）
        wrapper.like(StringUtils.hasText(queryDTO.getMenuName()),
                SysMenu::getMenuName, queryDTO.getMenuName());

        // 菜单状态精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getStatus()),
                SysMenu::getStatus, queryDTO.getStatus());

        // 菜单类型精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getMenuType()),
                SysMenu::getMenuType, queryDTO.getMenuType());

        // 显示状态精确匹配（如果不为空）
        wrapper.eq(StringUtils.hasText(queryDTO.getVisible()),
                SysMenu::getVisible, queryDTO.getVisible());

        // 按照父菜单ID和排序号排序
        wrapper.orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum);

        List<SysMenu> allMenus = this.list(wrapper);

        // 2. 构建树形结构
        return buildMenuTree(allMenus);
    }

    /**
     * 根据菜单ID获取菜单详情
     *
     * @param menuId 菜单ID
     * @return 菜单详情，不存在返回null
     */
    @Override
    public SysMenu getMenuById(Long menuId) {
        // 使用MyBatis-Plus内置方法根据ID查询
        return this.getById(menuId);
    }

    /**
     * 新增菜单
     * 业务逻辑：
     * 1. 校验父菜单是否存在（非顶级菜单时）
     * 2. 校验同一父菜单下菜单名称是否唯一
     * 3. 校验菜单类型与必填字段的匹配性
     * 4. 保存菜单信息
     *
     * @param addDTO 新增菜单请求DTO
     * @return 新增的菜单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addMenu(SysMenuAddDTO addDTO) {
        // 1. 校验父菜单是否存在（parentId不为0时）
        if (addDTO.getParentId() != null && addDTO.getParentId() != 0) {
            SysMenu parentMenu = this.getById(addDTO.getParentId());
            if (parentMenu == null) {
                throw new RuntimeException("父菜单不存在");
            }
            // 父菜单必须是目录(M)或菜单(C)类型，不能是按钮(F)
            if ("F".equals(parentMenu.getMenuType())) {
                throw new RuntimeException("按钮类型不能作为父菜单");
            }
        }

        // 2. 校验同一父菜单下菜单名称是否唯一（使用LambdaQueryWrapper）
        Long parentId = addDTO.getParentId() == null ? 0L : addDTO.getParentId();
        if (checkMenuNameExists(addDTO.getMenuName(), parentId, null)) {
            throw new RuntimeException("同一父菜单下已存在相同名称的菜单");
        }

        // 3. 校验菜单类型与必填字段的匹配性
        validateMenuTypeFields(addDTO);

        // 4. 创建菜单实体并设置属性
        SysMenu menu = new SysMenu();
        BeanUtils.copyProperties(addDTO, menu);

        // 5. 保存菜单到数据库
        boolean saveSuccess = this.save(menu);
        if (!saveSuccess) {
            throw new RuntimeException("菜单保存失败");
        }

        // 6. 清除所有用户的路由缓存
        clearAllUserRouterCache();

        // 7. 返回新增的菜单ID
        return menu.getMenuId();
    }

    /**
     * 修改菜单
     * 业务逻辑：
     * 1. 校验菜单是否存在
     * 2. 校验父菜单不能是自己
     * 3. 校验父菜单不能是自己的子菜单
     * 4. 校验同一父菜单下菜单名称是否唯一（排除自己）
     * 5. 更新菜单信息
     *
     * @param addDTO 修改菜单请求DTO（必须包含menuId）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(SysMenuAddDTO addDTO) {
        // 1. 校验menuId是否存在
        if (addDTO.getMenuId() == null) {
            throw new RuntimeException("菜单ID不能为空");
        }

        // 2. 校验菜单是否存在
        SysMenu existMenu = this.getById(addDTO.getMenuId());
        if (existMenu == null) {
            throw new RuntimeException("菜单不存在");
        }

        // 3. 校验父菜单不能是自己
        if (addDTO.getParentId() != null && addDTO.getMenuId().equals(addDTO.getParentId())) {
            throw new RuntimeException("父菜单不能是自己");
        }

        // 4. 校验父菜单不能是自己的子菜单（防止循环引用）
        if (addDTO.getParentId() != null && addDTO.getParentId() != 0) {
            if (isChildMenu(addDTO.getMenuId(), addDTO.getParentId())) {
                throw new RuntimeException("父菜单不能是当前菜单的子菜单");
            }
            // 校验父菜单是否存在
            SysMenu parentMenu = this.getById(addDTO.getParentId());
            if (parentMenu == null) {
                throw new RuntimeException("父菜单不存在");
            }
            // 父菜单必须是目录(M)或菜单(C)类型
            if ("F".equals(parentMenu.getMenuType())) {
                throw new RuntimeException("按钮类型不能作为父菜单");
            }
        }

        // 5. 校验同一父菜单下菜单名称是否唯一（排除自己）
        Long parentId = addDTO.getParentId() == null ? 0L : addDTO.getParentId();
        if (checkMenuNameExists(addDTO.getMenuName(), parentId, addDTO.getMenuId())) {
            throw new RuntimeException("同一父菜单下已存在相同名称的菜单");
        }

        // 6. 校验菜单类型与必填字段的匹配性
        validateMenuTypeFields(addDTO);

        // 7. 更新菜单信息
        SysMenu updateMenu = new SysMenu();
        BeanUtils.copyProperties(addDTO, updateMenu);

        boolean updateSuccess = this.updateById(updateMenu);
        if (!updateSuccess) {
            throw new RuntimeException("菜单更新失败");
        }

        // 8. 清除所有用户的路由缓存
        clearAllUserRouterCache();
    }

    /**
     * 删除菜单
     * 业务逻辑：
     * 1. 校验菜单是否存在
     * 2. 校验是否有子菜单（有子菜单不能删除）
     * 3. 校验是否被角色关联（被关联不能删除）
     * 4. 删除菜单
     *
     * @param menuId 菜单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long menuId) {
        // 1. 校验菜单ID
        if (menuId == null) {
            throw new RuntimeException("菜单ID不能为空");
        }

        // 2. 校验菜单是否存在
        SysMenu existMenu = this.getById(menuId);
        if (existMenu == null) {
            throw new RuntimeException("菜单不存在");
        }

        // 3. 校验是否有子菜单（使用LambdaQueryWrapper）
        if (hasChildMenu(menuId)) {
            throw new RuntimeException("该菜单下存在子菜单，请先删除子菜单");
        }

        // 4. 校验是否被角色关联
        LambdaQueryWrapper<SysRoleMenu> roleMenuWrapper = new LambdaQueryWrapper<>();
        roleMenuWrapper.eq(SysRoleMenu::getMenuId, menuId);
        long roleCount = sysRoleMenuService.count(roleMenuWrapper);
        if (roleCount > 0) {
            throw new RuntimeException("该菜单已被角色关联，请先解除关联");
        }

        // 5. 删除菜单
        boolean deleteSuccess = this.removeById(menuId);
        if (!deleteSuccess) {
            throw new RuntimeException("菜单删除失败");
        }

        // 6. 清除所有用户的路由缓存
        clearAllUserRouterCache();
    }

    /**
     * 更新菜单排序
     * 使用LambdaUpdateWrapper实现更新
     *
     * @param menuId   菜单ID
     * @param orderNum 新的排序号
     */
    @Override
    public void updateMenuOrder(Long menuId, Integer orderNum) {
        // 1. 校验参数
        if (menuId == null) {
            throw new RuntimeException("菜单ID不能为空");
        }
        if (orderNum == null || orderNum < 0) {
            throw new RuntimeException("排序号不能为空且必须大于等于0");
        }

        // 2. 校验菜单是否存在
        SysMenu existMenu = this.getById(menuId);
        if (existMenu == null) {
            throw new RuntimeException("菜单不存在");
        }

        // 3. 使用LambdaUpdateWrapper更新排序
        LambdaUpdateWrapper<SysMenu> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SysMenu::getMenuId, menuId)
                .set(SysMenu::getOrderNum, orderNum);

        boolean updateSuccess = this.update(updateWrapper);
        if (!updateSuccess) {
            throw new RuntimeException("菜单排序更新失败");
        }
    }

    /**
     * 构建菜单树形结构
     * 使用通用TreeUtils工具类将平铺的菜单列表转换为树形结构
     *
     * @param menuList 平铺的菜单列表
     * @return 树形结构的菜单列表
     */
    @Override
    public List<SysMenuVO> buildMenuTree(List<SysMenu> menuList) {
        if (menuList == null || menuList.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 将SysMenu转换为SysMenuVO
        List<SysMenuVO> menuVOList = menuList.stream().map(menu -> {
            SysMenuVO vo = new SysMenuVO();
            BeanUtils.copyProperties(menu, vo);
            return vo;
        }).collect(Collectors.toList());

        // 2. 使用通用TreeUtils构建树形结构
        return TreeUtils.buildTree(
                menuVOList,
                SysMenuVO::getMenuId, // ID获取器
                SysMenuVO::getParentId, // 父ID获取器
                SysMenuVO::setChildren, // 子节点设置器
                SysMenuVO::getOrderNum // 排序字段获取器
        );
    }

    /**
     * 根据角色ID获取菜单列表
     * 使用LambdaQueryWrapper和关联查询
     *
     * @param roleId 角色ID
     * @return 角色关联的菜单列表
     */
    @Override
    public List<SysMenu> getMenuListByRoleId(Long roleId) {
        // 1. 查询角色关联的菜单ID列表
        LambdaQueryWrapper<SysRoleMenu> roleMenuWrapper = new LambdaQueryWrapper<>();
        roleMenuWrapper.eq(SysRoleMenu::getRoleId, roleId);
        List<SysRoleMenu> roleMenus = sysRoleMenuService.list(roleMenuWrapper);

        if (roleMenus.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 提取菜单ID列表
        List<Long> menuIds = roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .collect(Collectors.toList());

        // 3. 查询菜单详情，只返回正常状态的
        LambdaQueryWrapper<SysMenu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.in(SysMenu::getMenuId, menuIds)
                .eq(SysMenu::getStatus, "0")
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum);

        return this.list(menuWrapper);
    }

    /**
     * 检查菜单是否有子菜单
     * 使用LambdaQueryWrapper替代自定义SQL
     *
     * @param menuId 菜单ID
     * @return true-有子菜单，false-无子菜单
     */
    @Override
    public boolean hasChildMenu(Long menuId) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getParentId, menuId);
        return this.count(wrapper) > 0;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 清除所有用户的路由缓存
     * 当菜单发生变更时，所有用户的路由缓存都可能受影响
     */
    private void clearAllUserRouterCache() {
        String pattern = com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + "*";
        java.util.Collection<String> keys = redisCacheUtils.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisCacheUtils.deleteObject(keys);
            log.info("已清除 {} 个用户的路由缓存", keys.size());
        }
    }

    /**
     * 判断targetId是否是menuId的子菜单（递归检查）
     * 用于防止设置父菜单时产生循环引用
     *
     * @param menuId   当前菜单ID
     * @param targetId 目标菜单ID（要设置为父菜单的ID）
     * @return true-targetId是menuId的子菜单，false-不是
     */
    private boolean isChildMenu(Long menuId, Long targetId) {
        // 获取当前菜单的所有直接子菜单（使用LambdaQueryWrapper）
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getParentId, menuId);
        List<SysMenu> children = this.list(wrapper);

        for (SysMenu child : children) {
            // 如果找到目标菜单，说明目标菜单是当前菜单的子菜单
            if (child.getMenuId().equals(targetId)) {
                return true;
            }
            // 递归检查子菜单的子菜单
            if (isChildMenu(child.getMenuId(), targetId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查菜单名称在同一父菜单下是否已存在
     * 使用LambdaQueryWrapper替代自定义SQL
     *
     * @param menuName  菜单名称
     * @param parentId  父菜单ID
     * @param excludeId 排除的菜单ID（修改时排除自己）
     * @return true-存在重名，false-不存在重名
     */
    private boolean checkMenuNameExists(String menuName, Long parentId, Long excludeId) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getMenuName, menuName)
                .eq(SysMenu::getParentId, parentId);

        // 如果是修改操作，排除自己
        if (excludeId != null) {
            wrapper.ne(SysMenu::getMenuId, excludeId);
        }

        return this.count(wrapper) > 0;
    }

    /**
     * 校验菜单类型与必填字段的匹配性
     * 规则：
     * - 目录(M)：path必填
     * - 菜单(C)：path必填，component必填
     * - 按钮(F)：perms建议设置
     *
     * @param addDTO 菜单DTO
     */
    private void validateMenuTypeFields(SysMenuAddDTO addDTO) {
        String menuType = addDTO.getMenuType();

        if ("M".equals(menuType)) {
            // 目录类型：path必填
            if (!StringUtils.hasText(addDTO.getPath())) {
                throw new RuntimeException("目录类型的路由地址不能为空");
            }
        } else if ("C".equals(menuType)) {
            // 菜单类型：path和component必填
            if (!StringUtils.hasText(addDTO.getPath())) {
                throw new RuntimeException("菜单类型的路由地址不能为空");
            }
            if (!StringUtils.hasText(addDTO.getComponent())) {
                throw new RuntimeException("菜单类型的组件路径不能为空");
            }
        }
        // 按钮类型：perms建议设置，但不强制
    }
}
