package com.xinghuiTec.datascope;

import com.xinghuiTec.annotation.datascope.DataColumn;
import com.xinghuiTec.annotation.datascope.DataPermission;
import com.xinghuiTec.config.datascope.DataPermissionHelper;
import com.xinghuiTec.enums.DataScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("数据权限模块测试")
public class DataPermissionTest {

    @Test
    @DisplayName("DataScopeType 枚举")
    void testDataScopeType() {
        assertEquals("1", DataScopeType.ALL.getCode());
        assertEquals("5", DataScopeType.SELF.getCode());
        assertEquals(DataScopeType.ALL, DataScopeType.findCode("1"));
        assertEquals(DataScopeType.SELF, DataScopeType.findCode("5"));
        assertNull(DataScopeType.findCode("99"));
        System.out.println("✓ DataScopeType 枚举验证通过");
    }

    @Test
    @DisplayName("@DataPermission 注解属性")
    void testAnnotation() throws NoSuchMethodException {
        DataPermission dp = TestMapper.class
            .getMethod("selectList")
            .getAnnotation(DataPermission.class);

        assertNotNull(dp);
        assertEquals(1, dp.value().length);
        assertEquals("userId", dp.value()[0].key());
        assertEquals("create_by", dp.value()[0].value());
        System.out.println("✓ @DataPermission 注解验证通过");
    }

    @Test
    @DisplayName("DataPermissionHelper set/get/remove")
    void testHelper() {
        DataPermission dp = TestMapper.class
            .getMethods()[0].getAnnotation(DataPermission.class);

        DataPermissionHelper.setPermission(dp);
        assertNotNull(DataPermissionHelper.getPermission());

        DataPermissionHelper.removePermission();
        assertNull(DataPermissionHelper.getPermission());
        System.out.println("✓ DataPermissionHelper set/get/remove 通过");
    }

    @Test
    @DisplayName("DataPermissionHelper ignore() 不抛异常")
    void testHelperIgnore() {
        DataPermissionHelper.ignore(() -> {
            assertNull(DataPermissionHelper.getPermission());
        });
        System.out.println("✓ ignore() 通过");
    }

    interface TestMapper {
        @DataPermission({@DataColumn(key = "userId", value = "create_by")})
        void selectList();
    }
}
