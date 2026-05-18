package com.xinghuiTec.login;

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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("登录模块测试")
public class LoginServiceTest {

    @Autowired
    private LoginService loginService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Resource
    private RedisCacheUtils redisCacheUtils;

    private static final String TEST_PHONE = "13800138000";
    private static final String TEST_PASSWORD = "123456";

    @Test
    @Order(1)
    @DisplayName("手机号格式校验 - 正确格式通过")
    void testPhoneFormatValid() {
        loginDTO dto = new loginDTO();
        dto.setPhone("13912345678");
        dto.setPassword("123456");
        try {
            loginService.login(dto);
        } catch (Exception e) {
            assertFalse(e.getMessage().contains("手机号格式不正确"));
        }
        System.out.println("✓ 正确手机号格式通过");
    }

    @Test
    @Order(2)
    @DisplayName("手机号格式校验 - 错误格式拦截")
    void testPhoneFormatInvalid() {
        loginDTO dto = new loginDTO();
        dto.setPhone("12345");
        dto.setPassword("123456");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> loginService.login(dto));
        assertTrue(ex.getMessage().contains("手机号格式不正确"));
        System.out.println("✓ 错误手机号格式拦截: " + ex.getMessage());
    }

    @Test
    @Order(3)
    @DisplayName("手机号格式校验 - 空手机号拦截")
    void testPhoneEmpty() {
        loginDTO dto = new loginDTO();
        dto.setPhone("");
        dto.setPassword("123456");
        assertThrows(Exception.class, () -> loginService.login(dto));
        System.out.println("✓ 空手机号拦截");
    }

    @Test
    @Order(4)
    @DisplayName("跨租户查询 - 按手机号查找用户")
    void testQueryUserAcrossTenants() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, TEST_PHONE))
        );
        if (user != null) {
            assertEquals(TEST_PHONE, user.getMobile());
            assertNotNull(user.getTenantId());
            System.out.println("✓ 找到用户: " + user.getUsername() + ", 租户: " + user.getTenantId());
        } else {
            System.out.println("跳过: 手机号 " + TEST_PHONE + " 未注册");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Spring Security 认证 - 手机号+密码")
    void testAuthenticateByPhone() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, TEST_PHONE))
        );
        if (user == null) { System.out.println("跳过: 无测试用户"); return; }

        UsernamePasswordAuthenticationToken token =
            new UsernamePasswordAuthenticationToken(TEST_PHONE, TEST_PASSWORD);
        Authentication auth = authenticationManager.authenticate(token);

        assertNotNull(auth);
        assertTrue(auth.getPrincipal() instanceof loginUser);
        loginUser lu = (loginUser) auth.getPrincipal();
        assertEquals(TEST_PHONE, lu.getUser().getMobile());
        assertNotNull(lu.getTenantId());
        System.out.println("✓ 认证通过, 用户: " + lu.getUser().getUserId() + ", 租户: " + lu.getTenantId());
    }

    @Test
    @Order(6)
    @DisplayName("完整登录流程 - JWT + Redis")
    void testFullLoginFlow() {
        SysUser user = TenantHelper.ignore(() ->
            sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, TEST_PHONE))
        );
        if (user == null) { System.out.println("跳过: 无测试用户"); return; }

        loginDTO dto = new loginDTO();
        dto.setPhone(TEST_PHONE);
        dto.setPassword(TEST_PASSWORD);

        String jwt = loginService.login(dto);
        assertNotNull(jwt);
        assertTrue(JwtUtil.verify(jwt));

        cn.hutool.jwt.JWT parsed = JwtUtil.parseToken(jwt);
        String userId = parsed.getPayload("userId").toString();
        Object tenantId = parsed.getPayload("tenantId");

        // 验证 Redis 中有登录信息
        loginUser cached = redisCacheUtils.getCacheObject(RedisConstants.ADMIN_LOGIN_PREFIX + userId);
        assertNotNull(cached);
        assertEquals(TEST_PHONE, cached.getUser().getMobile());

        System.out.println("✓ 完整登录通过, userId=" + userId + ", tenantId=" + tenantId);
    }

    @Test
    @Order(7)
    @DisplayName("登录失败 - 未注册手机号")
    void testLoginFailUnregistered() {
        loginDTO dto = new loginDTO();
        dto.setPhone("19900000001");
        dto.setPassword("anypassword");
        assertThrows(Exception.class, () -> loginService.login(dto));
        System.out.println("✓ 未注册手机号拦截");
    }

    @Test
    @Order(8)
    @DisplayName("登录失败 - 密码错误")
    void testLoginFailWrongPassword() {
        loginDTO dto = new loginDTO();
        dto.setPhone(TEST_PHONE);
        dto.setPassword("wrong_password_xxx");
        assertThrows(Exception.class, () -> loginService.login(dto));
        System.out.println("✓ 密码错误拦截");
    }
}
