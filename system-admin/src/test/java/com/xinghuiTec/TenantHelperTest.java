package com.xinghuiTec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysTenant;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.mapper.SysTenantMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.utils.SecurityUtils;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户助手 TenantHelper 单元测试
 * 验证忽略租户、动态租户、租户优先级、Redis Key前缀 等核心功能
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantHelperTest {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysTenantMapper sysTenantMapper;

    // ==================== isEnable 测试 ====================

    @Test
    @Order(1)
    @DisplayName("isEnable - 读取 tenant.enable 配置")
    public void testIsEnable() {
        boolean enabled = TenantHelper.isEnable();
        System.out.println("租户功能启用状态: " + enabled);
        System.out.println("✓ isEnable 测试通过");
    }

    // ==================== SecurityUtils 测试 ====================

    @Test
    @Order(2)
    @DisplayName("SecurityUtils - 未登录时 getLoginUser/isLogin 返回 null/false")
    public void testSecurityUtilsNotLogin() {
        assertFalse(SecurityUtils.isLogin(), "未登录时 isLogin 应为 false");
        assertNull(SecurityUtils.getLoginUser(), "未登录时 getLoginUser 应为 null");
        assertNull(SecurityUtils.getTenantId(), "未登录时 getTenantId 应为 null");
        assertNull(SecurityUtils.getUserId(), "未登录时 getUserId 应为 null");
        System.out.println("✓ SecurityUtils 未登录状态测试通过");
    }

    // ==================== ignore 机制测试 ====================

    @Test
    @Order(3)
    @DisplayName("ignore - Runnable 形式忽略租户隔离查询")
    public void testIgnoreRunnable() {
        TenantHelper.ignore(() -> {
            Long count = sysUserMapper.selectCount(null);
            System.out.println("忽略租户后查询到用户总数: " + count);
            assertNotNull(count, "查询结果不应为null");
        });
        System.out.println("✓ ignore(Runnable) 测试通过");
    }

    @Test
    @Order(4)
    @DisplayName("ignore - Supplier 形式忽略租户隔离并返回值")
    public void testIgnoreSupplier() {
        Long count = TenantHelper.ignore(() -> sysUserMapper.selectCount(null));
        System.out.println("忽略租户后查询到用户总数: " + count);
        assertNotNull(count, "查询结果不应为null");
        System.out.println("✓ ignore(Supplier) 测试通过");
    }

    @Test
    @Order(5)
    @DisplayName("ignore - 嵌套忽略不抛异常")
    public void testIgnoreNested() {
        TenantHelper.ignore(() -> {
            TenantHelper.ignore(() -> {
                Long count = sysUserMapper.selectCount(null);
                System.out.println("嵌套忽略中查询到用户总数: " + count);
            });
        });
        System.out.println("✓ 嵌套 ignore 未抛异常");
    }

    // ==================== 动态租户测试 ====================

    @Test
    @Order(6)
    @DisplayName("动态租户 - setDynamic / getDynamic / clearDynamic 完整流程")
    public void testDynamicSetGetClear() {
        String testTenantId = "999999";
        TenantHelper.setDynamic(testTenantId);

        assertEquals(testTenantId, TenantHelper.getDynamic(), "getDynamic 应返回设置的值");
        assertEquals(testTenantId, TenantHelper.getTenantId(), "getTenantId 应优先返回动态租户");

        TenantHelper.clearDynamic();
        assertNull(TenantHelper.getDynamic(), "清除后 getDynamic 应为 null");
        System.out.println("✓ 动态租户 set/get/clear 测试通过");
    }

    @Test
    @Order(7)
    @DisplayName("动态租户 - dynamic() 方法自动清理")
    public void testDynamicAutoCleanup() {
        TenantHelper.dynamic("888888", () -> {
            assertEquals("888888", TenantHelper.getDynamic(), "dynamic 块内应为设置的租户");
            Long count = sysUserMapper.selectCount(null);
            System.out.println("动态租户块内查询到用户数: " + count);
        });

        assertNull(TenantHelper.getDynamic(), "离开 dynamic 块后应自动清理");
        System.out.println("✓ dynamic() 自动清理测试通过");
    }

    @Test
    @Order(8)
    @DisplayName("动态租户 - dynamic() Supplier 形式带返回值")
    public void testDynamicSupplier() {
        Long result = TenantHelper.dynamic("777777", () -> {
            assertEquals("777777", TenantHelper.getDynamic());
            return sysUserMapper.selectCount(null);
        });

        assertNotNull(result, "dynamic Supplier 应正确返回值");
        assertNull(TenantHelper.getDynamic(), "离开块后应自动清理");
        System.out.println("✓ dynamic(Supplier) 测试通过，返回值: " + result);
    }

    // ==================== 租户优先级测试 ====================

    @Test
    @Order(9)
    @DisplayName("租户优先级 - 动态租户优先级高于登录用户租户")
    public void testTenantPriority() {
        // 未登录时，仅动态租户有效
        TenantHelper.setDynamic("666666");
        if (TenantHelper.isEnable()) {
            String tenantId = TenantHelper.getTenantId();
            assertEquals("666666", tenantId, "getTenantId 应优先返回动态租户");
        }
        TenantHelper.clearDynamic();
        System.out.println("✓ 租户优先级测试通过");
    }

    // ==================== Redis Key 前缀测试 ====================

    @Test
    @Order(10)
    @DisplayName("Redis Key - 全局 key（global:开头）不添加租户前缀")
    public void testGlobalKeyNoPrefix() {
        String globalKey = "global:captcha_codes:13800138000";
        String result = TenantHelper.addTenantPrefix(globalKey);
        assertEquals(globalKey, result, "全局 key 不应添加租户前缀");
        System.out.println("✓ 全局 key 前缀测试通过");
    }

    @Test
    @Order(11)
    @DisplayName("Redis Key - 业务 key 正确添加和移除租户前缀")
    public void testBusinessKeyPrefix() {
        TenantHelper.setDynamic("123456");
        String businessKey = "admin:userinfo:user123";

        String prefixed = TenantHelper.addTenantPrefix(businessKey);
        if (TenantHelper.isEnable()) {
            assertTrue(prefixed.startsWith("123456:"), "业务 key 应添加租户前缀");
            System.out.println("添加前缀后: " + prefixed);

            String removed = TenantHelper.removeTenantPrefix(prefixed);
            assertEquals(businessKey, removed, "移除前缀后应还原为原始 key");
            System.out.println("移除前缀后: " + removed);
        } else {
            assertEquals(businessKey, prefixed, "未启用时不添加前缀");
        }
        TenantHelper.clearDynamic();
        System.out.println("✓ 业务 key 前缀测试通过");
    }

    // ==================== 跨租户查询实战测试 ====================

    @Test
    @Order(12)
    @DisplayName("跨租户查询 - ignore 模式下查询所有租户的租户数据")
    public void testQueryAllTenants() {
        List<SysTenant> tenants = TenantHelper.ignore(() ->
            sysTenantMapper.selectList(null)
        );
        System.out.println("所有租户数量: " + tenants.size());
        tenants.forEach(t -> System.out.println("  - " + t.getTenantId() + ": " + t.getCompanyName()));
        assertNotNull(tenants, "租户列表不应为 null");
        System.out.println("✓ 跨租户查询租户列表通过");
    }

    @Test
    @Order(13)
    @DisplayName("动态租户查询 - dynamic 模式下查询指定租户的用户")
    public void testQueryUsersByDynamicTenant() {
        TenantHelper.dynamic("000000", () -> {
            List<SysUser> users = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getTenantId, "000000")
                    .last("LIMIT 5")
            );
            System.out.println("默认租户用户数（前5条）: " + users.size());
            users.forEach(u -> System.out.println("  - " + u.getUsername()
                    + " (手机: " + u.getMobile()
                    + ", 租户: " + u.getTenantId() + ")"));

            if (!users.isEmpty()) {
                assertEquals("000000", users.get(0).getTenantId(), "用户租户应为默认租户");
            }
        });
        System.out.println("✓ 动态租户查询用户通过");
    }
}
