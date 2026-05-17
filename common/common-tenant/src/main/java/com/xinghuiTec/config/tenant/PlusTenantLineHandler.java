package com.xinghuiTec.config.tenant;

import cn.hutool.core.collection.ListUtil;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.xinghuiTec.config.TenantProperties;
import com.xinghuiTec.utils.TenantHelper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;

import java.util.List;

/**
 * 自定义租户行处理器
 * 基于 MyBatis-Plus TenantLineHandler 实现 SQL 自动租户过滤
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
        if (tenantId == null || tenantId.isBlank()) {
            log.error("无法获取有效的租户id -> Null");
            return new NullValue();
        }
        return new StringValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        String tenantId = TenantHelper.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            // 不需要过滤租户的表
            List<String> excludes = tenantProperties.getExcludes();
            List<String> tables = ListUtil.toList(
                "gen_table",
                "gen_table_column"
            );
            tables.addAll(excludes);
            return ListUtil.toList(tables).contains(tableName);
        }
        return true;
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

}
