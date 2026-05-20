package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysPasswordUpdateDTO;
import com.xinghuiTec.domain.dto.SysProfileUpdateDTO;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.vo.UserInfoVO;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 用户信息表(SysUser)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysUserService extends IService<SysUser> {
    /**
     * 根据条件分页查询用户列表
     */
    Page<SysUser> getUserList(SysUserQueryDTO sysUserQueryDTO);

    /**
     * 根据用户ID获取用户详细信息
     * 
     * @param userId 用户ID
     * @return 用户详细信息，如果不存在返回 null
     */
    SysUser getUserInfo(Long userId);

    /**
     * 新增用户（包含角色分配）
     * 该方法会同时处理：
     * 1. 创建用户基本信息（密码自动加密）
     * 2. 分配用户角色
     * 
     * @param sysUserAddDTO 新增用户请求DTO
     * @return 新增成功的用户ID（UUID格式）
     */
    Long addUser(SysUserAddDTO sysUserAddDTO);

    /**
     * 批量新增用户
     *
     * @param sysUserAddDTOList 用户DTO列表
     * @return 成功导入的条数
     */
    int batchAddUser(List<SysUserAddDTO> sysUserAddDTOList);

    /**
     * 修改用户（包含角色更新）
     * 该方法会同时处理：
     * 1. 更新用户基本信息
     * 2. 更新用户角色分配
     * 
     * @param sysUserAddDTO 修改用户请求DTO
     */
    void updateUser(SysUserAddDTO sysUserAddDTO);

    /**
     * 删除用户（包含角色关联）
     * 该方法会同时处理：
     * 1. 删除用户基本信息
     * 2. 删除用户角色关联
     * 
     * @param userId 用户ID
     */
    void deleteUser(Long userId);

    /**
     * 重置用户密码
     * 
     * @param userId      用户ID
     * @param newPassword 新密码
     */
    void resetPassword(Long userId, String newPassword);

    /**
     * 下载用户导入模板
     * 
     * @param response HttpServletResponse
     */
    void downloadImportTemplate(HttpServletResponse response) throws Exception;

    /**
     * 批量导入用户
     * 
     * @param file Excel文件
     * @return 导入结果信息
     */
    String importUsers(MultipartFile file);

    /**
     * 批量导出用户
     * 
     * @param sysUserQueryDTO 查询条件
     * @param response        HttpServletResponse
     */
    void exportUsers(SysUserQueryDTO sysUserQueryDTO, HttpServletResponse response)
            throws Exception;

    // ==================== 个人中心管理 ====================

    /**
     * 获取当前登录用户的详细信息
     * 包含用户基本信息和角色列表
     * 
     * @return 用户详细信息（UserInfoVO）
     */
    UserInfoVO getProfile();

    /**
     * 修改当前登录用户的个人信息
     * 支持修改：昵称、邮箱、手机号
     * 
     * 注意：
     * 1. 用户只能修改自己的信息
     * 2. 邮箱和手机号会校验唯一性（排除自己）
     * 3. 修改后会清除 Redis 缓存
     * 
     * @param updateDTO 个人信息修改DTO
     */
    void updateProfile(SysProfileUpdateDTO updateDTO);

    /**
     * 修改当前登录用户的密码
     * 
     * 注意：
     * 1. 必须验证旧密码正确性
     * 2. 新密码使用 BCrypt 加密
     * 3. 修改成功后清除登录缓存，强制重新登录
     * 
     * @param updateDTO 密码修改DTO
     */
    void updatePassword(SysPasswordUpdateDTO updateDTO);

}
