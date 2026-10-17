package com.xinghuiTec.ai.scenedata;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class SceneDataServiceTest {
    private JdbcTemplate jdbc; private SceneDataService service; private ObjectMapper json;
    private static final String SCHEMA="{\"fields\":[{\"fieldKey\":\"subject\",\"label\":\"主题\",\"type\":\"text\",\"maxLength\":2000},{\"fieldKey\":\"summary\",\"label\":\"摘要值\",\"type\":\"number\",\"maxLength\":2000},{\"fieldKey\":\"internal_note\",\"label\":\"隐藏元数据\",\"type\":\"text\",\"maxLength\":2000}]}";
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:scene_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);json=new ObjectMapper();
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.extract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.unquote'");
        jdbc.execute("CREATE ALIAS JSON_TYPE FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.type'");
        jdbc.execute("CREATE ALIAS JSON_SEARCH FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.search'");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),status INT,is_deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(101,'tenant_a',1,0),(102,'tenant_b',1,0)");
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V20261012_160__lowcode_forms.sql")).split("-- Prefer reserved IDs")[0];
        for(String statement:ddl.split(";")) if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(1,'neutral','公开表单','enabled',?,2,'999'),(2,'disabled','不可见草稿','draft',?,0,'999')",SCHEMA,SCHEMA);
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(1,1,?,'999'),(1,2,?,'999')",SCHEMA,SCHEMA);
        jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(11,1,1,'tenant_a','999',?),(12,1,1,'tenant_a','999',?),(13,1,1,'tenant_b','999',?)",
                "{\"subject\":\"业务 {{user_request}} $1\",\"summary\":null,\"internal_note\":\"never-model\"}","{\"subject\":null,\"summary\":7.25}","{\"subject\":\"other-tenant\"}");
        var identity=new LowcodeIdentity(true);var schema=new LowcodeSchema(json);
        service=new SceneDataService(jdbc,new LowcodeService(jdbc,schema,identity),identity,schema,json,new DataSourceTransactionManager(ds));
    }
    @AfterEach void close(){service.close();SecurityContextHolder.clearContext();}
    private TrustedActor actor(){return new TrustedActor("101","tenant_a",Set.of("lowcode:record:query","lowcode:record:list"),null);}
    private InvocationControl control(){return new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(5);}public boolean cancelled(){return false;}};}
    private EditorInput editor(String text,String... fields){return new EditorInput(text,"",fields.length==0?List.of():List.of(new Selection("lowcode.record","1",List.of(fields))));}
    private SceneContext context(String record){return new SceneContext("scene.server","version.1",actor(),new BusinessRef("lowcode.record","1:"+record),"7");}

    @Test void catalogMetadataAndSamplesExposeOnlyAllowedIdentities(){
        assertEquals("lowcode.record",service.catalog(actor(),control()).objects().get(0).objectKey());
        var forms=service.forms(new OptionQuery(null,1,20),actor(),control());assertEquals("1",forms.total());assertEquals("公开表单",forms.records().get(0).name());
        var fields=service.fields("lowcode.record","1",actor(),control());assertEquals(List.of("subject","summary"),fields.fields().stream().map(FieldOption::fieldKey).toList());
        var records=service.samples(new SampleQuery("lowcode.record","1",null,1,20),actor(),control());
        assertEquals("2",records.total());assertEquals(Set.of("11","12"),new HashSet<>(records.records().stream().map(SampleOption::recordId).toList()));
        assertTrue(records.records().stream().anyMatch(r->r.label().contains("业务")));assertFalse(records.toString().contains("never-model"));
        assertEquals("1",service.samples(new SampleQuery("lowcode.record","1","业务",1,20),actor(),control()).total());
        assertEquals("0",service.samples(new SampleQuery("lowcode.record","1","never-model",1,20),actor(),control()).total());
        assertEquals("0",service.forms(new OptionQuery("%",1,20),actor(),control()).total());
    }
    @Test void metadataPermissionsDoNotLeakTotalsOrNames(){
        var denied=new TrustedActor("101","tenant_a",Set.of(),null);
        assertTrue(service.catalog(denied,control()).objects().isEmpty());
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.forms(new OptionQuery(null,1,20),denied,control())).code());
        var noList=new TrustedActor("101","tenant_a",Set.of("lowcode:record:query"),null);
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.samples(new SampleQuery("lowcode.record","1",null,1,20),noList,control())).code());
    }
    @Test void compilationNeedsNoRecordAndNeverWritesApproval(){
        jdbc.update("DELETE FROM sys_lowcode_record");
        var compiled=service.compile(editor("概括 {{user_request}} {{record_subject}}","subject"),actor(),control());
        assertTrue(compiled.inputVariables().get(0).required());assertEquals(2,compiled.selections().get(0).schemaVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record",Integer.class));
        assertFalse(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='sys_ai_data_resource_check'").size()>0);
    }
    @Test void invalidVariablesObjectsFieldsAndIntermediateSyntaxRejected(){
        for(String text:List.of("", "{{unknown}}", "{{bad.field}}", "{{user_request}"))assertThrows(InvalidConfiguration.class,()->service.compile(editor(text),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("概括","internal_note"),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("概括","subject","subject"),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(new EditorInput("概括","",List.of(new Selection("arbitrary.sql","1",List.of("subject")))),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("{{record_subject}}"),actor(),control()));
    }
    @Test void originalSchemaNullAndSinglePassStayInDataMessages() throws Exception {
        var compiled=service.compile(editor("要求 {{user_request}}；看 {{record_subject}}","subject","summary"),actor(),control());
        var rendered=service.render(compiled,context("11"),new AiInputs(Map.of("user_request","保留 {{record_summary}} $1")),control());
        assertEquals(1,rendered.dataUses().get(0).schemaVersion());
        assertEquals("要求 保留 {{record_summary}} $1；看 业务 {{user_request}} $1",rendered.messages().get(1).content());
        String data=rendered.messages().get(rendered.messages().size()-1).content();
        var value=json.readTree(data.substring(data.indexOf('{'))).path("lowcode.record");assertTrue(value.get("summary").isNull());assertTrue(value.get("subject").asText().contains("{{user_request}}"));
        assertFalse(rendered.messages().stream().anyMatch(m->m.content().contains("never-model")));
        assertFalse(rendered.messages().get(0).content().contains("业务 {{"));
        var numeric=service.render(compiled,context("12"),new AiInputs(Map.of("user_request","简短")),control());
        var numbers=json.readTree(numeric.messages().get(numeric.messages().size()-1).content().substring(data.indexOf('{'))).path("lowcode.record");
        assertTrue(numbers.get("summary").isNumber());assertTrue(numbers.get("subject").isNull());
    }
    @Test void tenantWrongFormDeletionAndTypeMismatchFailBeforeAnyModel(){
        var compiled=service.compile(editor("概括","subject"),actor(),control());
        assertThrows(AiRuntimeException.class,()->service.render(compiled,context("13"),new AiInputs(Map.of()),control()));
        var wrong=new SceneContext("scene.server","version.1",actor(),new BusinessRef("lowcode.record","2:11"),"7");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.render(compiled,wrong,new AiInputs(Map.of()),control())).code());
        jdbc.update("UPDATE sys_lowcode_form_version SET schema_json=? WHERE form_id=1 AND schema_version=1",SCHEMA.replace("\"type\":\"text\"","\"type\":\"number\""));
        assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of()),control()));
        jdbc.update("UPDATE sys_user SET status=0 WHERE user_id=101");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of()),control())).code());
    }
    @Test void noSelectionsSupportsGeneralSceneAndUnknownInputsFail(){
        var compiled=service.compile(editor("固定要求"),new TrustedActor("101","tenant_a",Set.of(),null),control());
        assertTrue(service.render(compiled,context("11"),new AiInputs(Map.of()),control()).dataUses().isEmpty());
        assertEquals(ErrorCode.INVALID_INPUT,assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of("tenantId","tenant_b")),control())).code());
    }
    @Test void expiredControlStopsBeforeAnyDataAccess(){
        var expired=new InvocationControl(){public Instant deadline(){return Instant.EPOCH;}public boolean cancelled(){return false;}};
        assertEquals(ErrorCode.DEADLINE_EXCEEDED,assertThrows(AiRuntimeException.class,()->service.compile(editor("固定要求"),actor(),expired)).code());
    }
    @Test void inputFreeAuthorizationDoesNotRenderOrParseRecordBody(){
        var compiled=service.compile(editor("{{user_request}}","subject"),actor(),control());
        jdbc.update("UPDATE sys_lowcode_record SET data_json='not-json' WHERE id=11");
        assertDoesNotThrow(()->service.authorize(compiled,context("11"),control()));
        jdbc.update("UPDATE sys_lowcode_record SET is_deleted=1 WHERE id=11");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.authorize(compiled,context("11"),control())).code());
    }
    public static String extract(String raw,String path) throws Exception {var n=new ObjectMapper().readTree(raw).get(path.substring(2));return n==null?null:n.toString();}
    public static String unquote(String raw) throws Exception {return raw==null?null:new ObjectMapper().readTree(raw).asText();}
    public static String type(String raw) throws Exception {return raw!=null&&new ObjectMapper().readTree(raw).isTextual()?"STRING":"NULL";}
    public static String search(String raw,String mode,String value,String escape,String path) throws Exception {for(var n:new ObjectMapper().readTree(raw).path("fields"))if(n.path("fieldKey").asText().equals(value))return "$.fields";return null;}
}
