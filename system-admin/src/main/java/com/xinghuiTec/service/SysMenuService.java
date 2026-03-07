package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import com.xinghuiTec.domain.dto.SysMenuQueryDTO;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.vo.SysMenuVO;

import java.util.List;

/**
 * 菜单权限表(SysMenu)服务接口层
 * 提供菜单管理的业务接口，包括增删改查和树形结构构建
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
public interface SysMenuService extends IService<SysMenu> {

    /**
     * 根据条件查询菜单列表
     * 支持按菜单名称、状态、类型等条件查询
     *
     * @param queryDTO 查询条件DTO
     * @return 菜单列表（平铺结构，按parent_id和order_num排序）
     */
    List<SysMenu> getMenuList(SysMenuQueryDTO queryDTO);

    /**
     * 获取菜单树形结构
     * 只返回正常状态的菜单，按照层级和orderNum排序
     *
     * @return 菜单树形结构列表
     */
    List<SysMenuVO> getMenuTree(SysMenuQueryDTO queryDTO);

    /**
     * 根据菜单ID获取菜单详情
     *
     * @param menuId 菜单ID
     * @return 菜单详情，不存在返回null
     */
    SysMenu getMenuById(Long menuId);

    /**
     * 新增菜单
     * 会校验：
     * 1. 菜单名称在同一父菜单下是否唯一
     * 2. 父菜单是否存在（parentId不为0时）
     * 3. 菜单类型与必填字段的匹配性
     *
     * @param addDTO 新增菜单请求DTO
     * @return 新增的菜单ID
     */
    Long addMenu(SysMenuAddDTO addDTO);

    /**
     * 修改菜单
     * 会校验：
     * 1. 菜单是否存在
     * 2. 菜单名称在同一父菜单下是否唯一（排除自己）
     * 3. 父菜单不能是自己或自己的子菜单
     *
     * @param addDTO 修改菜单请求DTO（必须包含menuId）
     */
    void updateMenu(SysMenuAddDTO addDTO);

    /**
     * 删除菜单
     * 会校验：
     * 1. 菜单是否存在
     * 2. 是否有子菜单（有子菜单不能删除）
     * 3. 是否被角色关联（被关联不能删除）
     *
     * @param menuId 菜单ID
     */
    void deleteMenu(Long menuId);

    /**
     * 更新菜单排序
     *
     * @param menuId   菜单ID
     * @param orderNum 新的排序号
     */
    void updateMenuOrder(Long menuId, Integer orderNum);

    /**
     * 构建菜单树形结构
     * 将平铺的菜单列表转换为树形结构
     * 使用通用TreeUtils工具类实现
     *
     * @param menuList 平铺的菜单列表
     * @return 树形结构的菜单列表
     */
    List<SysMenuVO> buildMenuTree(List<SysMenu> menuList);

    /**
     * 根据角色ID获取菜单列表
     * 用于角色菜单权限查询
     *
     * @param roleId 角色ID
     * @return 角色关联的菜单列表
     */
    List<SysMenu> getMenuListByRoleId(Long roleId);

    /**
     * 检查菜单是否有子菜单
     *
     * @param menuId 菜单ID
     * @return true-有子菜单，false-无子菜单
     */
    boolean hasChildMenu(Long menuId);
}
