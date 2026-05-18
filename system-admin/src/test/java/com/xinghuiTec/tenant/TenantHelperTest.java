package com.xinghuiTec.tenant;

import com.xinghuiTec.domain.entity.TenantEntity;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("租户助手测试")
public class TenantHelperTest {

    @Resource
    private SysUserMapper sysUserMapper;

    @Test
    @DisplayName("isEnable - 读取租户开关")
    void testIsEnable() {
        boolean enabled = TenantHelper.isEnable();
        System.out.println("租户功能: " + (enabled ? "启用" : "未启用"));
        System.out.println("✓ isEnable 测试通过");
    }

    @Test
    @DisplayName("动态租户 - set/get/clear")
    void testDynamicTenant() {
        TenantHelper.setDynamic("888888");
        assertEquals("888888", TenantHelper.getDynamic());
        TenantHelper.clearDynamic();
        assertNull(TenantHelper.getDynamic());
        System.out.println("✓ 动态租户 set/get/clear 通过");
    }

    @Test
    @DisplayName("动态租户 - dynamic() 自动清理")
    void testDynamicAutoCleanup() {
        TenantHelper.dynamic("999999", () -> {
            assertEquals("999999", TenantHelper.getDynamic());
        });
        assertNull(TenantHelper.getDynamic());
        System.out.println("✓ dynamic() 自动清理通过");
    }

    @Test
    @DisplayName("忽略租户 - ignore(Runnable)")
    void testIgnoreRunnable() {
        TenantHelper.ignore(() -> {
            Long count = sysUserMapper.selectCount(null);
            assertNotNull(count);
            System.out.println("忽略租户查询: " + count + " 条");
        });
        System.out.println("✓ ignore(Runnable) 通过");
    }

    @Test
    @DisplayName("忽略租户 - ignore(Supplier) 返回值")
    void testIgnoreSupplier() {
        Long count = TenantHelper.ignore(() -> sysUserMapper.selectCount(null));
        assertNotNull(count);
        System.out.println("忽略租户返回值: " + count);
        System.out.println("✓ ignore(Supplier) 通过");
    }

    @Test
    @DisplayName("忽略租户 - 嵌套不抛异常")
    void testIgnoreNested() {
        TenantHelper.ignore(() ->
            TenantHelper.ignore(() ->
                sysUserMapper.selectCount(null)
            )
        );
        System.out.println("✓ 嵌套 ignore 通过");
    }

    @Test
    @DisplayName("SysUser 继承 TenantEntity")
    void testEntityInheritance() {
        SysUser user = new SysUser();
        assertTrue(user instanceof TenantEntity, "SysUser 应为 TenantEntity 子类");
        user.setTenantId("123456");
        assertEquals("123456", user.getTenantId());
        System.out.println("✓ TenantEntity 继承链验证通过");
    }
}
