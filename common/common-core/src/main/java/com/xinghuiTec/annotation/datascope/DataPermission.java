package com.xinghuiTec.annotation.datascope;

import java.lang.annotation.*;

/**
 * 数据权限注解
 * 标记在 Mapper 方法上，用于声明该方法需要进行数据权限过滤
 *
 * <pre>
 * // 示例：只查看自己创建的数据
 * &#64;DataPermission({@DataColumn(key = "userId", value = "create_by")})
 * List&lt;SysUser&gt; selectUserList();
 * </pre>
 *
 * @author xinghuiTec
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataPermission {

    /** 列映射配置 */
    DataColumn[] value();
}
