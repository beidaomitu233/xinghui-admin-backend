package com.xinghuiTec.config.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.xinghuiTec.config.TenantProperties;
import com.xinghuiTec.utils.TenantHelper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义租户处理器
 * 用于 MyBatis-Plus SQL 拦截器自动添加 tenant_id 条件
 *
 * @author xinghuiTec
 */
@Slf4j
@AllArgsConstructor
public class PlusTenantLineHandler implements TenantLineHandler {

    private final TenantProperties tenantProperties;

    @Override
    public Expression getTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            log.error("无法获取有效的租户id -> Null");
            return new NullValue();
        }
        return new StringValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        String tenantId = TenantHelper.getTenantId();
        // 如果没有租户ID，所有表都忽略租户过滤
        if (tenantId == null || tenantId.isEmpty()) {
            return true;
        }

        // 不需要过滤租户的表
        List<String> excludes = tenantProperties.getExcludes();
        if (excludes == null) {
            excludes = new ArrayList<>();
        }

        // 非业务表（代码生成等）
        List<String> tables = new ArrayList<>();
        tables.add("gen_table");
        tables.add("gen_table_column");
        tables.addAll(excludes);

        // 判断是否匹配
        for (String table : tables) {
            if (table.equalsIgnoreCase(tableName)) {
                return true;
            }
        }
        return false;
    }
}
