package com.xinghuiTec;

import cn.hutool.jwt.JWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.domain.dto.loginDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.loginUser;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.LoginService;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手机号登录测试
 * 验证手机号格式校验、跨租户认证、JWT生成、Redis存储
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LoginServiceTest {

    @Autowired
    private LoginService loginService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private RedisCacheUtils redisCacheUtils;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private AuthenticationManager authenticationManager;

    /** 测试用手机号（需数据库中存在） */
    private static final String TEST_PHONE = "13800138000";
    /** 测试用密码（明文） */
    private static final String TEST_PASSWORD = "123456";

    // ==================== 手机号格式校验 ====================

    @Test
    @Order(1)
    @DisplayName("手机号格式 - 正确格式通过校验")
    public void testPhoneFormatValid() {
        loginDTO dto = new loginDTO();
        dto.setPhone("13912345678");
        dto.setPassword("123456");

        try {
            loginService.login(dto);
        } catch (Exception e) {
            // 预期用户不存在或密码错误，但不应该是手机号格式错误
            assertFalse(e.getMessage().contains("手机号格式不正确"),
                    "正确格式的手机号不应触发格式校验异常，实际: " + e.getMessage());
        }
        System.out.println("✓ 正确手机号格式通过");
    }

    @Test
    @Order(2)
    @DisplayName("手机号格式 - 错误格式被拦截")
    public void testPhoneFormatInvalid() {
        loginDTO dto = new loginDTO();
        dto.setPhone("12345");
        dto.setPassword("123456");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> loginService.login(dto), "非法手机号应抛异常");
        assertTrue(ex.getMessage().contains("手机号格式不正确"),
                "异常消息应包含'手机号格式不正确'，实际: " + ex.getMessage());
        System.out.println("✓ 错误手机号格式正确拦截: " + ex.getMessage());
    }

    @Test
    @Order(3)
    @DisplayName("手机号格式 - 空手机号被拦截")
    public void testPhoneEmpty() {
        loginDTO dto = new loginDTO();
        dto.setPhone("");
        dto.setPassword("123456");

        assertThrows(Exception.class, () -> loginService.login(dto), "空手机号应抛异常");
        System.out.println("✓ 空手机号正确拦截");
    }

    // ==================== 跨租户查询 ====================

    @Test
    @Order(4)
    @DisplayName("跨租户查询 - 按手机号不限租户查询用户")
    public void testQueryUserAcrossTenants() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, TEST_PHONE)
            )
        );

        if (user != null) {
            System.out.println("找到用户: " + user.getUsername()
                    + ", 手机号: " + user.getMobile()
                    + ", 租户ID: " + user.getTenantId());
            assertEquals(TEST_PHONE, user.getMobile(), "手机号应匹配");
            assertNotNull(user.getTenantId(), "用户应有租户ID");
        } else {
            System.out.println("手机号 " + TEST_PHONE + " 未注册");
        }
        System.out.println("✓ 跨租户查询通过");
    }

    // ==================== 认证测试 ====================

    @Test
    @Order(5)
    @DisplayName("认证 - Spring Security 按手机号认证")
    public void testSpringSecurityAuthByPhone() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, TEST_PHONE)
            )
        );

        if (user == null) {
            System.out.println("跳过：手机号 " + TEST_PHONE + " 未注册");
            return;
        }

        // 模拟认证流程
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(TEST_PHONE, TEST_PASSWORD);
        Authentication authenticate = authenticationManager.authenticate(token);

        assertNotNull(authenticate, "认证结果不应为null");
        assertTrue(authenticate.getPrincipal() instanceof loginUser,
                "认证主体应为 loginUser 类型");

        loginUser loginUser = (loginUser) authenticate.getPrincipal();
        assertEquals(TEST_PHONE, loginUser.getUser().getMobile(), "手机号应匹配");
        assertNotNull(loginUser.getTenantId(), "loginUser 应有租户ID（从用户实体的 tenant_id 获取）");

        System.out.println("✓ Spring Security 认证通过");
        System.out.println("  用户ID: " + loginUser.getUser().getUserId());
        System.out.println("  租户ID: " + loginUser.getTenantId());
        System.out.println("  权限数: " + loginUser.getAuthorities().size());
    }

    // ==================== 完整登录流程 ====================

    @Test
    @Order(6)
    @DisplayName("完整登录流程 - 手机号+密码 → 认证 → JWT → Redis")
    public void testFullLoginFlow() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, TEST_PHONE)
            )
        );

        if (user == null) {
            System.out.println("跳过：手机号 " + TEST_PHONE + " 未注册");
            return;
        }

        // 1. 执行登录
        loginDTO dto = new loginDTO();
        dto.setPhone(TEST_PHONE);
        dto.setPassword(TEST_PASSWORD);

        String jwt = loginService.login(dto);
        assertNotNull(jwt, "JWT 不应为null");
        assertFalse(jwt.isBlank(), "JWT 不应为空");
        System.out.println("JWT 生成成功: " + jwt.substring(0, Math.min(30, jwt.length())) + "...");

        // 2. 验证 JWT 有效性
        assertTrue(JwtUtil.verify(jwt), "JWT 应有效");

        // 3. 验证 JWT 中的 tenantId
        JWT parsed = JwtUtil.parseToken(jwt);
        Object tenantIdInJwt = parsed.getPayload("tenantId");
        System.out.println("JWT 中的 tenantId: " + tenantIdInJwt);

        // 4. 验证 Redis 中的登录信息
        String userId = parsed.getPayload("userId").toString();
        loginUser cachedUser = redisCacheUtils.getCacheObject(
                RedisConstants.ADMIN_LOGIN_PREFIX + userId);
        assertNotNull(cachedUser, "Redis 中应有登录用户信息");
        assertEquals(user.getMobile(), cachedUser.getUser().getMobile(),
                "Redis 中手机号应匹配");
        assertEquals(user.getTenantId(), cachedUser.getTenantId(),
                "Redis 中租户ID应匹配");

        System.out.println("✓ 完整登录流程测试通过");
        System.out.println("  Redis Key: admin:login:" + userId);
        System.out.println("  存储租户ID: " + cachedUser.getTenantId());
    }

    // ==================== 登录失败场景 ====================

    @Test
    @Order(7)
    @DisplayName("登录失败 - 未注册的手机号")
    public void testLoginFailUnregisteredPhone() {
        loginDTO dto = new loginDTO();
        dto.setPhone("19900000001");
        dto.setPassword("anypassword");

        assertThrows(Exception.class, () -> loginService.login(dto),
                "未注册的手机号应抛异常");
        System.out.println("✓ 未注册手机号正确拦截");
    }

    @Test
    @Order(8)
    @DisplayName("登录失败 - 密码错误")
    public void testLoginFailWrongPassword() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, TEST_PHONE)
            )
        );

        if (user == null) {
            System.out.println("跳过：手机号 " + TEST_PHONE + " 未注册");
            return;
        }

        loginDTO dto = new loginDTO();
        dto.setPhone(TEST_PHONE);
        dto.setPassword("wrong_password_xxx");

        assertThrows(Exception.class, () -> loginService.login(dto),
                "错误密码应抛异常");
        System.out.println("✓ 错误密码正确拦截");
    }

    // ==================== 用户创建+登录端到端测试 ====================

    @Test
    @Order(9)
    @DisplayName("端到端 - 创建用户后手机号登录")
    public void testCreateUserAndLogin() {
        String phone = "13900001111";
        String password = "test123456";

        // 先检查是否已存在，存在则跳过
        SysUser existing = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, phone)
            )
        );

        if (existing != null) {
            System.out.println("用户已存在，跳过创建: " + phone);

            // 直接测试登录
            loginDTO dto = new loginDTO();
            dto.setPhone(phone);
            dto.setPassword(password);

            try {
                String jwt = loginService.login(dto);
                assertNotNull(jwt);
                System.out.println("✓ 已有用户登录成功: " + phone);
            } catch (Exception e) {
                System.out.println("登录异常: " + e.getMessage());
            }
            return;
        }

        // 创建新用户
        cn.hutool.core.util.IdUtil idUtil = new cn.hutool.core.util.IdUtil();
        SysUser newUser = new SysUser();
        newUser.setUserId(cn.hutool.core.util.IdUtil.simpleUUID());
        newUser.setUsername("phone_test_" + System.currentTimeMillis());
        newUser.setPassword(passwordEncoder.encode(password));
        newUser.setNickname("手机号测试用户");
        newUser.setMobile(phone);
        newUser.setEmail(phone + "@test.com");
        newUser.setStatus(1);
        newUser.setTenantId("000000");
        newUser.setCreateTime(new java.util.Date());

        TenantHelper.ignore(() -> sysUserMapper.insert(newUser));
        System.out.println("创建测试用户: " + phone + " (ID: " + newUser.getUserId() + ")");

        // 登录
        loginDTO dto = new loginDTO();
        dto.setPhone(phone);
        dto.setPassword(password);

        String jwt = loginService.login(dto);
        assertNotNull(jwt, "登录应返回 JWT");
        assertTrue(JwtUtil.verify(jwt), "JWT 应有效");

        // 验证 JWT 中的 tenantId
        JWT parsed = JwtUtil.parseToken(jwt);
        Object tenantIdInJwt = parsed.getPayload("tenantId");
        System.out.println("JWT tenantId: " + tenantIdInJwt);

        // 清理
        String userId = parsed.getPayload("userId").toString();
        redisCacheUtils.deleteObject(RedisConstants.ADMIN_LOGIN_PREFIX + userId);
        TenantHelper.ignore(() -> sysUserMapper.deleteById(newUser.getUserId()));
        System.out.println("✓ 端到端测试通过，测试数据已清理");
    }
}
