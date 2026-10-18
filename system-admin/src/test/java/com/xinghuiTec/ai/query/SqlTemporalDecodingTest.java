package com.xinghuiTec.ai.query;

import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.protocol.InternalTimestamp;
import com.mysql.cj.result.LocalDateTimeValueFactory;
import com.mysql.cj.result.SqlTimestampValueFactory;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Connector/J value factories, no database, provider or credentials. */
class SqlTemporalDecodingTest {
    @Test void timestampSessionCalendarFieldsDoNotShiftWithJvmTimezone() {
        var saved=TimeZone.getDefault();
        try {
            var fields=new InternalTimestamp(2026,10,18,12,34,56,123456000,6);
            var properties=new DefaultPropertySet();
            var typed=new LocalDateTimeValueFactory(properties).createFromTimestamp(fields);
            var legacy=new SqlTimestampValueFactory(properties,null,TimeZone.getTimeZone("UTC"),TimeZone.getTimeZone("Asia/Shanghai")).createFromTimestamp(fields);
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals("2026-10-18T12:34:56.123456",decode(Types.TIMESTAMP,typed));
                if(zone.equals("UTC"))assertEquals("2026-10-18T04:34:56.123456",legacy.toLocalDateTime().toString(),"Reproduces the old host-timezone conversion");
            }
        } finally {TimeZone.setDefault(saved);}
    }
    @Test void datetimeDateAndFractionalTimeUseTypedCalendarValues() {
        var saved=TimeZone.getDefault();
        try {
            var properties=new DefaultPropertySet();
            var fields=new InternalTimestamp(2026,10,18,12,34,56,123456000,6);
            var datetime=new LocalDateTimeValueFactory(properties).createFromDatetime(fields);
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals("2026-10-18T12:34:56.123456",decode(Types.TIMESTAMP,datetime));
                assertEquals("2026-10-18",decode(Types.DATE,LocalDate.of(2026,10,18)));
                assertEquals("12:34:56.123456",decode(Types.TIME,LocalTime.of(12,34,56,123456000)));
                assertNull(decode(Types.TIMESTAMP,null));
            }
        } finally {TimeZone.setDefault(saved);}
    }
    private Object decode(int type,Object value) {
        var metadata=(ResultSetMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSetMetaData.class},(proxy,method,args)-> {
            if(method.getName().equals("getColumnType"))return type;throw new AssertionError("Unexpected metadata call");
        });
        var result=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)-> {
            if(method.getName().equals("getMetaData"))return metadata;
            if(method.getName().equals("getObject")&&args.length==2&&args[1] instanceof Class<?>)return value;
            throw new AssertionError("Untyped calendar conversion must not be used");
        });
        var executor=new SqlReadOnlyExecutor(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:unused-temporal-fixture")));
        return ReflectionTestUtils.invokeMethod(executor,"value",result,1,new QueryColumn("calendar","日期时间","DATETIME",true),new ArrayList<QueryDiagnostic>());
    }
}
