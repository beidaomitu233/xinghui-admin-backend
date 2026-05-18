package com.xinghuiTec.service.impl;

import cn.idev.excel.FastExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.excel.SysUserExcel;
import com.xinghuiTec.listener.SysUserImportListener;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.service.SysUserRoleService;
import com.xinghuiTec.service.SysUserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户信息表(SysUser)表服务实现类
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Resource
    private BCryptPasswordEncoder passwordEncoder;

    @Resource
    private SysUserRoleService sysUserRoleService;

    @Resource
    private SysRoleService sysRoleService;

    @Resource
    private com.xinghuiTec.service.LoginService loginService;

    @Resource
    private com.xinghuiTec.utils.RedisCacheUtils redisCacheUtils;

    /**
     * 获取用户列表
     *
     * @param sysUserQueryDTO 用户查询条件DTO对象，包含分页参数和查询条件
     * @return Result<Page<SysUser>> 返回结果包装对象，包含分页的用户信息列表
     *         Result: 统一结果包装类
     *         Page<SysUser>: 分页对象，包含SysUser列表和分页信息
     */
    @Override
    public Page<SysUser> getUserList(SysUserQueryDTO sysUserQueryDTO) {

        // 需要根据前端传参条件来进行动态拼接查询条件

        // 1. 创建分页对象
        Page<SysUser> page = new Page<>(sysUserQueryDTO.getPageNum(), sysUserQueryDTO.getPageSize());

        // 1.校验用户名、手机号、用户状态、创建时间范围是否为空，不为空则添加到查询条件中
        LambdaQueryWrapper<SysUser> userListWrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(sysUserQueryDTO.getUsername()), SysUser::getUsername,
                        sysUserQueryDTO.getUsername())
                .like(StringUtils.hasText(sysUserQueryDTO.getNickname()), SysUser::getNickname,
                        sysUserQueryDTO.getNickname())
                .like(StringUtils.hasText(sysUserQueryDTO.getMobile()), SysUser::getMobile, sysUserQueryDTO.getMobile())
                .eq(sysUserQueryDTO.getStatus() != null, SysUser::getStatus, sysUserQueryDTO.getStatus())
                .ge(sysUserQueryDTO.getCreateTimeStart() != null,
                        SysUser::getCreateTime, sysUserQueryDTO.getCreateTimeStart())
                .le(sysUserQueryDTO.getCreateTimeEnd() != null,
                        SysUser::getCreateTime, sysUserQueryDTO.getCreateTimeEnd());
        // 3. 处理排序
        // 步骤 A: 确定排序方向（升序还是降序）
        // 逻辑：取出前端传来的 order 字段，忽略大小写判断是否等于 "asc"。
        // 结果：
        // - 如果前端传 "asc" -> isAsc = true (升序，旧的在前)
        // - 如果前端传 "desc"、null 或其他 -> isAsc = false (降序，新的在前)
        boolean isAsc = "asc".equalsIgnoreCase(sysUserQueryDTO.getOrder());
        // 步骤 B: 确定排序字段（按哪个列排）
        // 逻辑：判断前端传来的 sortBy 字段是否为 "updateTime"
        if ("updateTime".equals(sysUserQueryDTO.getOrderBy())) {
            // 情况 1: 如果指定了按更新时间排，则使用 getUpdateTime 字段
            // 参数含义: (条件是否生效, 是否升序, 数据库列对应的Lambda引用)
            // 第一个 true 表示这个排序条件始终执行
            userListWrapper.orderBy(true, isAsc, SysUser::getUpdateTime);
        } else {
            // 情况 2 (默认情况):
            // - 如果前端传的是 "createTime"
            // - 或者前端什么都没传 (null)
            // - 或者传了不认识的字段
            // 统统走进这个 else 分支，使用 getCreateTime (创建时间/注册时间)
            userListWrapper.orderBy(true, isAsc, SysUser::getCreateTime);
        }

        Page<SysUser> SysUserPage = this.page(page, userListWrapper);

        return SysUserPage;
    }

    /**
     * 根据用户ID获取用户详细信息
     *
     * @param userId 用户ID
     * @return 用户详细信息，如果不存在返回 null
     */
    @Override
    public SysUser getUserInfo(String userId) {
        // 直接使用 MyBatis-Plus 的 getById 方法根据主键查询
        // 密码字段已通过 @JsonIgnore 注解自动过滤，不会返回给前端
        return this.getById(userId);
    }

    /**
     * 新增用户（包含角色分配）
     * 该方法会同时处理：
     * 1. 创建用户基本信息（密码自动加密）
     * 2. 分配用户角色
     * 使用@Transactional注解确保操作的原子性
     *
     * @param sysUserAddDTO 新增用户请求DTO
     * @return 新增成功的用户ID（UUID格式）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String addUser(SysUserAddDTO sysUserAddDTO) {
        // 1. 校验用户名是否已存在
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, sysUserAddDTO.getUsername());
        Long count = this.count(queryWrapper);
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 2. 创建用户实体并设置基本信息
        SysUser user = new SysUser();
        // 生成UUID作为用户ID
        String userId = UUID.randomUUID().toString().replace("-", "");
        user.setUserId(userId);
        user.setUsername(sysUserAddDTO.getUsername());

        // 使用BCrypt加密密码
        String encryptedPassword = passwordEncoder.encode(sysUserAddDTO.getPassword());
        user.setPassword(encryptedPassword);

        user.setNickname(sysUserAddDTO.getNickname());
        user.setEmail(sysUserAddDTO.getEmail());
        user.setMobile(sysUserAddDTO.getMobile());
        user.setAvatar(sysUserAddDTO.getAvatar());
        user.setStatus(sysUserAddDTO.getStatus());

        // 3. 保存用户信息到数据库
        boolean saveSuccess = this.save(user);
        if (!saveSuccess) {
            throw new RuntimeException("用户信息保存失败");
        }

        // 4. 分配角色 - 批量插入用户角色关联
        List<Long> roleIds = sysUserAddDTO.getRoleIds();
        if (roleIds != null && !roleIds.isEmpty()) {
            List<SysUserRole> userRoles = new ArrayList<>();
            for (Long roleId : roleIds) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRoles.add(userRole);
            }
            // 批量保存用户角色关联
            boolean roleSuccess = sysUserRoleService.saveBatch(userRoles);
            if (!roleSuccess) {
                throw new RuntimeException("用户角色分配失败");
            }
        }

        // 5. 返回新创建的用户ID
        return userId;
    }

    /**
     * 批量新增用户
     *
     * @param sysUserAddDTOList 用户DTO列表
     * @return 成功导入的条数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAddUser(List<SysUserAddDTO> sysUserAddDTOList) {
        if (sysUserAddDTOList == null || sysUserAddDTOList.isEmpty()) {
            return 0;
        }

        List<SysUser> sysUsers = new ArrayList<>();
        List<SysUserRole> sysUserRoles = new ArrayList<>();

        // 1. 预处理数据
        for (SysUserAddDTO dto : sysUserAddDTOList) {
            SysUser user = new SysUser();
            String userId = UUID.randomUUID().toString().replace("-", "");
            user.setUserId(userId);
            user.setUsername(dto.getUsername());
            user.setNickname(dto.getNickname());
            user.setEmail(dto.getEmail());
            user.setMobile(dto.getMobile());
            user.setStatus(dto.getStatus());
            // 统一加密密码
            user.setPassword(passwordEncoder.encode(dto.getPassword()));

            sysUsers.add(user);

            // 处理角色关联
            if (dto.getRoleIds() != null && !dto.getRoleIds().isEmpty()) {
                for (Long roleId : dto.getRoleIds()) {
                    SysUserRole userRole = new SysUserRole();
                    userRole.setUserId(userId);
                    userRole.setRoleId(roleId);
                    sysUserRoles.add(userRole);
                }
            }
        }

        // 2. 批量保存用户
        // 校验用户名是否存在逻辑比较复杂，批量操作通常假设数据已经校验过
        // 或者依赖数据库唯一索引抛出异常。这里简单起见直接保存。
        boolean saveUserSuccess = this.saveBatch(sysUsers);
        if (!saveUserSuccess) {
            throw new RuntimeException("批量保存用户失败");
        }

        // 3. 批量保存角色关联
        if (!sysUserRoles.isEmpty()) {
            boolean saveRoleSuccess = sysUserRoleService.saveBatch(sysUserRoles);
            if (!saveRoleSuccess) {
                throw new RuntimeException("批量保存用户角色失败");
            }
        }

        return sysUsers.size();
    }

    /**
     * 修改用户（包含角色更新）
     * 该方法会同时处理：
     * 1. 更新用户基本信息
     * 2. 更新用户角色分配（先删除旧的角色关联，再插入新的角色关联）
     * 使用@Transactional注解确保操作的原子性
     *
     * @param sysUserAddDTO 修改用户请求DTO（必须包含userId）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(SysUserAddDTO sysUserAddDTO) {
        // 1. 校验用户是否存在
        String userId = sysUserAddDTO.getUserId();
        if (!StringUtils.hasText(userId)) {
            throw new RuntimeException("用户ID不能为空");
        }

        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new RuntimeException("用户不存在");
        }

        // 2. 如果修改了用户名，校验新用户名是否已被其他用户使用
        if (StringUtils.hasText(sysUserAddDTO.getUsername())
                && !sysUserAddDTO.getUsername().equals(existUser.getUsername())) {
            LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(SysUser::getUsername, sysUserAddDTO.getUsername())
                    .ne(SysUser::getUserId, userId);
            Long count = this.count(queryWrapper);
            if (count > 0) {
                throw new RuntimeException("用户名已存在");
            }
        }

        // 3. 更新用户基本信息
        SysUser updateUser = new SysUser();
        updateUser.setUserId(userId);
        updateUser.setUsername(sysUserAddDTO.getUsername());

        // 如果密码不为空，则更新密码（使用BCrypt加密）
        if (StringUtils.hasText(sysUserAddDTO.getPassword())) {
            String encryptedPassword = passwordEncoder.encode(sysUserAddDTO.getPassword());
            updateUser.setPassword(encryptedPassword);
        }

        updateUser.setNickname(sysUserAddDTO.getNickname());
        updateUser.setEmail(sysUserAddDTO.getEmail());
        updateUser.setMobile(sysUserAddDTO.getMobile());
        updateUser.setAvatar(sysUserAddDTO.getAvatar());
        updateUser.setStatus(sysUserAddDTO.getStatus());

        // 4. 保存更新后的用户信息
        boolean updateSuccess = this.updateById(updateUser);
        if (!updateSuccess) {
            throw new RuntimeException("用户信息更新失败");
        }

        // 5. 更新角色分配
        // 先删除该用户的所有旧角色关联
        LambdaQueryWrapper<SysUserRole> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(SysUserRole::getUserId, userId);
        sysUserRoleService.remove(deleteWrapper);

        // 再插入新的角色关联
        List<Long> roleIds = sysUserAddDTO.getRoleIds();
        if (roleIds != null && !roleIds.isEmpty()) {
            List<SysUserRole> userRoles = new ArrayList<>();
            for (Long roleId : roleIds) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRoles.add(userRole);
            }
            // 批量保存用户角色关联
            boolean roleSuccess = sysUserRoleService.saveBatch(userRoles);
            if (!roleSuccess) {
                throw new RuntimeException("用户角色分配失败");
            }
        }

        // 6. 清除用户缓存，确保下次获取时能拿到最新数据
        String userInfoCacheKey = com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId;
        redisCacheUtils.deleteObject(userInfoCacheKey);
        String routerCacheKey = com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + userId;
        redisCacheUtils.deleteObject(routerCacheKey);
    }

    /**
     * 删除用户（包含角色关联）
     * 该方法会同时处理：
     * 1. 删除用户基本信息
     * 2. 删除用户角色关联
     * 使用@Transactional注解确保操作的原子性
     *
     * @param userId 用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(String userId) {
        // 1. 校验用户ID是否为空
        if (!StringUtils.hasText(userId)) {
            throw new RuntimeException("用户ID不能为空");
        }

        // 2. 校验用户是否存在
        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 删除用户角色关联
        LambdaQueryWrapper<SysUserRole> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(SysUserRole::getUserId, userId);
        sysUserRoleService.remove(deleteWrapper);

        // 4. 删除用户基本信息
        boolean deleteSuccess = this.removeById(userId);
        if (!deleteSuccess) {
            throw new RuntimeException("用户删除失败");
        }

        // 5. 清除用户相关缓存
        String loginCacheKey = com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX + userId;
        redisCacheUtils.deleteObject(loginCacheKey);
        String userInfoCacheKey = com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId;
        redisCacheUtils.deleteObject(userInfoCacheKey);
        String routerCacheKey = com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + userId;
        redisCacheUtils.deleteObject(routerCacheKey);
    }

    /**
     * 重置用户密码
     * 使用BCrypt加密新密码
     *
     * @param userId      用户ID
     * @param newPassword 新密码
     */
    @Override
    public void resetPassword(String userId, String newPassword) {
        // 1. 校验参数
        if (!StringUtils.hasText(userId)) {
            throw new RuntimeException("用户ID不能为空");
        }
        if (!StringUtils.hasText(newPassword)) {
            throw new RuntimeException("新密码不能为空");
        }

        // 2. 校验用户是否存在
        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 使用BCrypt加密新密码
        String encryptedPassword = passwordEncoder.encode(newPassword);

        // 4. 更新用户密码
        SysUser updateUser = new SysUser();
        updateUser.setUserId(userId);
        updateUser.setPassword(encryptedPassword);

        boolean updateSuccess = this.updateById(updateUser);
        if (!updateSuccess) {
            throw new RuntimeException("密码重置失败");
        }
    }

    /**
     * 下载用户导入模板
     * 生成包含表头和示例数据的Excel文件
     *
     * @param response HttpServletResponse
     */
    @Override
    public void downloadImportTemplate(HttpServletResponse response) throws Exception {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("用户导入模板", "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        // 创建示例数据
        List<SysUserExcel> exampleData = new ArrayList<>();
        SysUserExcel example = new SysUserExcel();
        example.setUsername("zhangsan");
        example.setNickname("张三");
        example.setEmail("zhangsan@example.com");
        example.setMobile("13800138000");
        example.setStatus("正常");
        example.setRoleKeys("admin,common");
        example.setRoleNames("管理员,普通用户");
        exampleData.add(example);

        // 写出包含示例数据的Excel文件
        FastExcel.write(response.getOutputStream(), SysUserExcel.class)
                .sheet("用户信息")
                .doWrite(exampleData);
    }

    /**
     * 批量导入用户
     * 解析上传的Excel文件并批量保存用户信息
     *
     * @param file Excel文件
     * @return 导入结果信息
     */
    @Override
    public String importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }

        try {
            // 创建监听器（需要角色服务来根据roleKey查询roleId）
            SysUserImportListener listener = new SysUserImportListener(
                    this, sysRoleService);

            // 读取Excel文件
            FastExcel.read(file.getInputStream(), SysUserExcel.class, listener)
                    .sheet()
                    .doRead();

            // 返回导入结果
            int successCount = listener.getSuccessCount();
            int failCount = listener.getFailCount();
            StringBuilder result = new StringBuilder();
            result.append(String.format("导入完成！成功: %d 条, 失败: %d 条", successCount, failCount));

            if (failCount > 0) {
                result.append("\n失败详情：\n");
                List<String> failMessages = listener.getFailMessages();
                for (int i = 0; i < Math.min(failMessages.size(), 10); i++) {
                    result.append(failMessages.get(i)).append("\n");
                }
                if (failMessages.size() > 10) {
                    result.append(String.format("...还有 %d 条失败记录未显示", failMessages.size() - 10));
                }
            }

            return result.toString();
        } catch (Exception e) {
            throw new RuntimeException("文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 批量导出用户
     * 根据查询条件导出用户列表到Excel文件
     *
     * @param sysUserQueryDTO 查询条件
     * @param response        HttpServletResponse
     */
    @Override
    public void exportUsers(SysUserQueryDTO sysUserQueryDTO,
            HttpServletResponse response) throws Exception {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("用户列表", "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        // 查询用户列表（不分页，导出所有符合条件的数据）
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(sysUserQueryDTO.getUsername()), SysUser::getUsername,
                        sysUserQueryDTO.getUsername())
                .like(StringUtils.hasText(sysUserQueryDTO.getNickname()), SysUser::getNickname,
                        sysUserQueryDTO.getNickname())
                .like(StringUtils.hasText(sysUserQueryDTO.getMobile()), SysUser::getMobile,
                        sysUserQueryDTO.getMobile())
                .eq(sysUserQueryDTO.getStatus() != null, SysUser::getStatus, sysUserQueryDTO.getStatus())
                .ge(sysUserQueryDTO.getCreateTimeStart() != null,
                        SysUser::getCreateTime, sysUserQueryDTO.getCreateTimeStart())
                .le(sysUserQueryDTO.getCreateTimeEnd() != null,
                        SysUser::getCreateTime, sysUserQueryDTO.getCreateTimeEnd());

        List<SysUser> userList = this.list(queryWrapper);

        // 转换为Excel数据模型
        List<com.xinghuiTec.domain.excel.SysUserExcel> excelDataList = new ArrayList<>();
        for (SysUser user : userList) {
            com.xinghuiTec.domain.excel.SysUserExcel excelData = new com.xinghuiTec.domain.excel.SysUserExcel();
            excelData.setUsername(user.getUsername());
            excelData.setNickname(user.getNickname());
            excelData.setEmail(user.getEmail());
            excelData.setMobile(user.getMobile());
            excelData.setStatus(user.getStatus() == 1 ? "正常" : "停用");

            // 查询用户的角色信息（包括roleKey和roleName）
            LambdaQueryWrapper<SysUserRole> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(SysUserRole::getUserId, user.getUserId());
            List<SysUserRole> userRoles = sysUserRoleService.list(roleWrapper);
            if (!userRoles.isEmpty()) {
                // 查询角色详细信息
                List<Long> roleIds = userRoles.stream()
                        .map(SysUserRole::getRoleId)
                        .collect(java.util.stream.Collectors.toList());

                LambdaQueryWrapper<com.xinghuiTec.domain.entity.SysRole> roleQuery = new LambdaQueryWrapper<>();
                roleQuery.in(com.xinghuiTec.domain.entity.SysRole::getRoleId, roleIds);
                List<com.xinghuiTec.domain.entity.SysRole> roles = sysRoleService.list(roleQuery);

                // 提取角色标识和角色名称
                String roleKeys = roles.stream()
                        .map(com.xinghuiTec.domain.entity.SysRole::getRoleKey)
                        .collect(java.util.stream.Collectors.joining(","));
                String roleNames = roles.stream()
                        .map(com.xinghuiTec.domain.entity.SysRole::getRoleName)
                        .collect(java.util.stream.Collectors.joining(","));

                excelData.setRoleKeys(roleKeys);
                excelData.setRoleNames(roleNames);
            }

            excelDataList.add(excelData);
        }

        // 写入Excel文件
        cn.idev.excel.FastExcel.write(response.getOutputStream(), com.xinghuiTec.domain.excel.SysUserExcel.class)
                .sheet("用户列表")
                .doWrite(excelDataList);
    }

    // ==================== 个人中心管理实现 ====================

    /**
     * 获取当前登录用户的详细信息
     * 包含用户基本信息和角色列表
     * 
     * @return UserInfoVO 用户详细信息
     */
    @Override
    public com.xinghuiTec.domain.vo.UserInfoVO getProfile() {
        // 1. 从 SecurityContext 获取当前登录用户的 userId
        // SecurityUtils.getUser() 返回的是当前登录用户的 SysUser 对象
        com.xinghuiTec.domain.entity.SysUser currentUser = com.xinghuiTec.utils.SecurityUtils.getUser();
        String userId = currentUser.getUserId();

        // 2. 调用 LoginService 获取用户详细信息（包含角色列表）
        // LoginService 已经实现了缓存机制，优先从 Redis 获取，缓存未命中才查询数据库
        return loginService.getUserInfo(userId);
    }

    /**
     * 修改当前登录用户的个人信息
     * 
     * @param updateDTO 个人信息修改DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(com.xinghuiTec.domain.dto.SysProfileUpdateDTO updateDTO) {
        // 1. 获取当前登录用户的 userId
        // 注意：不能从参数中获取 userId，必须从 SecurityContext 获取，防止用户篡改他人信息
        com.xinghuiTec.domain.entity.SysUser currentUser = com.xinghuiTec.utils.SecurityUtils.getUser();
        String userId = currentUser.getUserId();

        // 2. 查询用户当前信息
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 校验邮箱唯一性（如果传入了邮箱且与当前邮箱不同）
        if (StringUtils.hasText(updateDTO.getEmail()) && !updateDTO.getEmail().equals(user.getEmail())) {
            // 检查新邮箱是否已被其他用户使用
            LambdaQueryWrapper<SysUser> emailWrapper = new LambdaQueryWrapper<>();
            emailWrapper.eq(SysUser::getEmail, updateDTO.getEmail())
                    .ne(SysUser::getUserId, userId); // 排除自己
            long emailCount = this.count(emailWrapper);
            if (emailCount > 0) {
                throw new RuntimeException("该邮箱已被其他用户使用");
            }
        }

        // 4. 校验手机号唯一性（如果传入了手机号且与当前手机号不同）
        if (StringUtils.hasText(updateDTO.getMobile()) && !updateDTO.getMobile().equals(user.getMobile())) {
            // 检查新手机号是否已被其他用户使用
            LambdaQueryWrapper<SysUser> mobileWrapper = new LambdaQueryWrapper<>();
            mobileWrapper.eq(SysUser::getMobile, updateDTO.getMobile())
                    .ne(SysUser::getUserId, userId); // 排除自己
            long mobileCount = this.count(mobileWrapper);
            if (mobileCount > 0) {
                throw new RuntimeException("该手机号已被其他用户使用");
            }
        }

        // 5. 更新用户信息
        // 使用 LambdaUpdateWrapper 只更新传入的字段
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser> updateWrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        updateWrapper.eq(SysUser::getUserId, userId);

        // 只更新非空字段
        if (StringUtils.hasText(updateDTO.getNickname())) {
            updateWrapper.set(SysUser::getNickname, updateDTO.getNickname());
        }
        if (StringUtils.hasText(updateDTO.getEmail())) {
            updateWrapper.set(SysUser::getEmail, updateDTO.getEmail());
        }
        if (StringUtils.hasText(updateDTO.getMobile())) {
            updateWrapper.set(SysUser::getMobile, updateDTO.getMobile());
        }

        boolean updateSuccess = this.update(updateWrapper);
        if (!updateSuccess) {
            throw new RuntimeException("个人信息修改失败");
        }

        // 6. 清除 Redis 缓存
        // 修改个人信息后，需要清除用户信息缓存，下次访问时重新加载
        String cacheKey = com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId;
        redisCacheUtils.deleteObject(cacheKey);
    }

    /**
     * 修改当前登录用户的密码
     * 
     * @param updateDTO 密码修改DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(com.xinghuiTec.domain.dto.SysPasswordUpdateDTO updateDTO) {
        // 1. 获取当前登录用户的 userId
        com.xinghuiTec.domain.entity.SysUser currentUser = com.xinghuiTec.utils.SecurityUtils.getUser();
        String userId = currentUser.getUserId();

        // 2. 校验新密码和确认密码是否一致
        if (!updateDTO.getNewPassword().equals(updateDTO.getConfirmPassword())) {
            throw new RuntimeException("新密码和确认密码不一致");
        }

        // 3. 查询用户当前密码
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 4. 验证旧密码是否正确
        // 使用 BCryptPasswordEncoder 验证密码
        // matches(原始密码, 加密后的密码)
        if (!passwordEncoder.matches(updateDTO.getOldPassword(), user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }

        // 5. 验证新密码不能与旧密码相同
        if (updateDTO.getNewPassword().equals(updateDTO.getOldPassword())) {
            throw new RuntimeException("新密码不能与旧密码相同");
        }

        // 6. 加密新密码
        String encodedNewPassword = passwordEncoder.encode(updateDTO.getNewPassword());

        // 7. 更新密码到数据库
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser> updateWrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        updateWrapper.eq(SysUser::getUserId, userId)
                .set(SysUser::getPassword, encodedNewPassword);

        boolean updateSuccess = this.update(updateWrapper);
        if (!updateSuccess) {
            throw new RuntimeException("密码修改失败");
        }

        // 8. 清除登录缓存，强制用户重新登录
        // 修改密码是敏感操作，为了安全，清除登录缓存让用户使用新密码重新登录
        // 清除登录信息缓存
        String loginCacheKey = com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX + userId;
        redisCacheUtils.deleteObject(loginCacheKey);

        // 清除用户信息缓存
        String userInfoCacheKey = com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId;
        redisCacheUtils.deleteObject(userInfoCacheKey);

        // 清除用户路由缓存
        String routerCacheKey = com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + userId;
        redisCacheUtils.deleteObject(routerCacheKey);
    }

}
