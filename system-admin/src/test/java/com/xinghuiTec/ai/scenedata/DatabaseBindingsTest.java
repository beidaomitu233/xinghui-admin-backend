package com.xinghuiTec.ai.scenedata;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseBindingsTest {
    JdbcTemplate jdbc;ObjectMapper json;SceneDataService service;TrustedActor actor;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:practical_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);json=new ObjectMapper();
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),status INT,is_deleted INT)");jdbc.update("INSERT INTO sys_user VALUES(1,'000000',1,0),(2,'other',1,0)");
        jdbc.execute("CREATE TABLE customers(id BIGINT PRIMARY KEY,name VARCHAR(100) COMMENT '客户名称',tenant_id VARCHAR(20),is_deleted INT DEFAULT 0,password VARCHAR(100),profile_json VARCHAR(1000)) COMMENT '客户档案'");
        jdbc.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY,customer_id BIGINT,amount DECIMAL(10,2),status VARCHAR(30),tenant_id VARCHAR(20),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE order_lines(id BIGINT PRIMARY KEY,order_id BIGINT,sku VARCHAR(50),quantity INT,tenant_id VARCHAR(20),is_deleted INT DEFAULT 0,CONSTRAINT fk_order_lines FOREIGN KEY(order_id) REFERENCES orders(id))");
        jdbc.update("INSERT INTO customers VALUES(7,'客户甲','000000',0,'never-export-password','{\"color\":\"blue\",\"api_token\":\"never-export-token\"}'),(8,'别的租户','other',0,'hidden','{}'),(9,'已删除','000000',1,'hidden','{}')");
        jdbc.update("INSERT INTO orders VALUES(42,7,12.50,'OPEN','000000',0),(43,7,NULL,'CLOSED','000000',0),(44,8,99,'OPEN','other',0)");
        jdbc.update("INSERT INTO order_lines VALUES(1,42,'SKU-A',2,'000000',0),(2,43,'SKU-B',9,'000000',0),(3,42,'OTHER',8,'other',0),(4,42,'DELETED',1,'000000',1)");
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V20261012_160__lowcode_forms.sql"));for(String sql:ddl.substring(0,ddl.indexOf("-- Prefer")).split(";"))if(!sql.isBlank())jdbc.execute(sql);
        var identity=new LowcodeIdentity(true);var schema=new LowcodeSchema(json);var lowcode=new LowcodeService(jdbc,schema,identity);service=new SceneDataService(jdbc,lowcode,identity,schema,json,new DataSourceTransactionManager(ds));service.database(new DatabaseBindingReader(jdbc,json,lowcode,schema,new PlatformAccessService(true)));
        actor=new TrustedActor("1","000000",Set.of(DatabaseBindingReader.READ,"lowcode:record:query","lowcode:record:list"),null);
    }
    @AfterEach void close(){service.close();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    InvocationControl control(){return new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(8);}public boolean cancelled(){return false;}};}
    DataBinding binding(String variable,String source,ReadMode mode,List<String> fields,RowSelector row,List<DataFilter> filters,int limit,RelationBinding relation){return new DataBinding(variable,variable,source,mode,fields,filters,limit,row,relation);}
    DataBinding row(String variable,String source,String id,List<String> fields){return binding(variable,source,ReadMode.ROW,fields,new RowSelector(false,Map.of("id",id)),List.of(),1,null);}
    BindingPreview preview(DataBinding... bindings){return service.previewBindings(new BindingPreviewRequest(List.of(bindings),null),actor,control());}
    @Test void discoversActualUnregisteredTablesRemarksFieldsAndForeignKey(){
        var tables=service.tables(new OptionQuery("客户",1,20),actor,control());assertEquals("1",tables.total());assertEquals("db.customers",tables.records().get(0).sourceKey());
        var fields=service.databaseFields("db.customers",actor,control());assertTrue(fields.fields().stream().anyMatch(f->f.field().equals("name")&&f.label().equals("客户名称")));assertTrue(fields.fields().stream().noneMatch(f->f.field().equals("password")));
        assertTrue(service.relations("db.orders",actor,control()).stream().anyMatch(r->r.childSourceKey().equals("db.order_lines")&&r.parentField().equals("id")&&r.childField().equals("order_id")&&r.foreignKey()));
    }
    @Test void actualRowAndFieldPreserveOrdinaryValuesAndExcludeSecrets(){
        var field=binding("客户.名称","db.customers",ReadMode.FIELD,List.of("name"),new RowSelector(false,Map.of("id","7")),List.of(),1,null);
        var result=preview(row("客户","db.customers","7",List.of()),field);assertEquals("客户甲",result.values().get("客户.名称"));var data=(Map<?,?>)result.values().get("客户");assertFalse(data.containsKey("password"));assertFalse(data.toString().contains("never-export"));assertEquals("blue",((Map<?,?>)data.get("profile_json")).get("color"));
        var nulls=preview(row("空金额","db.orders","43",List.of("amount")));assertNull(((Map<?,?>)nulls.values().get("空金额")).get("amount"));
    }
    @Test void tableFiltersTenantSoftDeleteAndLimitAreEffective(){
        var all=binding("客户集","db.customers",ReadMode.TABLE,List.of("name"),null,List.of(),10,null);var data=(List<?>)preview(all).values().get("客户集");assertEquals(1,data.size());
        var open=binding("订单","db.orders",ReadMode.TABLE,List.of("id","amount"),null,List.of(new DataFilter("status",FilterOperator.EQ,"OPEN")),1,null);var result=preview(open);assertEquals(1,result.reads().get(0).rowCount());assertFalse(result.reads().get(0).truncated());assertEquals("12.50",((Map<?,?>)((List<?>)result.values().get("订单")).get(0)).get("amount").toString());
        var bounded=binding("有界订单","db.orders",ReadMode.TABLE,List.of("id"),null,List.of(),1,null);assertTrue(preview(bounded).reads().get(0).truncated());
    }
    @Test void childrenUseActualParentKeyAndNeverAllChildRows(){
        var parent=row("当前订单","db.orders","42",List.of("amount"));var children=binding("订单明细","db.order_lines",ReadMode.CHILDREN,List.of("sku","quantity"),null,List.of(),20,new RelationBinding("当前订单","id","order_id"));
        var result=preview(parent,children);assertEquals(1,result.reads().get(1).rowCount());assertEquals("SKU-A",((Map<?,?>)((List<?>)result.values().get("订单明细")).get(0)).get("sku"));assertFalse(((Map<?,?>)result.values().get("当前订单")).containsKey("id"));
    }
    @Test void rejectsIdentifiersSecretsAndParameterizedValueCannotInject(){
        assertThrows(AiRuntimeException.class,()->service.databaseFields("db.orders` WHERE 1=1 --",actor,control()));assertThrows(AiRuntimeException.class,()->preview(row("秘密","db.customers","7",List.of("password"))));
        var badField=binding("错字段","db.orders",ReadMode.TABLE,List.of("id"),null,List.of(new DataFilter("status OR 1=1",FilterOperator.EQ,"OPEN")),10,null);assertThrows(AiRuntimeException.class,()->preview(badField));
        var injection=binding("输入","db.orders",ReadMode.TABLE,List.of("id"),null,List.of(new DataFilter("status",FilterOperator.EQ,"OPEN' OR 1=1 --")),10,null);assertEquals(0,preview(injection).reads().get(0).rowCount());
    }
    @Test void currentRecordTokensAndRuntimeProjectionMatchPreviewSinglePass() throws Exception {
        var candidates=service.records(new DatabaseRecordQuery("db.customers",List.of(),10),actor,control());assertEquals(1,candidates.records().size());var ref=candidates.records().get(0).businessRef();
        jdbc.update("UPDATE customers SET name='保留 {{订单明细}}' WHERE id=7");var current=binding("客户.名称","db.customers",ReadMode.FIELD,List.of("name"),new RowSelector(true,Map.of()),List.of(),1,null);
        var input=new EditorInput("客户：{{客户.名称}}；{{user_request}}","",List.of(),List.of(current));var compiled=service.compile(input,actor,control());var persisted=json.readValue(json.writeValueAsString(compiled),CompiledScene.class);
        var context=new SceneContext("scene.check","1",actor,ref,"r1");var rendered=service.render(persisted,context,new AiInputs(Map.of("user_request","具体分析")),control());assertTrue(rendered.messages().get(1).content().contains("保留 {{订单明细}}"));assertTrue(rendered.messages().get(1).content().contains("具体分析"));
        assertThrows(AiRuntimeException.class,()->service.render(persisted,new SceneContext("scene.check","1",actor,new BusinessRef("lowcode.record","1:7"),"r2"),new AiInputs(Map.of("user_request","分析")),control()));
    }
    @Test void compileIsStaticPinnedFieldsDoNotExpandAndDeletedFieldsReject(){
        assertDoesNotThrow(()->service.compile(new EditorInput("{{缺行}}","",List.of(),List.of(row("缺行","db.orders","999",List.of("id")))),actor,control()));
        var binding=row("订单","db.orders","42",List.of());var compiled=service.compile(new EditorInput("{{订单}}","",List.of(),List.of(binding)),actor,control());jdbc.execute("ALTER TABLE orders ADD new_business_field VARCHAR(20)");
        var context=new SceneContext("scene.check","1",actor,new BusinessRef("ai.scene","scene.check"),"r");assertFalse(service.render(compiled,context,new AiInputs(Map.of()),control()).messages().stream().anyMatch(m->m.content().contains("new_business_field")));
        jdbc.execute("ALTER TABLE orders DROP COLUMN status");assertThrows(AiRuntimeException.class,()->service.render(compiled,context,new AiInputs(Map.of()),control()));
    }
    @Test void databaseScopeRequiresDedicatedPermissionPlatformAndCurrentAccount(){
        var noGrant=new TrustedActor("1","000000",Set.of("ai:scene:invoke"),null);assertThrows(AiRuntimeException.class,()->service.tables(new OptionQuery(null,1,20),noGrant,control()));
        var tenant=new TrustedActor("2","other",Set.of(DatabaseBindingReader.READ),null);assertThrows(AiRuntimeException.class,()->service.databaseFields("db.orders",tenant,control()));
        var context=new SceneContext("scene.check","1",actor,new BusinessRef("ai.scene","scene.check"),"r");var compiled=service.compile(new EditorInput("{{客户}}","",List.of(),List.of(row("客户","db.customers","7",List.of("name")))),actor,control());
        jdbc.update("UPDATE sys_user SET status=0 WHERE user_id=1");assertThrows(AiRuntimeException.class,()->service.render(compiled,context,new AiInputs(Map.of()),control()));
    }
    @Test void jdbcMetadataDoesNotMixUnderscorePatternNeighbours(){
        jdbc.execute("CREATE TABLE biz_order(id BIGINT PRIMARY KEY,ours VARCHAR(20))");jdbc.execute("CREATE TABLE bizXorder(id BIGINT PRIMARY KEY,neighbour_secret VARCHAR(20),alien_column VARCHAR(20))");
        var fields=service.databaseFields("db.biz_order",actor,control());assertEquals(Set.of("id","ours"),fields.fields().stream().map(DatabaseField::field).collect(java.util.stream.Collectors.toSet()));
        assertThrows(AiRuntimeException.class,()->preview(row("错列","db.biz_order","1",List.of("alien_column"))));
    }
    @Test void actualMvcUsesContractBodiesBoundedContentAndPrivateHeaders() throws Exception {
        var user=new com.xinghuiTec.domain.entity.SysUser();user.setUserId(1L);user.setTenantId("000000");user.setStatus(1);
        var login=new com.xinghuiTec.domain.entity.LoginUser(user,new ArrayList<>(actor.authorities()));login.setTenantId("000000");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SceneDataController(service,new com.xinghuiTec.ai.application.AiInvocationIdentity())).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/system/ai/scene-data/database/tables").param("keyword","客户")).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store, private",response.getHeader("Cache-Control"));assertEquals("db.customers",json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("records").get(0).path("sourceKey").asText());
        String body=json.writeValueAsString(new BindingPreviewRequest(List.of(row("客户","db.customers","7",List.of("name"))),null));
        var preview=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/system/ai/scene-data/bindings/preview").contentType("application/json").content(body)).andReturn().getResponse();assertEquals(200,preview.getStatus());assertEquals("客户甲",json.readTree(preview.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("values").path("客户").path("name").asText());
        var malformed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/system/ai/scene-data/bindings/preview").contentType("application/json").content("{\"bindings\":[],\"actor\":{\"subjectId\":\"2\"}}")).andReturn().getResponse();assertEquals(400,malformed.getStatus());
        var oversized=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/system/ai/scene-data/database/records").contentType("application/json").content(" ".repeat(131073))).andReturn().getResponse();assertEquals(413,oversized.getStatus());
    }
    @Test void nineteenDigitCandidateKeySurvivesHttpAndFixedRowRoundTrip() throws Exception {
        String id = "9007199254740993123";
        jdbc.execute("CREATE TABLE exact_keys(id BIGINT PRIMARY KEY,amount DECIMAL(24,4),name VARCHAR(20),tenant_id VARCHAR(20),is_deleted INT)");
        jdbc.update("INSERT INTO exact_keys VALUES(?,?,'正确记录','000000',0),(?,?,'相邻记录','000000',0)",
                new java.math.BigInteger(id), new java.math.BigDecimal("12345678901234567890.1250"),
                new java.math.BigInteger(id).add(java.math.BigInteger.ONE), java.math.BigDecimal.ZERO);
        var user = new com.xinghuiTec.domain.entity.SysUser();
        user.setUserId(1L); user.setTenantId("000000"); user.setStatus(1);
        var login = new com.xinghuiTec.domain.entity.LoginUser(user, new ArrayList<>(actor.authorities()));
        login.setTenantId("000000");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new SceneDataController(service, new com.xinghuiTec.ai.application.AiInvocationIdentity())).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/system/ai/scene-data/database/records").contentType("application/json")
                .content("{\"sourceKey\":\"db.exact_keys\",\"filters\":[],\"limit\":20}")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var candidate = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("records").get(0);
        assertTrue(candidate.path("key").path("id").isTextual());
        assertEquals(id, candidate.path("key").path("id").textValue());
        Map<String,Object> key = json.convertValue(candidate.path("key"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        var fixed = binding("选择记录", "db.exact_keys", ReadMode.ROW, List.of("id", "name", "amount"),
                new RowSelector(false, key), List.of(), 1, null);
        var values = (Map<?,?>) preview(fixed).values().get("选择记录");
        assertEquals("正确记录", values.get("name"));
        assertEquals(id, values.get("id").toString());
        assertTrue(values.get("id") instanceof Number);
        assertEquals(new java.math.BigDecimal("12345678901234567890.1250"), values.get("amount"));
        assertTrue(values.get("amount") instanceof java.math.BigDecimal);
        var ref = json.treeToValue(candidate.path("businessRef"), BusinessRef.class);
        var current = binding("当前记录", "db.exact_keys", ReadMode.ROW, List.of("name"),
                new RowSelector(true, Map.of()), List.of(), 1, null);
        assertEquals("正确记录", ((Map<?,?>) service.previewBindings(
                new BindingPreviewRequest(List.of(current), ref), actor, control()).values().get("当前记录")).get("name"));
    }
    @Test void compositeNumericCandidateKeysUsePlainDecimalStringsWithoutChangingValues() {
        jdbc.execute("CREATE TABLE composite_keys(account_id BIGINT,entry_no DECIMAL(24,4),name VARCHAR(20),PRIMARY KEY(account_id,entry_no))");
        jdbc.update("INSERT INTO composite_keys VALUES(?,?,'复合记录')", new java.math.BigInteger("9007199254740993123"),
                new java.math.BigDecimal("12345678901234567890.1250"));
        var candidate = service.records(new DatabaseRecordQuery("db.composite_keys", List.of(), 10), actor, control()).records().get(0);
        assertEquals("9007199254740993123", candidate.key().get("account_id"));
        assertEquals("12345678901234567890.1250", candidate.key().get("entry_no"));
        assertTrue(candidate.values().get("account_id") instanceof Number);
        assertTrue(candidate.values().get("entry_no") instanceof java.math.BigDecimal);
        var fixed = binding("复合主键", "db.composite_keys", ReadMode.ROW, List.of("name"),
                new RowSelector(false, candidate.key()), List.of(), 1, null);
        assertEquals("复合记录", ((Map<?,?>) preview(fixed).values().get("复合主键")).get("name"));
    }
    @Test void inferredTenantColumnsAndKnownBusinessActionsRemainMandatory(){
        jdbc.execute("CREATE TABLE business_scope(id BIGINT PRIMARY KEY,tenantId VARCHAR(20),deleted INT)");jdbc.update("INSERT INTO business_scope VALUES(1,'000000',0),(2,'other',0),(3,'000000',1)");
        var binding=binding("范围","db.business_scope",ReadMode.TABLE,List.of("id"),null,List.of(),20,null);assertEquals(1,preview(binding).reads().get(0).rowCount());
        jdbc.execute("CREATE TABLE sys_news(id BIGINT PRIMARY KEY,title VARCHAR(20),tenant_id VARCHAR(20),is_deleted INT)");jdbc.update("INSERT INTO sys_news VALUES(1,'资讯','000000',0)");assertThrows(AiRuntimeException.class,()->service.databaseFields("db.sys_news",actor,control()));
        var granted=new TrustedActor("1","000000",Set.of(DatabaseBindingReader.READ,"system:news:query"),null);assertTrue(service.databaseFields("db.sys_news",granted,control()).fields().stream().anyMatch(f->f.field().equals("title")));
    }
    @Test void plainBusinessTextKeepsItsTypeAndMalformedStructuredDataNeverLeaks(){
        jdbc.update("UPDATE customers SET name=? WHERE id=7","{普通文字}");assertEquals("{普通文字}",((Map<?,?>)preview(row("文字","db.customers","7",List.of("name"))).values().get("文字")).get("name"));
        jdbc.update("UPDATE customers SET profile_json=? WHERE id=7","{\"api_token\":\"sensitive\",broken");assertThrows(AiRuntimeException.class,()->preview(row("结构","db.customers","7",List.of("profile_json"))));
    }    @Test void boundedTextReadReportsTruncation(){
        jdbc.execute("ALTER TABLE customers ALTER COLUMN name VARCHAR(10000)");jdbc.update("UPDATE customers SET name=? WHERE id=7","甲".repeat(9000));
        var result=preview(row("长名称","db.customers","7",List.of("name")));assertTrue(result.reads().get(0).truncated());assertEquals(8192,((Map<?,?>)result.values().get("长名称")).get("name").toString().length());assertTrue(result.notices().stream().anyMatch(n->n.code().equals("DATA_TRUNCATED")));
    }
    @Test void lowcodeExposesAllActualBusinessFieldsAndPreservesOriginalSchema() {
        String original="{\"fields\":[{\"fieldKey\":\"priority\",\"label\":\"优先级\",\"type\":\"number\"},{\"fieldKey\":\"active\",\"label\":\"有效\",\"type\":\"switch\"},{\"fieldKey\":\"internal_note\",\"label\":\"业务备注\",\"type\":\"text\"}]}";
        String latest=original.replace("]}",",{\"fieldKey\":\"extra\",\"label\":\"新字段\",\"type\":\"text\"},{\"fieldKey\":\"api_secret\",\"label\":\"密钥\",\"type\":\"text\"}]}");
        jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(1,'actual','实际业务','enabled',?,2,'1')",latest);
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(1,1,?,'1'),(1,2,?,'1')",original,latest);
        jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(11,1,1,'000000','1','{\"priority\":2.5,\"active\":true,\"internal_note\":\"普通业务备注\"}')");
        var fields=service.databaseFields("lowcode.1",actor,control());assertTrue(fields.fields().stream().anyMatch(f->f.field().equals("priority")));assertTrue(fields.fields().stream().noneMatch(f->f.field().equals("api_secret")));
        var result=preview(row("业务记录","lowcode.1","11",List.of()));var values=(Map<?,?>)result.values().get("业务记录");assertTrue(values.get("priority") instanceof Number);assertEquals(true,values.get("active"));assertEquals("普通业务备注",values.get("internal_note"));assertNull(values.get("extra"));assertTrue(result.notices().stream().anyMatch(n->n.code().equals("FIELD_NOT_IN_RECORD_SCHEMA")));
        var compiled=service.compile(new EditorInput("{{元数据}}","",List.of(),List.of(row("元数据","lowcode.1","11",List.of("priority")))),actor,control());
        jdbc.update("UPDATE sys_lowcode_record SET data_json='invalid-json' WHERE id=11");assertDoesNotThrow(()->service.authorize(compiled,new SceneContext("scene.check","1",actor,new BusinessRef("lowcode.record","1:11"),"r"),control()));
    }
}
