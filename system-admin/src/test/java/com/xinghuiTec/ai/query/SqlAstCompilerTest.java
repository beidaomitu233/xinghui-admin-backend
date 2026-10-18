package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlAstCompilerTest {
    private final SqlAstCompiler compiler=new SqlAstCompiler();
    private final List<SqlDataset> sources=List.of(dataset("orders",ScopeKind.TENANT,List.of("tenant-A")),
            dataset("customers",ScopeKind.PERSONAL,List.of("tenant-A",19L)));
    private static SqlDataset dataset(String name,ScopeKind scope,List<Object> parameters) {
        var columns=List.of(new QueryColumn("id","编号","INTEGER",false),new QueryColumn("customer_id","客户","INTEGER",false),
                new QueryColumn("name","名称","STRING",true),new QueryColumn("amount","金额","DECIMAL",true));
        return new SqlDataset(new DatasetSchema("db."+name,name,name,"",scope,"",columns,"schema"),"POLICY",
                "SELECT id,customer_id,name,amount FROM `private_"+name+"` WHERE tenant_id=?"+(scope==ScopeKind.PERSONAL?" AND owner_id=?":""),parameters,List.of("id"));
    }
    private SqlAstCompiler.Plan compile(String sql) {return compiler.compile(new SqlQuery(sql,Map.of(),20),sources);}
    @Test void starExpandsAndNeverLeaksPhysicalSql() {
        var plan=compile("select * from orders");
        assertEquals(4,plan.columns().size());assertFalse(plan.normalizedSql().contains("*"));
        assertFalse(plan.normalizedSql().contains("private_"));assertFalse(plan.normalizedSql().contains("tenant-A"));
        assertTrue(plan.executionSql().contains("private_orders"));assertTrue(plan.parameters().contains("tenant-A"));
    }
    @Test void eachJoinSourceContainsScopeBeforeOrAndFilters() {
        var plan=compile("select o.id, c.name from orders o left join customers c on o.customer_id=c.id where o.amount>10 or c.name='a' order by o.id");
        assertEquals(List.of("tenant-A","tenant-A",19L,new BigDecimal("10"),"a",21),plan.parameters());
        assertTrue(plan.executionSql().contains("owner_id=?"));assertEquals(2,plan.datasets().size());
        assertEquals(List.of("amount","customer_id","id"),plan.dependencies().get("db.orders").stream().map(QueryColumn::name).toList());
    }
    @Test void aggregatesGroupHavingAndOrderAliasesWork() {
        var plan=compile("select customer_id, count(*) as total, sum(amount) as sum_amount from orders group by customer_id having total>1 order by sum_amount desc limit 5");
        assertEquals(5,plan.maxRows());assertEquals("INTEGER",plan.columns().get(1).type());assertEquals("DECIMAL",plan.columns().get(2).type());
    }
    @Test void nullBusinessValuesAndCoalesceRemainPrepared() {
        var parameters=new HashMap<String,Object>();parameters.put("fallback",null);
        var plan=compiler.compile(new SqlQuery("select coalesce(name,:fallback) as label from orders where amount is null",parameters,10),sources);
        assertNull(plan.parameters().get(0));assertTrue(plan.executionSql().contains("IS NULL"));
        assertThrows(UnsupportedOperationException.class,()->plan.parameters().add("x"));
    }
    @Test void likeNotLikeEscapeAndBetweenArePrepared() {
        var plan=compile("select id from orders where name like 'a!_%' escape '!' and name not like 'b%' and amount between 1 and 9");
        assertTrue(plan.parameters().containsAll(List.of("a!_%","!","b%",new BigDecimal("1"),new BigDecimal("9"))));
        assertFalse(plan.executionSql().contains("a!_%"));
    }
    @Test void exactNumericLiteralsAndQuotesNeverBecomeSql() {
        var plan=compile("select id from orders where amount=1234567890123456789.12345 and name='x'' OR 1=1 --'");
        assertTrue(plan.parameters().contains(new BigDecimal("1234567890123456789.12345")));
        assertFalse(plan.executionSql().contains("OR 1=1 --"));
    }
    @Test void trustedCurrentRecordIsStaticUntilExecution() {
        var plan=compile("select name from orders where id=:current_record_id and customer_id=:current_user_id");
        assertEquals("db.orders",plan.currentRecordDatasetKey());
        assertTrue(plan.parameters().stream().anyMatch(value->value instanceof SqlAstCompiler.TrustedValue));
    }
    @Test void currentRecordRequiresActualSinglePrimaryKey() {
        assertThrows(SqlQueryException.class,()->compile("select id from orders where customer_id=:current_record_id"));
        var original=sources.get(0);var notId=new SqlDataset(original.schema(),original.policyCode(),original.sourceSql(),original.sourceParameters(),List.of("customer_id"));
        var query=new SqlQuery("select id from orders where customer_id=:current_record_id",Map.of(),20);
        assertEquals("db.orders",compiler.compile(query,List.of(notId)).currentRecordDatasetKey());
        assertThrows(SqlQueryException.class,()->compiler.compile(new SqlQuery("select id from orders where id=:current_record_id",Map.of(),20),List.of(notId)));
    }
    @Test void secretFieldsCannotAppearInAnyExpression() {
        for(String sql:List.of("select password from orders","select id from orders where password='x'", "select count(password) from orders",
                "select o.id from orders o join customers c on o.password=c.id", "select id as api_key from orders"))
            assertThrows(SqlQueryException.class,()->compile(sql),sql);
    }
    @Test void systemSchemasUnionSubqueriesAndCteAreRejected() {
        for(String sql:List.of("select id from mysql.user","select id from sys_user","select id from orders union select id from customers",
                "select id from (select id from orders) x","select id from orders where id in (select id from customers)",
                "with x as (select id from orders) select id from x"))assertThrows(SqlQueryException.class,()->compile(sql),sql);
    }
    @Test void writesLocksFilesSessionsAndExpensiveFunctionsAreRejected() {
        for(String sql:List.of("delete from orders","update orders set amount=0","create table x(id int)","select id from orders; select id from customers",
                "select id from orders for update","select id from orders lock in share mode","select id into outfile '/tmp/x' from orders",
                "select load_file('/tmp/x') from orders","select sleep(5) from orders","select @x from orders","select @@version from orders",
                "select id from orders cross join customers"))assertThrows(SqlQueryException.class,()->compile(sql),sql);
    }
    @Test void parametersAndSourcesCannotBeForged() {
        assertThrows(SqlQueryException.class,()->compiler.compile(new SqlQuery("select id from orders",Map.of("current_tenant_id","other"),20),sources));
        assertThrows(SqlQueryException.class,()->compile("select id from orders where name=:missing"));
        assertThrows(SqlQueryException.class,()->compile("select id from orders limit 201"));
        assertThrows(SqlQueryException.class,()->compile("select id from orders limit 2 offset 1"));
        assertThrows(SqlQueryException.class,()->compile("select id from orders o join customers c on o.id=c.id"));
    }
    @Test void ambiguousVirtualDatasetNamesFailClosed() {
        var original=sources.get(0);
        var duplicate=new SqlDataset(new DatasetSchema("different", "ORDERS", "", "", ScopeKind.TENANT,"",original.schema().columns(),""),"OTHER",original.sourceSql(),original.sourceParameters(),List.of("id"));
        assertThrows(SqlQueryException.class,()->compiler.compile(new SqlQuery("select id from orders",Map.of(),20),List.of(original,duplicate)));
    }
    @Test void decimalBusinessParametersSurviveDefaultMapperSnapshotRoundTrip() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var query=mapper.readValue("{\"sql\":\"select id from orders where amount=:value\",\"parameters\":{\"value\":1234567890123456789.12345678},\"maxRows\":20}",SqlQuery.class);
        assertEquals(new BigDecimal("1234567890123456789.12345678"),query.parameters().get("value"));
        assertEquals(query,mapper.readValue(mapper.writeValueAsBytes(query),SqlQuery.class));
        var wire=mapper.readTree(mapper.writeValueAsBytes(query));
        assertEquals("1234567890123456789.12345678",wire.path("parameters").path("value").path("$number").textValue());
        var text=new SqlQuery(query.sql(),Map.of("value","00123"),20);
        assertEquals("00123",mapper.readValue(mapper.writeValueAsBytes(text),SqlQuery.class).parameters().get("value"));
    }
}
