package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.domain.vo.UserDetailVO;
import com.xinghuiTec.domain.vo.UserInfoVO;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.LoginService;
import com.xinghuiTec.service.SysUserService;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_USER_NOT_EXIST_ERROR;

/**
 * 用户信息表(SysUser)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */

@RestController
// 请求路径和权限符一致
@RequestMapping("/system/user")
public class SysUserController {
    @Resource
    private SysUserService sysUserService;

    @Resource
    private LoginService loginService;

    /**
     * 获取用户列表
     * 该方法提供用户列表的分页查询功能，需要具备'system:user:list'权限才能访问
     *
     * @param sysUserQueryDTO 用户查询条件DTO对象，包含分页参数和查询条件，经过@Validated验证
     * @return Result<Page<SysUser>> 返回结果包装对象，包含分页的用户信息列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:user:list')")
    public Result<Page<SysUser>> getUserList(@Validated SysUserQueryDTO sysUserQueryDTO) {
        Page<SysUser> userListPage = sysUserService.getUserList(sysUserQueryDTO);
        return Result.ok(userListPage);
    }

    /**
     * 获取当前登录用户的完整信息
     * 包含用户基本信息、权限列表、角色信息和路由菜单
     * 该接口需要用户已登录，通过JWT认证
     *
     * @return Result<UserDetailVO> 返回用户详细信息，包括：
     *         - userInfo: 用户基本信息、角色标识、角色名称、权限列表
     *         - routers: 用户可访问的路由菜单树
     */
    @GetMapping("/info")
    @PreAuthorize("hasAuthority('system:user:query')")
    public Result<UserDetailVO> getUserDetail() {
        String userId = SecurityUtils.getUser().getUserId();
        // 1. 获取用户基本信息和权限(包含角色keys、角色names和权限permissions)
        UserInfoVO user = loginService.getUserInfo(userId);
        if (user == null) {
            return Result.fail(ADMIN_USER_NOT_EXIST_ERROR);
        }
        // 2. 获取用户路由菜单
        List<SysMenuVO> routers = loginService.getUserRouter(userId);

        // 3. 组装返回结果
        UserDetailVO userDetail = new UserDetailVO(user, routers);

        return Result.ok(userDetail);
    }


    /**
     * 获取当前登录用户的完整信息
     * 包含用户基本信息、权限列表、角色信息和路由菜单
     * 该接口需要用户已登录，通过JWT认证
     *
     * @return Result<UserDetailVO> 返回用户详细信息，包括：
     *         - userInfo: 用户基本信息、角色标识、角色名称、权限列表
     *         - routers: 用户可访问的路由菜单树
     */
    @GetMapping("/router")
    @PreAuthorize("hasAuthority('system:user:query')")
    public Result<UserDetailVO> getUserRouter() {
        String userId = SecurityUtils.getUser().getUserId();
        // 2. 获取用户路由菜单
        List<SysMenuVO> routers = loginService.getUserRouter(userId);

        // 3. 组装返回结果
        UserDetailVO userDetail = new UserDetailVO(null,routers);

        return Result.ok(userDetail);
    }

    /**
     * 新增用户
     * 该接口用于创建新用户并同时分配角色
     * 角色关联的路由权限会在用户登录时自动获取
     * 需要具备'system:user:add'权限才能访问
     *
     * @param sysUserAddDTO 新增用户请求DTO，经过@Validated验证
     * @return Result<String> 返回新增用户的userId（UUID格式）
     */
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('system:user:add')")
    @Log(title = "用户管理", businessType = BusinessType.INSERT)
    public Result<String> addUser(@Validated @RequestBody SysUserAddDTO sysUserAddDTO) {
        String userId = sysUserService.addUser(sysUserAddDTO);
        return Result.ok(userId);
    }

    /**
     * 修改用户
     * 该接口用于更新用户信息及其角色分配
     * 需要具备'system:user:edit'权限才能访问
     *
     * @param sysUserAddDTO 修改用户请求DTO，经过@Validated验证
     * @return Result<Void> 返回操作结果
     */
    @PostMapping("/edit")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    public Result<Void> updateUser(@Validated @RequestBody SysUserAddDTO sysUserAddDTO) {
        sysUserService.updateUser(sysUserAddDTO);
        return Result.ok();
    }

    /**
     * 删除用户
     * 该接口用于删除指定用户及其角色关联
     * 需要具备'system:user:remove'权限才能访问
     *
     * @param userId 用户ID
     * @return Result<Void> 返回操作结果
     */
    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:user:remove')")
    @Log(title = "用户管理", businessType = BusinessType.DELETE)
    public Result<Void> deleteUser(@RequestParam("userId") String userId) {
        sysUserService.deleteUser(userId);
        return Result.ok();
    }

    /**
     * 重置密码
     * 该接口用于重置指定用户的密码为默认密码
     * 默认密码是 123456
     * 需要具备'system:user:edit'权限才能访问
     *
     * @param userId 用户ID
     * @return Result<Void> 返回操作结果
     */
    @PostMapping("/resetPwd")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    public Result<Void> resetPassword(@RequestParam("userId") String userId) {
        sysUserService.resetPassword(userId, "123456");
        return Result.ok();
    }

    /**
     * 下载用户导入模板
     * 生成一个包含表头的空 Excel 文件供用户填写
     * 需要具备'system:user:list'权限才能访问
     *
     * @param response HttpServletResponse
     */
    @GetMapping("/importTemplate")
    @PreAuthorize("hasAuthority('system:user:list')")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        sysUserService.downloadImportTemplate(response);
    }

    /**
     * 批量导入用户
     * 上传 Excel 文件批量导入用户信息
     * 需要具备'system:user:add'权限才能访问
     *
     * @param file 上传的 Excel 文件
     * @return Result<String> 返回导入结果信息
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('system:user:add')")
    @Log(title = "用户管理", businessType = BusinessType.IMPORT)
    public Result<String> importUsers(@RequestBody MultipartFile file) {
        String result = sysUserService.importUsers(file);
        return Result.ok(result);
    }

    /**
     * 批量导出用户
     * 根据查询条件导出用户列表到 Excel 文件
     * 需要具备'system:user:list'权限才能访问
     *
     * @param sysUserQueryDTO 查询条件
     * @param response        HttpServletResponse
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('system:user:list')")
    @Log(title = "用户管理", businessType = BusinessType.EXPORT)
    public void exportUsers(@Validated SysUserQueryDTO sysUserQueryDTO, HttpServletResponse response) throws Exception {
        sysUserService.exportUsers(sysUserQueryDTO, response);
    }
}
