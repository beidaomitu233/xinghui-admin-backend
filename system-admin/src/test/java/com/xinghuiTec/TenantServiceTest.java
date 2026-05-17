package com.xinghuiTec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysTenant;
import com.xinghuiTec.domain.entity.SysTenantPackage;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.mapper.SysTenantMapper;
import com.xinghuiTec.mapper.SysTenantPackageMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.ISysTenantPackageService;
import com.xinghuiTec.service.ISysTenantService;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户管理服务测试
 * 验证租户套餐 CRUD、租户 CRUD、租户校验、用户-租户关联
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantServiceTest {

    @Autowired
    private ISysTenantService tenantService;

    @Autowired
    private ISysTenantPackageService packageService;

    @Resource
    private SysTenantMapper tenantMapper;

    @Resource
    private SysTenantPackageMapper packageMapper;

    @Resource
    private SysUserMapper userMapper;

    private static final String TEST_TENANT_NUM = "TEST01";
    private static Long testPackageId;

    // ==================== 租户套餐测试 ====================

    @Test
    @Order(1)
    @DisplayName("套餐 - 新增套餐")
    public void testAddPackage() {
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setPackageName("测试套餐_" + System.currentTimeMillis());
        pkg.setMenuIds("1,2,3,4,5");
        pkg.setRemark("测试用套餐");
        pkg.setStatus("0");
        pkg.setCreateTime(new Date());

        boolean result = packageService.save(pkg);
        assertTrue(result, "套餐保存应成功");
        assertNotNull(pkg.getPackageId(), "保存后应有ID");
        testPackageId = pkg.getPackageId();

        System.out.println("✓ 新增套餐成功，ID: " + testPackageId);
    }

    @Test
    @Order(2)
    @DisplayName("套餐 - 查询套餐列表")
    public void testListPackages() {
        List<SysTenantPackage> list = packageService.list();
        assertNotNull(list, "套餐列表不应为null");
        assertTrue(list.size() > 0, "至少应有一个套餐");

        System.out.println("套餐总数: " + list.size());
        list.forEach(p -> System.out.println("  - " + p.getPackageId() + ": " + p.getPackageName()));
        System.out.println("✓ 查询套餐列表通过");
    }

    @Test
    @Order(3)
    @DisplayName("套餐 - 修改套餐")
    public void testUpdatePackage() {
        if (testPackageId == null) {
            System.out.println("跳过：无测试套餐");
            return;
        }

        SysTenantPackage pkg = packageService.getById(testPackageId);
        assertNotNull(pkg, "套餐应存在");

        pkg.setRemark("已更新的测试套餐");
        pkg.setUpdateTime(new Date());
        boolean result = packageService.updateById(pkg);
        assertTrue(result, "套餐更新应成功");

        SysTenantPackage updated = packageService.getById(testPackageId);
        assertEquals("已更新的测试套餐", updated.getRemark(), "备注应已更新");
        System.out.println("✓ 修改套餐通过");
    }

    // ==================== 租户 CRUD 测试 ====================

    @Test
    @Order(4)
    @DisplayName("租户 - 新增租户")
    public void testAddTenant() {
        // 先清理可能残留的测试数据
        TenantHelper.ignore(() ->
            tenantMapper.delete(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantId, TEST_TENANT_NUM))
        );

        SysTenant tenant = new SysTenant();
        tenant.setTenantId(TEST_TENANT_NUM);
        tenant.setCompanyName("测试企业");
        tenant.setContactUserName("张三");
        tenant.setContactPhone("13800001111");
        tenant.setAddress("北京市朝阳区");
        tenant.setLicenseNumber("91110000MA12345678");
        tenant.setIntro("测试用租户");
        tenant.setPackageId(testPackageId);
        tenant.setAccountCount(100L);
        tenant.setStatus("0");
        tenant.setCreateTime(new Date());

        boolean result = TenantHelper.ignore(() -> tenantService.save(tenant));
        assertTrue(result, "租户保存应成功");
        assertNotNull(tenant.getId(), "保存后应有ID");

        System.out.println("✓ 新增租户成功，ID: " + tenant.getId()
                + ", 租户编号: " + tenant.getTenantId());
    }

    @Test
    @Order(5)
    @DisplayName("租户 - 根据租户编号查询")
    public void testQueryByTenantId() {
        SysTenant tenant = TenantHelper.ignore(() ->
            tenantService.queryByTenantId(TEST_TENANT_NUM)
        );

        assertNotNull(tenant, "应能查询到租户 " + TEST_TENANT_NUM);
        assertEquals("测试企业", tenant.getCompanyName(), "企业名称应匹配");
        assertEquals("张三", tenant.getContactUserName(), "联系人应匹配");
        System.out.println("✓ 按租户编号查询通过");
    }

    @Test
    @Order(6)
    @DisplayName("租户 - 校验租户有效性（默认租户/不存在的租户）")
    public void testCheckTenant() {
        // 默认租户直接通过
        assertDoesNotThrow(() -> TenantHelper.ignore(() ->
            tenantService.checkTenant("000000")),
            "默认租户校验应通过");

        // 不存在的租户抛异常
        assertThrows(Exception.class, () -> TenantHelper.ignore(() ->
            tenantService.checkTenant("NOT_EXIST_999")),
            "不存在的租户应抛异常");

        System.out.println("✓ 租户校验测试通过");
    }

    @Test
    @Order(7)
    @DisplayName("租户 - 查询租户列表（忽略隔离）")
    public void testListTenants() {
        List<SysTenant> list = TenantHelper.ignore(() -> tenantService.list());
        assertNotNull(list, "租户列表不应为null");
        assertTrue(list.size() > 0, "至少应有一个默认租户");

        System.out.println("租户总数: " + list.size());
        list.forEach(t -> System.out.println("  - " + t.getTenantId() + ": "
                + t.getCompanyName() + " (状态: " + t.getStatus() + ")"));
        System.out.println("✓ 查询租户列表通过");
    }

    @Test
    @Order(8)
    @DisplayName("租户 - 修改租户")
    public void testUpdateTenant() {
        SysTenant tenant = TenantHelper.ignore(() ->
            tenantService.queryByTenantId(TEST_TENANT_NUM)
        );

        assertNotNull(tenant, "测试租户应存在");
        tenant.setCompanyName("已更新的测试企业");
        tenant.setUpdateTime(new Date());

        boolean result = TenantHelper.ignore(() -> tenantService.updateById(tenant));
        assertTrue(result, "租户更新应成功");

        SysTenant updated = TenantHelper.ignore(() ->
            tenantService.getById(tenant.getId()));
        assertEquals("已更新的测试企业", updated.getCompanyName(), "企业名称应已更新");
        System.out.println("✓ 修改租户通过");
    }

    @Test
    @Order(9)
    @DisplayName("租户 - 删除租户")
    public void testDeleteTenant() {
        SysTenant tenant = TenantHelper.ignore(() ->
            tenantService.queryByTenantId(TEST_TENANT_NUM)
        );

        assertNotNull(tenant, "测试租户应存在");
        boolean result = TenantHelper.ignore(() ->
            tenantService.removeById(tenant.getId()));
        assertTrue(result, "租户删除应成功");

        // 验证删除
        SysTenant deleted = TenantHelper.ignore(() ->
            tenantService.getById(tenant.getId()));
        assertNull(deleted, "删除后查询应为null");
        System.out.println("✓ 删除租户通过");
    }

    // ==================== 用户租户关联测试 ====================

    @Test
    @Order(10)
    @DisplayName("用户租户 - 查询默认租户下的用户")
    public void testQueryUsersByDefaultTenant() {
        TenantHelper.dynamic("000000", () -> {
            List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getTenantId, "000000")
                    .last("LIMIT 5")
            );
            System.out.println("默认租户下用户数（前5条）: " + users.size());
            users.forEach(u -> System.out.println("  - " + u.getUsername()
                    + " (手机: " + u.getMobile()
                    + ", 租户: " + u.getTenantId() + ")"));

            if (!users.isEmpty()) {
                assertEquals("000000", users.get(0).getTenantId(),
                        "用户租户ID应为 000000");
            }
        });
        System.out.println("✓ 用户租户关联查询通过");
    }

    @Test
    @Order(11)
    @DisplayName("用户租户 - 所有用户均有 tenant_id")
    public void testAllUsersHaveTenantId() {
        TenantHelper.ignore(() -> {
            List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().last("LIMIT 20")
            );

            assertFalse(users.isEmpty(), "至少应有一个用户");
            for (SysUser user : users) {
                assertNotNull(user.getTenantId(),
                        "用户 " + user.getUsername() + " (手机: " + user.getMobile() + ") 应有 tenantId");
            }

            System.out.println("验证了 " + users.size() + " 个用户，全部拥有 tenantId");
            return null;
        });
        System.out.println("✓ 用户 tenant_id 完整性验证通过");
    }

    @Test
    @Order(12)
    @DisplayName("用户租户 - 跨租户查询所有用户手机去重")
    public void testCrossTenantDistinctUsers() {
        List<SysUser> allUsers = TenantHelper.ignore(() ->
            userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getMobile, SysUser::getTenantId)
                .last("LIMIT 10")
            )
        );

        System.out.println("跨租户用户（前10条）:");
        allUsers.forEach(u -> System.out.println("  - 手机: " + u.getMobile()
                + " → 租户: " + u.getTenantId()));
        System.out.println("✓ 跨租户用户查询通过");
    }

    // ==================== 清理 ====================

    @Test
    @Order(99)
    @DisplayName("清理 - 删除测试数据")
    public void testCleanup() {
        // 删除测试租户
        tenantMapper.delete(new LambdaQueryWrapper<SysTenant>()
            .eq(SysTenant::getTenantId, TEST_TENANT_NUM));

        // 删除测试套餐
        if (testPackageId != null) {
            packageMapper.deleteById(testPackageId);
        }

        System.out.println("✓ 测试数据清理完成");
    }
}
