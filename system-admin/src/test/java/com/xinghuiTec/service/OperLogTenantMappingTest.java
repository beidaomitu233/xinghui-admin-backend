package com.xinghuiTec.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.xinghuiTec.mapper.SysOperLogMapper;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperLogTenantMappingTest {
    @Test void platformLedgerWithoutTenantColumnWorksWithTenantInterceptorEnabled() throws Exception {
        var source=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:operlog_tenant;DB_CLOSE_DELAY=-1","sa","");
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS sys_oper_log(oper_id BIGINT PRIMARY KEY,title VARCHAR(100),business_type INT,method VARCHAR(100),request_method VARCHAR(10),oper_name VARCHAR(100),oper_url VARCHAR(100),oper_ip VARCHAR(100),oper_param VARCHAR(100),json_result VARCHAR(100),status INT,error_msg VARCHAR(100),cost_time BIGINT,oper_time TIMESTAMP)");
            sql.execute("MERGE INTO sys_oper_log(oper_id,title) KEY(oper_id) VALUES(1,'ledger-fixture')");
        }
        var config=new MybatisConfiguration();
        config.setEnvironment(new Environment("test",new JdbcTransactionFactory(),source));
        var plugins=new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            public Expression getTenantId() { return new StringValue("000000"); }
        }));
        config.addInterceptor(plugins);
        config.addMapper(SysOperLogMapper.class);
        try(var session=new MybatisSqlSessionFactoryBuilder().build(config).openSession()) {
            var rows=session.getMapper(SysOperLogMapper.class).selectList(null);
            assertEquals(1,rows.size());
            assertEquals("ledger-fixture",rows.get(0).getTitle());
        }
    }
}
