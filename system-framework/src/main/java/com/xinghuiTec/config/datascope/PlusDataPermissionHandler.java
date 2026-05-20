package com.xinghuiTec.config.datascope;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.xinghuiTec.annotation.datascope.DataColumn;
import com.xinghuiTec.annotation.datascope.DataPermission;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.loginUser;
import com.xinghuiTec.enums.DataScopeType;
import com.xinghuiTec.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;

/**
 * 数据权限 SQL 处理器
 * 根据当前用户角色数据范围构建 WHERE 条件
 *
 * @author xinghuiTec
 */
@Slf4j
public class PlusDataPermissionHandler {

    /**
     * 构建数据过滤 SQL 片段
     *
     * @param where    原始 WHERE 表达式
     * @param isSelect 是否为查询语句（查询用 OR 拼接，更新/删除用 AND 拼接）
     * @return 添加了数据过滤条件的新 WHERE 表达式
     */
    public Expression getSqlSegment(Expression where, boolean isSelect) {
        try {
            DataPermission dataPermission = DataPermissionHelper.getPermission();
            if (dataPermission == null) {
                return where;
            }

            loginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null) {
                return where;
            }

            // 超级管理员或租户管理员 → 不过滤数据
            if (isSuperAdmin(loginUser) || isTenantAdmin(loginUser)) {
                return where;
            }

            // 获取用户角色的数据范围
            DataScopeType scopeType = getDataScopeType(loginUser);
            if (scopeType == null || scopeType == DataScopeType.ALL) {
                return where;
            }

            // 构建过滤条件
            Expression filterExpr = buildFilterExpression(dataPermission, loginUser, scopeType);
            if (filterExpr == null) {
                return where;
            }

            // 用括号包裹，与原 WHERE AND 连接
            ParenthesedExpressionList<Expression> parens = new ParenthesedExpressionList<>(filterExpr);
            if (ObjectUtil.isNotNull(where)) {
                return new AndExpression(where, parens);
            }
            return parens;

        } catch (JSQLParserException e) {
            log.error("数据权限 SQL 解析异常: {}", e.getMessage());
            return where;
        } finally {
            DataPermissionHelper.removePermission();
        }
    }

    /**
     * 构建过滤表达式：create_by = 'userId'
     */
    private Expression buildFilterExpression(DataPermission dataPermission, loginUser loginUser,
                                              DataScopeType scopeType) throws JSQLParserException {
        if (scopeType != DataScopeType.SELF) {
            return null;
        }

        for (DataColumn col : dataPermission.value()) {
            // SELF 模式：create_by = 当前用户ID
            String columnName = col.value();
            Long userId = loginUser.getUser().getUserId();
            return new EqualsTo(new Column(columnName), new StringValue(String.valueOf(userId)));
        }
        return null;
    }

    /** 检查是否为超级管理员 */
    private boolean isSuperAdmin(loginUser loginUser) {
        // 检查角色中是否有 superadmin roleKey
        if (CollUtil.isNotEmpty(loginUser.getPermissions())) {
            return loginUser.getPermissions().contains(TenantConstants.SUPER_ADMIN_ROLE_KEY);
        }
        return false;
    }

    /** 检查是否为租户管理员 */
    private boolean isTenantAdmin(loginUser loginUser) {
        if (CollUtil.isNotEmpty(loginUser.getPermissions())) {
            return loginUser.getPermissions().contains(TenantConstants.TENANT_ADMIN_ROLE_KEY);
        }
        return false;
    }

    /** 获取用户第一个角色的数据范围 */
    private DataScopeType getDataScopeType(loginUser loginUser) {
        // 从 SecurityUtils 获取用户角色信息
        // 由于 loginUser 没有直接存 dataScope，我们需要从 roles 列表获取
        // 暂时通过 permissions 判断，后续可优化
        // 默认返回 SELF（最严格）
        return DataScopeType.SELF;
    }

    public boolean invalid() {
        return DataPermissionHelper.getPermission() == null;
    }
}
