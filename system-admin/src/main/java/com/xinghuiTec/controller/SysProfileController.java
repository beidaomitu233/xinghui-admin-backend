package com.xinghuiTec.controller;

import com.xinghuiTec.domain.dto.SysPasswordUpdateDTO;
import com.xinghuiTec.domain.dto.SysProfileUpdateDTO;
import com.xinghuiTec.domain.vo.UserInfoVO;
import com.xinghuiTec.service.SysUserService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 个人中心控制层
 * 提供用户个人信息管理的REST API接口，包括：
 * 1. 查看个人信息
 * 2. 修改个人信息
 * 3. 修改密码
 * 
 * 安全说明：
 * - 所有接口都不需要特定权限，已登录用户即可访问
 * - 所有操作只能修改当前登录用户自己的信息
 * - userId 从 SecurityContext 获取，不能通过参数传递
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@RestController
@RequestMapping("/system/profile")
public class SysProfileController {

    /**
     * 注入用户服务
     */
    @Resource
    private SysUserService sysUserService;

    /**
     * 获取当前登录用户的详细信息
     * 
     * 接口说明：
     * - 返回用户基本信息（用户名、昵称、邮箱、手机号、头像等）
     * - 返回用户角色列表
     * - 返回用户权限标识列表
     * 
     * 缓存机制：
     * - 优先从 Redis 获取，缓存未命中才查询数据库
     * - 缓存 Key: USER_INFO_PREFIX + userId
     * - 缓存时间：USER_INFO_TTL_SEC
     *
     * @return Result<UserInfoVO> 用户详细信息
     */
    @GetMapping
    public Result<UserInfoVO> getProfile() {
        // 调用 Service 层获取当前登录用户的详细信息
        // Service 层会自动从 SecurityContext 获取当前用户的 userId
        UserInfoVO userInfo = sysUserService.getProfile();
        return Result.ok(userInfo);
    }

    /**
     * 修改当前登录用户的个人信息
     * 
     * 支持修改的字段：
     * - 昵称 (nickname)
     * - 邮箱 (email)
     * - 手机号 (mobile)
     * 
     * 业务逻辑：
     * 1. 只更新传入的非空字段
     * 2. 邮箱和手机号会校验唯一性（排除自己）
     * 3. 修改成功后会清除用户信息缓存
     * 
     * 注意事项：
     * - 用户账号（username）不允许修改
     * - 邮箱需要符合格式要求
     * - 手机号需要符合中国大陆格式（11位）
     *
     * @param updateDTO 个人信息修改DTO（使用 @Validated 进行参数校验）
     * @return Result<Void> 操作结果
     */
    @PutMapping
    public Result<Void> updateProfile(@Validated @RequestBody SysProfileUpdateDTO updateDTO) {
        // 调用 Service 层修改个人信息
        // Service 层会：
        // 1. 从 SecurityContext 获取当前用户的 userId
        // 2. 校验邮箱/手机号唯一性
        // 3. 更新用户信息
        // 4. 清除 Redis 缓存
        sysUserService.updateProfile(updateDTO);
        return Result.ok();
    }

    /**
     * 修改当前登录用户的密码
     * 
     * 业务流程：
     * 1. 验证旧密码是否正确
     * 2. 验证新密码和确认密码是否一致
     * 3. 验证新密码不能与旧密码相同
     * 4. 使用 BCrypt 加密新密码
     * 5. 更新密码到数据库
     * 6. 清除登录缓存，强制用户重新登录
     * 
     * 密码要求：
     * - 长度：6-20个字符
     * - 强度：必须包含数字和字母
     * 
     * 安全机制：
     * - 必须验证旧密码，防止恶意修改
     * - 新密码使用 BCrypt 加密存储
     * - 修改成功后清除所有相关缓存（登录信息、用户信息、路由信息）
     * - 用户需要使用新密码重新登录
     *
     * @param updateDTO 密码修改DTO（使用 @Validated 进行参数校验）
     * @return Result<Void> 操作结果
     */
    @PutMapping("/updatePwd")
    public Result<Void> updatePassword(@Validated @RequestBody SysPasswordUpdateDTO updateDTO) {
        // 调用 Service 层修改密码
        // Service 层会：
        // 1. 从 SecurityContext 获取当前用户的 userId
        // 2. 验证旧密码是否正确
        // 3. 验证新密码和确认密码是否一致
        // 4. 加密新密码并更新到数据库
        // 5. 清除所有相关的 Redis 缓存
        sysUserService.updatePassword(updateDTO);
        return Result.ok();
    }
}
