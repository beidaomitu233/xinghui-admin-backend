package com.xinghuiTec.lowcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LowcodeServiceTest {
    private JdbcTemplate jdbc;
    private LowcodeService service;
    private LowcodeSchema schemas;
    private LowcodeIdentity identity;
    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:lowcode_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_name VARCHAR(100),"
                + "parent_id BIGINT,order_num INT,path VARCHAR(100),component VARCHAR(100),is_frame INT,"
                + "menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(100),icon VARCHAR(100),is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),role_key VARCHAR(40),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.update("INSERT INTO sys_role VALUES(1,'000000','admin',1,0),(2,'000000','user',1,0),(3,'tenant-only','admin',1,0)");
        applyMigration();
        schemas = new LowcodeSchema(new com.fasterxml.jackson.databind.ObjectMapper());
        identity = org.mockito.Mockito.mock(LowcodeIdentity.class);
        org.mockito.Mockito.when(identity.tenant()).thenReturn("tenant_a");
        org.mockito.Mockito.when(identity.creator()).thenReturn("1");
        service = new LowcodeService(jdbc, schemas, identity);
    }

    private void applyMigration() throws Exception {
        String sql;
        try (var resource = getClass().getResourceAsStream("/db/migration/V20261012_160__lowcode_forms.sql")) {
            assertNotNull(resource);
            sql = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        sql = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
    }


    @Test
    void migrationIsIdempotentAndDoesNotGrantTenantOrOrdinaryRoles() throws Exception {
        applyMigration();
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id IN(2,3)", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'sys_lowcode_%'", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT order_num FROM sys_menu WHERE menu_id=7160", Integer.class));
    }

    @Test
    void publishedRecordsPersistOnOriginalVersionAndIncompatibleDraftIsRetained() {
        long id = Long.parseLong(service.create(draft(schema("text", "姓名"), 0, "draft")));
        assertThrows(ServiceException.class, () -> service.runtime(id));
        service.publish(id, 0);
        var saved = service.addRecord(id, submission(1, 0, "{\"field_1\":\"First\"}"));
        long recordId = Long.parseLong((String) saved.get("id"));
        service.update(id, draft(schema("number", "新名字"), 1, "enabled"));
        assertThrows(ServiceException.class, () -> service.publish(id, 2));
        assertEquals("number", ((LowcodeSchema.Schema) service.form(id).get("schema")).fields().get(0).type());
        service.update(id, draft(schema("text", "新标签"), 2, "enabled"));
        service.publish(id, 3);
        assertEquals(2, service.runtime(id).get("schemaVersion"));
        assertEquals(1, service.record(id, recordId).get("schemaVersion"));
        assertEquals("姓名", ((LowcodeSchema.Schema) service.record(id, recordId).get("schema")).fields().get(0).label());
        assertThrows(ServiceException.class, () -> service.addRecord(id, submission(1, 0, "{\"field_1\":\"Old\"}")));
        service.updateRecord(id, recordId, submission(1, 0, "{\"field_1\":\"Edited\"}"));
        assertThrows(ServiceException.class, () -> service.updateRecord(id, recordId, submission(1, 0, "{\"field_1\":\"Stale\"}")));
        assertThrows(ServiceException.class, () -> service.removeRecord(id, recordId, 0));
        service.removeRecord(id, recordId, 1);
        assertEquals("0", service.records(id,1,10,null,null).total());
        assertThrows(ServiceException.class, () -> service.removeForm(id,4));
    }

    @Test
    void recordIdAndFormAndTenantAreAlwaysCombined() {
        long id = Long.parseLong(service.create(draft(schema("text", "姓名"),0,"draft")));
        service.publish(id,0);
        long recordId = Long.parseLong((String) service.addRecord(id,submission(1,0,"{\"field_1\":\"Neutral\"}")).get("id"));
        long other = Long.parseLong(service.create(new LowcodeService.Draft("Other","other","draft",0,schema("text","姓名"))));
        service.publish(other,0);
        assertThrows(ServiceException.class, () -> service.record(other,recordId));
        when(identity.tenant()).thenReturn("tenant_b");
        assertEquals("0",service.records(id,1,10,null,null).total());
        assertThrows(ServiceException.class, () -> service.record(id,recordId));
        assertThrows(ServiceException.class, () -> service.updateRecord(id,recordId,submission(1,0,"{\"field_1\":\"B\"}")));
        assertThrows(ServiceException.class, () -> service.removeRecord(id,recordId,0));
        when(identity.tenant()).thenReturn("tenant_a");
        assertEquals("1",service.records(id,1,10,null,null).total());
    }

    @Test
    void disabledFormKeepsHistoryEditableButRejectsNewAndRevisionIsRequired() {
        long id=Long.parseLong(service.create(draft(schema("text","姓名"),0,"draft")));
        service.publish(id,0);
        long recordId=Long.parseLong((String) service.addRecord(id,submission(1,0,"{\"field_1\":\"Neutral\"}")).get("id"));
        service.update(id,draft(schema("text","姓名"),1,"disabled"));
        assertThrows(ServiceException.class, () -> service.addRecord(id,submission(1,0,"{\"field_1\":\"New\"}")));
        assertEquals("disabled",service.runtime(id).get("status"));
        service.updateRecord(id,recordId,submission(1,0,"{\"field_1\":\"History\"}"));
        assertThrows(ServiceException.class, () -> service.update(id,draft(schema("text","姓名"),1,"enabled")));
        assertThrows(ServiceException.class, () -> service.publish(id,1));
        assertThrows(ServiceException.class, () -> service.records(id,1,101,null,null));
        assertThrows(ServiceException.class, () -> service.records(id,1,10,"field_1' OR 1=1", "x"));
    }

    @Test
    void validationRejectsSystemKeysBadDefaultsTypesAndOversizedValues() throws Exception {
        var mapper=new ObjectMapper();
        var schema=schema("text","姓名");
        assertThrows(ServiceException.class, () -> schemas.validateData(schema,mapper.readTree("{}"),false));
        assertThrows(ServiceException.class, () -> schemas.validateData(schema,mapper.readTree("{\"field_1\":12}"),false));
        assertThrows(ServiceException.class, () -> schemas.validateData(schema,mapper.readTree("{\"field_1\":\"x\",\"tenantId\":\"b\"}"),false));
        assertThrows(ServiceException.class, () -> schemas.validateData(schema,mapper.readTree("{\"field_1\":\""+"x".repeat(201)+"\"}"),false));
        var fields=new java.util.ArrayList<>(schema.fields());fields.add(schema.fields().get(0));
        assertThrows(ServiceException.class, () -> schemas.validate(new LowcodeSchema.Schema(fields)));
        for(String type:List.of("number","date","switch","select","multiselect")){
            var field=new LowcodeSchema.Field("field_1","值",type,true,null,List.of(new LowcodeSchema.Option("是","yes")),"",200,null,null,true,false);
            assertThrows(ServiceException.class, () -> schemas.validateValue(field,mapper.readTree("\"bad\""),true));
        }
    }

    @Test
    void identifiersBeyondJavascriptIntegerPrecisionRemainStrings() {
        jdbc.execute("ALTER TABLE sys_lowcode_form ALTER COLUMN id RESTART WITH 9007199254740993");
        String id=service.create(draft(schema("text","姓名"),0,"draft"));
        assertEquals("9007199254740993",id);
        assertEquals(id,service.form(Long.parseLong(id)).get("id"));
        service.publish(Long.parseLong(id),0);
        jdbc.execute("ALTER TABLE sys_lowcode_record ALTER COLUMN id RESTART WITH 9007199254740993");
        assertEquals(id,service.addRecord(Long.parseLong(id),submission(1,0,"{\"field_1\":\"Neutral\"}")).get("id"));
    }

    @Test
    void numericDictionaryOptionValuesAreValidAndUnknownOptionsAreRejected() {
        var field = new LowcodeSchema.Field("choice", "选项", "select", true, schemas.data("\"0\""),
                List.of(new LowcodeSchema.Option("否", "0"), new LowcodeSchema.Option("是", "1")), "", 200, null, null, true, true);
        var schema = schemas.validate(new LowcodeSchema.Schema(List.of(field)));
        assertEquals("1", schemas.validateData(schema, schemas.data("{\"choice\":\"1\"}"), false).get("choice").asText());
        assertThrows(ServiceException.class, () -> schemas.validateData(schema, schemas.data("{\"choice\":\"2\"}"), false));
    }

    @Test
    void fractionalSchemaIntegersAreNotSilentlyTruncatedAndOptionalMetadataIsNormalized() throws Exception {
        var mapper = new ObjectMapper();
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> mapper.readValue(
                "{\"fields\":[{\"fieldKey\":\"text\",\"label\":\"字段\",\"type\":\"text\",\"maxLength\":1.5}]}", LowcodeSchema.Schema.class));
        var normalized = schemas.validate(mapper.readValue(
                "{\"fields\":[{\"fieldKey\":\"text\",\"label\":\"字段\",\"type\":\"text\"}]}", LowcodeSchema.Schema.class));
        assertEquals(200, normalized.fields().get(0).maxLength());
        assertEquals("", normalized.fields().get(0).help());
        assertTrue(normalized.fields().get(0).options().isEmpty());
        var number = new LowcodeSchema.Field("number", "数字", "number", true, null, List.of(), "", 200, null, null, true, true);
        assertThrows(ServiceException.class, () -> schemas.validateValue(number, schemas.data("1e9999"), true));
    }

    private static LowcodeSchema.Schema schema(String type,String label) {
        return new LowcodeSchema.Schema(List.of(new LowcodeSchema.Field("field_1",label,type,true,null,List.of(),"",200,null,null,true,true)));
    }
    private static LowcodeService.Draft draft(LowcodeSchema.Schema schema,int revision,String status) {
        return new LowcodeService.Draft("Neutral form","neutral_form",status,revision,schema);
    }
    private LowcodeService.Submission submission(int version,int revision,String data) {
        return new LowcodeService.Submission(version,revision,schemas.data(data));
    }
}
