package com.xinghuiTec.service.impl;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.excel.SysUserExcel;
import com.xinghuiTec.domain.excel.SysUserImportExcel;
import com.xinghuiTec.domain.vo.UserImportRowResult;
import com.xinghuiTec.domain.vo.UserImportSummary;
import com.xinghuiTec.listener.SysUserImportListener;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.service.SysUserRoleService;
import com.xinghuiTec.service.SysUserService;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.utils.TenantHelper;
import com.xinghuiTec.utils.TemporaryPasswordGenerator;
import com.xinghuiTec.utils.UserExcelSafety;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用户信息表(SysUser)表服务实现类
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
    private static final long MAX_IMPORT_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_IMPORT_EXPANDED_BYTES = 50L * 1024 * 1024;
    private static final int MAX_XLSX_ENTRIES = 2_000;
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int EXPORT_PAGE_SIZE = 500;
    private static final String IMPORT_PENDING_ACTIVATION_REASON =
            "已创建为待激活账号（禁用）；请检查角色、重置密码并安全交付后再启用";

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

    @Resource
    private Validator validator;

    /**
     * 导入逐行调用代理以确保每个用户及其角色关联在同一事务中提交。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private SysUserService selfProxy;

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
        LambdaQueryWrapper<SysUser> userListWrapper = buildUserFilters(sysUserQueryDTO);
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

    private LambdaQueryWrapper<SysUser> buildUserFilters(SysUserQueryDTO query) {
        return new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .like(StringUtils.hasText(query.getNickname()), SysUser::getNickname, query.getNickname())
                .like(StringUtils.hasText(query.getMobile()), SysUser::getMobile, query.getMobile())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .ge(query.getCreateTimeStart() != null, SysUser::getCreateTime, query.getCreateTimeStart())
                .le(query.getCreateTimeEnd() != null, SysUser::getCreateTime, query.getCreateTimeEnd());
    }

    /**
     * 根据用户ID获取用户详细信息
     *
     * @param userId 用户ID
     * @return 用户详细信息，如果不存在返回 null
     */
    @Override
    public SysUser getUserInfo(Long userId) {
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
    public Long addUser(SysUserAddDTO sysUserAddDTO) {
        validateRoles(sysUserAddDTO.getRoleIds(), null);
        // 1. 校验用户名是否已存在
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, sysUserAddDTO.getUsername());
        Long count = this.count(queryWrapper);
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 1.1 校验手机号唯一性（登录凭证按手机号匹配，重复手机号会导致登录命中错误账号）
        if (StringUtils.hasText(sysUserAddDTO.getMobile())) {
            LambdaQueryWrapper<SysUser> mobileWrapper = new LambdaQueryWrapper<>();
            mobileWrapper.eq(SysUser::getMobile, sysUserAddDTO.getMobile());
            if (this.count(mobileWrapper) > 0) {
                throw new RuntimeException("手机号已被其他用户使用");
            }
        }

        // 2. 创建用户实体并设置基本信息
        SysUser user = new SysUser();
        // ID 由 MyBatis-Plus ASSIGN_ID 自动生成
        user.setUsername(sysUserAddDTO.getUsername());

        String tenantId = TenantHelper.getTenantId();
        if (TenantHelper.isEnable() && !StringUtils.hasText(tenantId)) {
            throw new IllegalStateException("当前租户信息缺失");
        }
        user.setTenantId(StringUtils.hasText(tenantId) ? tenantId : TenantConstants.DEFAULT_TENANT_ID);

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
        
        Long userId = user.getUserId();

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
        Long userId = sysUserAddDTO.getUserId();
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }

        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new RuntimeException("用户不存在");
        }
        validateRoles(sysUserAddDTO.getRoleIds(), userId);

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

        // 2.1 如果修改了手机号，校验新手机号是否已被其他用户使用（手机号是登录凭证）
        if (StringUtils.hasText(sysUserAddDTO.getMobile())
                && !sysUserAddDTO.getMobile().equals(existUser.getMobile())) {
            LambdaQueryWrapper<SysUser> mobileWrapper = new LambdaQueryWrapper<>();
            mobileWrapper.eq(SysUser::getMobile, sysUserAddDTO.getMobile())
                    .ne(SysUser::getUserId, userId);
            if (this.count(mobileWrapper) > 0) {
                throw new RuntimeException("手机号已被其他用户使用");
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
        // 同时清除登录态缓存：LoginFilter 按此缓存放行请求，
        // 不清除的话被停用/降权的用户旧 token 在 TTL 内仍按旧权限生效
        String loginCacheKey = com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX + userId;
        redisCacheUtils.deleteObject(loginCacheKey);
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
    public void deleteUser(Long userId) {
        // 1. 校验用户ID是否为空
        if (userId == null) {
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

    /** Creates a distinct 20-character temporary password and returns it only once. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long userId) {
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }
        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new RuntimeException("用户不存在");
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        String encryptedPassword = passwordEncoder.encode(temporaryPassword);

        // 4. 更新用户密码
        SysUser updateUser = new SysUser();
        updateUser.setUserId(userId);
        updateUser.setPassword(encryptedPassword);

        boolean updateSuccess = this.updateById(updateUser);
        if (!updateSuccess) {
            throw new RuntimeException("密码重置失败");
        }

        // 5. 清除该用户全部登录相关缓存：重置密码属安全敏感操作，
        // 必须让旧 token 立即失效（否则持旧凭证的会话——可能正是泄露源——继续有效）
        redisCacheUtils.deleteObject(com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX + userId);
        redisCacheUtils.deleteObject(com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId);
        redisCacheUtils.deleteObject(com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + userId);
        return temporaryPassword;
    }

    void validateRoles(List<Long> roleIds, Long existingUserId) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        if (roleIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(roleIds).size() != roleIds.size()) {
            throw new IllegalArgumentException("角色ID不能重复或为空");
        }
        List<com.xinghuiTec.domain.entity.SysRole> roles = sysRoleService.list(
                new LambdaQueryWrapper<com.xinghuiTec.domain.entity.SysRole>()
                        .in(com.xinghuiTec.domain.entity.SysRole::getRoleId, roleIds));
        if (roles.size() != roleIds.size()) {
            throw new IllegalArgumentException("角色不存在");
        }
        java.util.Set<Long> assignedRoleIds = existingUserId == null ? java.util.Set.of()
                : sysUserRoleService.list(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, existingUserId)).stream()
                        .map(SysUserRole::getRoleId).collect(java.util.stream.Collectors.toSet());
        if (roles.stream().anyMatch(role -> !Integer.valueOf(1).equals(role.getStatus())
                && !assignedRoleIds.contains(role.getRoleId()))) {
            throw new IllegalArgumentException("不能新分配已停用角色");
        }
    }

    /** Download a neutral import template containing only the six required headers. */
    @Override
    public void downloadImportTemplate(HttpServletResponse response) throws Exception {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("用户导入模板.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName);
        FastExcel.write(response.getOutputStream(), SysUserImportExcel.class)
                .sheet("用户导入")
                .doWrite(List.of());
    }

    /** Parses and validates the whole bounded workbook before saving any row. */
    @Override
    public UserImportSummary importUsers(MultipartFile file) {
        validateImportFile(file);
        SysUserImportListener listener = new SysUserImportListener();
        try (InputStream input = file.getInputStream()) {
            FastExcel.read(input, SysUserImportExcel.class, listener).sheet(0).doRead();
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Excel 文件无法解析，请检查文件内容和表头");
        }
        if (listener.isRowLimitExceeded()) {
            throw new IllegalArgumentException("每个文件最多导入 1000 行；未写入任何用户");
        }
        if (!listener.areHeadersValid()) {
            throw new IllegalArgumentException("Excel 表头错误，请使用系统下载的导入模板");
        }

        Map<String, List<Long>> visibleRoleIds = new HashMap<>();
        try {
            for (SysRole role : sysRoleService.list()) {
                if (StringUtils.hasText(role.getRoleKey())) {
                    visibleRoleIds.computeIfAbsent(role.getRoleKey(), key -> new ArrayList<>()).add(role.getRoleId());
                }
            }
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("读取当前租户角色失败");
        }

        int successCount = 0;
        int failureCount = 0;
        int skippedCount = 0;
        List<UserImportRowResult> results = new ArrayList<>();
        for (SysUserImportListener.ParsedRow row : listener.getRows()) {
            SysUserImportExcel data = row.data();
            if (isBlankImportRow(data)) {
                skippedCount++;
                results.add(new UserImportRowResult(row.rowNumber(), "skipped", "", "空行已忽略"));
                continue;
            }
            try {
                SysUserAddDTO dto = toImportDto(data, visibleRoleIds);
                validateImportDto(dto);
                selfProxy.addUser(dto);
                successCount++;
                results.add(new UserImportRowResult(row.rowNumber(), "success", "",
                        IMPORT_PENDING_ACTIVATION_REASON));
            } catch (RowImportException e) {
                failureCount++;
                results.add(new UserImportRowResult(row.rowNumber(), "failed", e.field, e.reason));
            } catch (RuntimeException e) {
                RowImportException safeFailure = safeServiceFailure(e);
                failureCount++;
                results.add(new UserImportRowResult(row.rowNumber(), "failed", safeFailure.field, safeFailure.reason));
            }
        }
        return new UserImportSummary(results.size(), successCount, failureCount, skippedCount, List.copyOf(results));
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_IMPORT_FILE_BYTES) {
            throw new IllegalArgumentException("XLSX 文件不能超过 5 MiB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 文件");
        }
        try (InputStream signatureStream = file.getInputStream()) {
            byte[] signature = signatureStream.readNBytes(4);
            if (signature.length != 4 || signature[0] != 'P' || signature[1] != 'K'
                    || signature[2] != 3 || signature[3] != 4) {
                throw new IllegalArgumentException("上传文件不是有效的 XLSX 工作簿");
            }
        } catch (IOException ignored) {
            throw new IllegalArgumentException("上传文件无法读取");
        }

        Set<String> packageEntries = readBoundedXlsxPackage(file);
        boolean hasWorksheet = packageEntries.stream()
                .anyMatch(name -> name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
        if (!packageEntries.contains("[Content_Types].xml")
                || !packageEntries.contains("xl/workbook.xml") || !hasWorksheet) {
            throw new IllegalArgumentException("上传文件不是有效的 XLSX 工作簿");
        }
    }

    private Set<String> readBoundedXlsxPackage(MultipartFile file) {
        Path archivePath = null;
        byte[] buffer = new byte[8192];
        try {
            archivePath = Files.createTempFile("sys-user-import-", ".xlsx");
            try (InputStream upload = file.getInputStream()) {
                Files.copy(upload, archivePath, StandardCopyOption.REPLACE_EXISTING);
            }

            long expandedBytes = 0;
            int entryCount = 0;
            Set<String> packageEntries = new HashSet<>();
            try (ZipFile zip = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    entryCount++;
                    if (entryCount > MAX_XLSX_ENTRIES) {
                        throw new IllegalArgumentException("XLSX 文件包含过多内容");
                    }
                    String name = entry.getName();
                    if (name == null || name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                        throw new IllegalArgumentException("不支持含宏的工作簿");
                    }
                    if (!packageEntries.add(name)) {
                        throw new IllegalArgumentException("XLSX 文件包含重复内容项");
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > MAX_IMPORT_EXPANDED_BYTES - expandedBytes) {
                        throw new IllegalArgumentException("XLSX 解压后内容超过 50 MiB");
                    }
                    try (InputStream content = zip.getInputStream(entry)) {
                        int read;
                        while ((read = content.read(buffer)) != -1) {
                            expandedBytes += read;
                            if (expandedBytes > MAX_IMPORT_EXPANDED_BYTES) {
                                throw new IllegalArgumentException("XLSX 解压后内容超过 50 MiB");
                            }
                        }
                    }
                }
            }
            return packageEntries;
        } catch (IOException ignored) {
            throw new IllegalArgumentException("XLSX 文件损坏或格式错误");
        } finally {
            if (archivePath != null) {
                try {
                    Files.deleteIfExists(archivePath);
                } catch (IOException ignored) {
                    archivePath.toFile().deleteOnExit();
                }
            }
        }
    }

    private boolean isBlankImportRow(SysUserImportExcel data) {
        return data == null || (!StringUtils.hasText(data.getUsername())
                && !StringUtils.hasText(data.getNickname())
                && !StringUtils.hasText(data.getEmail())
                && !StringUtils.hasText(data.getMobile())
                && !StringUtils.hasText(data.getRoleKeys()));
    }

    private SysUserAddDTO toImportDto(SysUserImportExcel row, Map<String, List<Long>> visibleRoleIds) {
        SysUserAddDTO dto = new SysUserAddDTO();
        dto.setUsername(trimToNull(row.getUsername()));
        dto.setNickname(trimToNull(row.getNickname()));
        dto.setEmail(trimToNull(row.getEmail()));
        dto.setMobile(trimToNull(row.getMobile()));
        dto.setPassword(TemporaryPasswordGenerator.generate());
        // Import creates a pre-provisioned account only. It stays disabled until an
        // authorized administrator issues its one-time password and enables it.
        dto.setStatus(0);

        String roleKeys = trimToNull(row.getRoleKeys());
        if (roleKeys == null) {
            dto.setRoleIds(List.of());
            return dto;
        }
        if (roleKeys.length() > 200) {
            throw new RowImportException("角色标识", "角色标识长度不能超过 200 个字符");
        }
        List<Long> roleIds = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (String rawKey : roleKeys.split(",", -1)) {
            String key = rawKey.strip();
            List<Long> matches = visibleRoleIds.get(key);
            if (key.isEmpty() || !seenKeys.add(key) || matches == null || matches.size() != 1) {
                throw new RowImportException("角色标识", "角色标识无效、重复或不可分配");
            }
            roleIds.add(matches.get(0));
        }
        dto.setRoleIds(roleIds);
        return dto;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateImportDto(SysUserAddDTO dto) {
        List<ConstraintViolation<SysUserAddDTO>> violations = new ArrayList<>(validator.validate(dto));
        if (violations.isEmpty()) {
            return;
        }
        ConstraintViolation<SysUserAddDTO> violation = violations.stream()
                .min(Comparator.comparing(item -> item.getPropertyPath().toString()))
                .orElseThrow();
        String property = violation.getPropertyPath().toString();
        switch (property) {
            case "username" -> throw new RowImportException("用户账号", "账号必填且需为 2 至 30 位字母、数字或下划线");
            case "mobile" -> throw new RowImportException("手机号码", "手机号必填且格式不正确");
            case "nickname" -> throw new RowImportException("用户昵称", "昵称不能超过 30 个字符");
            case "email" -> throw new RowImportException("邮箱", "邮箱格式不正确或长度超过 50 个字符");
            case "status" -> throw new RowImportException("用户状态", "状态值无效");
            default -> throw new RowImportException("", "数据格式不正确");
        }
    }

    private RowImportException safeServiceFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("用户名已存在")) {
            return new RowImportException("用户账号", "用户账号已存在");
        }
        if (message != null && message.contains("手机号已被其他用户使用")) {
            return new RowImportException("手机号码", "手机号已被其他用户使用");
        }
        if (message != null && (message.contains("不能新分配已停用角色") || message.contains("角色不存在")
                || message.contains("角色ID不能重复或为空"))) {
            return new RowImportException("角色标识", "角色无效、已停用或不可分配");
        }
        return new RowImportException("", "新增失败，请核对数据后重试");
    }

    private static final class RowImportException extends RuntimeException {
        private final String field;
        private final String reason;

        private RowImportException(String field, String reason) {
            super(reason);
            this.field = field;
            this.reason = reason;
        }
    }

    /** Exports only the current list scope, in bounded pages with one role query per page. */
    @Override
    public void exportUsers(SysUserQueryDTO query, HttpServletResponse response) throws Exception {
        long rowCount = this.count(buildUserFilters(query));
        if (rowCount > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("导出结果超过 10000 条，请缩小筛选范围");
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("用户列表.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName);
        try (ExcelWriter writer = FastExcel.write(response.getOutputStream(), SysUserExcel.class)
                .autoCloseStream(false).build()) {
            WriteSheet sheet = FastExcel.writerSheet("用户列表").build();
            if (rowCount == 0) {
                writer.write(List.of(), sheet);
                return;
            }
            long written = 0;
            long pageNumber = 1;
            while (written < rowCount) {
                Page<SysUser> page = new Page<>(pageNumber++, EXPORT_PAGE_SIZE, false);
                page = this.page(page, applyExportOrdering(buildUserFilters(query), query));
                List<SysUser> users = page.getRecords();
                if (users == null || users.isEmpty()) {
                    break;
                }
                int remaining = (int) Math.min(rowCount - written, MAX_EXPORT_ROWS - written);
                List<SysUser> exportPage = users.size() > remaining ? users.subList(0, remaining) : users;
                writer.write(buildExportRows(exportPage), sheet);
                written += exportPage.size();
            }
        }
    }

    private LambdaQueryWrapper<SysUser> applyExportOrdering(LambdaQueryWrapper<SysUser> wrapper,
            SysUserQueryDTO query) {
        boolean ascending = "asc".equalsIgnoreCase(query.getOrder());
        if ("updateTime".equals(query.getOrderBy())) {
            return wrapper.orderBy(true, ascending, SysUser::getUpdateTime).orderByAsc(SysUser::getUserId);
        }
        return wrapper.orderBy(true, ascending, SysUser::getCreateTime).orderByAsc(SysUser::getUserId);
    }

    private List<SysUserExcel> buildExportRows(List<SysUser> users) {
        List<Long> userIds = users.stream().map(SysUser::getUserId).toList();
        List<SysUserRole> associations = sysUserRoleService.list(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        Set<Long> roleIds = new HashSet<>();
        associations.forEach(association -> roleIds.add(association.getRoleId()));

        Map<Long, SysRole> roleById = new HashMap<>();
        if (!roleIds.isEmpty()) {
            sysRoleService.list(new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleId, roleIds))
                    .forEach(role -> roleById.put(role.getRoleId(), role));
        }
        Map<Long, List<SysRole>> rolesByUser = new HashMap<>();
        for (SysUserRole association : associations) {
            SysRole role = roleById.get(association.getRoleId());
            if (role != null) {
                rolesByUser.computeIfAbsent(association.getUserId(), key -> new ArrayList<>()).add(role);
            }
        }

        List<SysUserExcel> rows = new ArrayList<>(users.size());
        for (SysUser user : users) {
            SysUserExcel row = new SysUserExcel();
            row.setUsername(UserExcelSafety.safeCell(user.getUsername()));
            row.setNickname(UserExcelSafety.safeCell(user.getNickname()));
            row.setEmail(UserExcelSafety.safeCell(user.getEmail()));
            row.setMobile(UserExcelSafety.safeCell(user.getMobile()));
            row.setStatus(user.getStatus() != null && user.getStatus() == 1 ? "正常" : "停用");
            List<SysRole> userRoles = rolesByUser.getOrDefault(user.getUserId(), List.of());
            row.setRoleKeys(UserExcelSafety.safeCell(userRoles.stream()
                    .map(SysRole::getRoleKey).filter(StringUtils::hasText).sorted().reduce((a, b) -> a + "," + b)
                    .orElse("")));
            row.setRoleNames(UserExcelSafety.safeCell(userRoles.stream()
                    .map(SysRole::getRoleName).filter(StringUtils::hasText).sorted().reduce((a, b) -> a + "," + b)
                    .orElse("")));
            rows.add(row);
        }
        return rows;
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
        Long userId = currentUser.getUserId();

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
        Long userId = currentUser.getUserId();

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
        Long userId = currentUser.getUserId();

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
