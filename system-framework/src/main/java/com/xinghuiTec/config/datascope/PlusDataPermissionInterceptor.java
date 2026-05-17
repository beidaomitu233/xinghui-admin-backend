package com.xinghuiTec.config.datascope;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.BaseMultiTableInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 数据权限 MyBatis 拦截器
 * 在 SQL 执行前注入数据过滤条件
 *
 * @author xinghuiTec
 */
@Slf4j
public class PlusDataPermissionInterceptor extends BaseMultiTableInnerInterceptor implements InnerInterceptor {

    private final PlusDataPermissionHandler handler = new PlusDataPermissionHandler();

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) {
            return;
        }
        if (handler.invalid()) {
            return;
        }
        PluginUtils.MPBoundSql mpBs = PluginUtils.mpBoundSql(boundSql);
        mpBs.sql(parserSingle(mpBs.sql(), ms.getId()));
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = mpSh.mappedStatement();
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct == SqlCommandType.UPDATE || sct == SqlCommandType.DELETE) {
            if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) {
                return;
            }
            if (handler.invalid()) {
                return;
            }
            PluginUtils.MPBoundSql mpBs = mpSh.mPBoundSql();
            mpBs.sql(parserMulti(mpBs.sql(), ms.getId()));
        }
    }

    @Override
    protected void processSelect(Select select, int index, String sql, Object obj) {
        if (select instanceof PlainSelect ps) {
            setWhere(ps);
        } else if (select instanceof SetOperationList sol) {
            sol.getSelects().forEach(s -> setWhere((PlainSelect) s));
        }
    }

    @Override
    protected void processUpdate(Update update, int index, String sql, Object obj) {
        Expression segment = handler.getSqlSegment(update.getWhere(), false);
        if (segment != null) {
            update.setWhere(segment);
        }
    }

    @Override
    protected void processDelete(Delete delete, int index, String sql, Object obj) {
        Expression segment = handler.getSqlSegment(delete.getWhere(), false);
        if (segment != null) {
            delete.setWhere(segment);
        }
    }

    private void setWhere(PlainSelect plainSelect) {
        Expression segment = handler.getSqlSegment(plainSelect.getWhere(), true);
        if (segment != null) {
            plainSelect.setWhere(segment);
        }
    }

    /**
     * 多表数据权限表达式构建（暂不支持多表场景，直接返回原始 WHERE）
     */
    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        return handler.getSqlSegment(where, true);
    }
}
