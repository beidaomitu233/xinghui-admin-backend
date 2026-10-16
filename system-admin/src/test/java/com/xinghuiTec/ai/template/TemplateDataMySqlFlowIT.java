package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.application.JdbcAiApplicationReferences;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.lowcode.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.access.AccessDeniedException;
import com.xinghuiTec.security.PlatformAccessService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static com.xinghuiTec.ai.template.TemplateDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

/** C assigned MySQL only. Two explicit JVM phases; real lowcode service/transactions/B guard, no provider. */
class TemplateDataMySqlFlowIT {
    private final ObjectMapper json = new ObjectMapper();
    private Path statePath() {
        String path = System.getenv("AI_V04_C_NATIVE_STATE"); assertNotNull(path, "Private C fixture ledger path required");
        var target = Path.of(path).toAbsolutePath().normalize();
        assertTrue(target.toString().replace('\\','/').endsWith("/.qa-local/20261016-ai-v04/c/native-state.json")); return target;
    }
    private Map<String,Object> readState() throws Exception { return json.readValue(Files.readString(statePath()), new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
    private void writeState(Map<String,Object> state) throws Exception { Files.writeString(statePath(), json.writeValueAsString(state)); }
    private String str(Map<String,Object> s, String key) { return Objects.requireNonNull(s.get(key), key).toString(); }
    private long id(Map<String,Object> s, String key) { return Long.parseLong(str(s,key)); }
    private AnnotationConfigApplicationContext context(DataSource source) {
        var c = new AnnotationConfigApplicationContext(); c.register(Transactions.class);
        c.registerBean(DataSource.class, () -> source);
        c.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(source));
        c.registerBean("transactionManager", PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        c.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        c.registerBean(LowcodeIdentity.class, () -> new LowcodeIdentity(true));
        c.registerBean(LowcodeSchema.class); c.registerBean(LowcodeService.class); c.registerBean(LowcodeResourceReader.class);
        c.registerBean(JdbcAiApplicationReferences.class); c.registerBean(TemplateDataService.class);
        c.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(true));
        c.registerBean(TemplateAdminController.class); c.registerBean(DataResourceAdminController.class);
        c.refresh(); return c;
    }
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) @EnableMethodSecurity static class Transactions {}
    private void login(long userId, String tenant, Set<String> permissions) {
        var user = new SysUser(); user.setUserId(userId); user.setTenantId(tenant); user.setStatus(1);
        var principal = new LoginUser(user, new ArrayList<>(permissions)); principal.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    private LowcodeSchema.Field field(String key) { return new LowcodeSchema.Field(key, key, "text", false, null, List.of(), "", 2000, null, null, true, false); }
    private int revision(TemplateDataService service, boolean resource, long id) { return ((Number)service.get(resource,id).get("revision")).intValue(); }
    private String insert(JdbcTemplate jdbc, String sql, Object... parameters) {
        var keys = new GeneratedKeyHolder(); jdbc.update(c -> { var p=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<parameters.length;i++)p.setObject(i+1,parameters[i]); return p; },keys);
        return Objects.requireNonNull(keys.getKey()).toString();
    }
    private ResourceDefinition resource(Map<String,Object> s) {
        return new ResourceDefinition(str(s,"prefix")+":resource", "native C", "C fixture owner", "lowcode.record", str(s,"formId"),
                List.of("subject"), Set.of(str(s,"owner"),str(s,"otherTenantUser")), Set.of(str(s,"appId")),1000);
    }
    private TemplateDefinition definition(Map<String,Object> s, String name) {
        var binding = new Binding("record",str(s,"resourceId"),str(s,"resourceVersionId"),true,false,
                Map.of("formId",new Parameter(ParameterSource.BUSINESS_REF,"formId"),"recordId",new Parameter(ParameterSource.BUSINESS_REF,"recordId")),Map.of("subject","record_subject"));
        return new TemplateDefinition(name,"native C record summary","C fixture owner",List.of(new Message(Section.INSTRUCTION,"请概括批准的业务数据"),new Message(Section.INPUT,"{{user_request}}"),new Message(Section.DATA,"{{record_subject}}")),
                List.of(new Variable("user_request",Type.STRING,true,false,null,100,false,false),new Variable("record_subject",Type.STRING,true,false,null,2000,true,false)),List.of(binding),8000);
    }
    private TemplateDataService.Test test(Map<String,Object> s, int revision) {
        return new TemplateDataService.Test(revision,str(s,"appId"),str(s,"formId")+":"+str(s,"recordId"),Map.of("user_request",json.valueToTree("概括主要信息")));
    }
    private Invocation invocation(Map<String,Object> s, String actorId, String tenant, Set<String> authorities, String recordId, String appId) {
        return new Invocation(new TrustedActor(actorId,tenant,authorities,null),appId,"draft:1",new BusinessRef("lowcode.record",str(s,"formId")+":"+recordId),"native-c-request");
    }
    private Invocation invocation(Map<String,Object> s) { return invocation(s,str(s,"owner"),"000000",Set.of("lowcode:record:query"),str(s,"recordId"),str(s,"appId")); }
    private InvocationControl control(AtomicBoolean cancelled) { final Instant deadline=Instant.now().plusSeconds(15); return new InvocationControl(){public Instant deadline(){return deadline;}public boolean cancelled(){return cancelled.get();}}; }
    private AiTemplateRuntime.RenderRequest request(Map<String,Object> s, Invocation invocation) {
        return new AiTemplateRuntime.RenderRequest(new TemplateVersionRef(str(s,"templateId"),str(s,"templateVersionId")),new AiInputs(Map.of("user_request","概括主要信息")),invocation);
    }
    @Test void seedAndValidateNativePermissionsVersionsAndApproval() throws Exception {
        assertFalse(Files.exists(statePath()),"A prior C ledger exists; inspect it before new fixtures");
        var source=TemplateDataMySqlIT.source(); var jdbc=new JdbcTemplate(source); Map<String,Object> s=new LinkedHashMap<>();
        String suffix=Long.toString(System.currentTimeMillis()); s.put("prefix","qa:aiv04_c_20261016:"+suffix); s.put("formKey","qa_aiv04_c_"+suffix);
        long owner=System.currentTimeMillis()*1000; s.put("owner",Long.toString(owner)); s.put("creator",Long.toString(owner+1)); s.put("otherTenantUser",Long.toString(owner+2));
        s.put("baseline15198",jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=15198",Integer.class));
        writeState(s);
        for(int offset=0;offset<3;offset++) jdbc.update("INSERT INTO sys_user(user_id,tenant_id,username,password,status,is_deleted) VALUES(?,?,?,'QA-NATIVE-NO-LOGIN',1,0)",owner+offset,offset==2?"qa_aiv04_c_b":"000000",str(s,"prefix")+":u"+offset);
        s.put("appId",insert(jdbc,"INSERT INTO sys_ai_application(name,purpose,owner_id,entry_key,draft_json) VALUES(?, ?, ?, ?, '{}')",str(s,"prefix")+":application","Controlled native C metadata fixture",owner,"qa.aiv04_c."+suffix)); writeState(s);
        try(var c=context(source)) {
            var lowcode=c.getBean(LowcodeService.class); var service=c.getBean(TemplateDataService.class);
            login(owner+1,"000000",Set.of("lowcode:record:query"));
            var schema=new LowcodeSchema.Schema(List.of(field("subject"),field("private_note")));
            String formId=lowcode.create(new LowcodeService.Draft(str(s,"prefix")+":form",str(s,"formKey"),"draft",null,schema)); s.put("formId",formId); writeState(s);
            lowcode.publish(Long.parseLong(formId),0);
            var record=lowcode.addRecord(Long.parseLong(formId),new LowcodeService.Submission(1,null,json.readTree("{\"subject\":\"真实记录 {{user_request}} $1\",\"private_note\":\"native-private-not-approved\"}")));
            s.put("recordId",record.get("id")); writeState(s);
            login(owner+2,"qa_aiv04_c_b",Set.of("lowcode:record:query"));
            s.put("otherRecordId",lowcode.addRecord(Long.parseLong(formId),new LowcodeService.Submission(1,null,json.readTree("{\"subject\":\"other-tenant-record\"}"))).get("id")); writeState(s);
            login(owner,"000000",Set.of("lowcode:record:query"));
            lowcode.update(Long.parseLong(formId),new LowcodeService.Draft(str(s,"prefix")+":form",str(s,"formKey"),"enabled",1,new LowcodeSchema.Schema(List.of(field("subject"),field("private_note"),field("extra")))));
            lowcode.publish(Long.parseLong(formId),2);
            long resourceId=Long.parseLong(service.createResource(resource(s))); s.put("resourceId",Long.toString(resourceId)); writeState(s);
            assertThrows(ServiceException.class,()->service.submit(resourceId,1));
            var result=service.testResource(resourceId,test(s,1)); assertEquals("SUCCESS",result.outcome()); assertEquals(1,result.schemaVersion()); assertEquals(List.of("subject"),result.fields());
            service.submit(resourceId,2); service.approve(resourceId,new TemplateDataService.Approval(3,true));
            s.put("resourceVersionId",service.versions(true,resourceId).get(0).get("id")); writeState(s);
            long templateId=Long.parseLong(service.createTemplate(definition(s,str(s,"prefix")+":template-v1"))); s.put("templateId",Long.toString(templateId)); writeState(s);
            var preview=service.testTemplate(templateId,test(s,1),true);
            assertFalse(json.writeValueAsString(preview).contains("真实记录"));
            service.publish(templateId,2); s.put("templateVersionId",service.versions(false,templateId).get(0).get("id")); writeState(s);
            var rendered=service.renderPublished(request(s,invocation(s)),control(new AtomicBoolean()));
            assertEquals("真实记录 {{user_request}} $1",rendered.messages().get(2).content());
            assertTrue(rendered.messages().stream().noneMatch(m->m.content().contains("native-private-not-approved")));
            assertEquals(str(s,"creator"),lowcode.record(id(s,"formId"),id(s,"recordId")).get("creator"));
            assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s,str(s,"owner"),"000000",Set.of("lowcode:record:query"),str(s,"otherRecordId"),str(s,"appId"))),control(new AtomicBoolean()))).code());
            assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s,str(s,"owner"),"qa_aiv04_c_b",Set.of("lowcode:record:query"),str(s,"otherRecordId"),str(s,"appId"))),control(new AtomicBoolean()))).code());
            assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s,str(s,"owner"),"000000",Set.of("ai:data:edit"),str(s,"recordId"),str(s,"appId"))),control(new AtomicBoolean()))).code());
            assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s,str(s,"creator"),"000000",Set.of("lowcode:record:query"),str(s,"recordId"),str(s,"appId"))),control(new AtomicBoolean()))).code());
            assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s,str(s,"owner"),"000000",Set.of("lowcode:record:query"),"9223372036854775807",str(s,"appId"))),control(new AtomicBoolean()))).code());
            // B version/reference rows here are explicitly test metadata, not a claimed application publish flow.
            s.put("appVersionId",insert(jdbc,"INSERT INTO sys_ai_application_version(app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by) VALUES(?,1,1,'{}',0,?)",id(s,"appId"),owner));
            jdbc.update("INSERT INTO sys_ai_application_reference(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,'TEMPLATE',?,?),(?,'RESOURCE',?,?)",id(s,"appVersionId"),str(s,"templateId"),str(s,"templateVersionId"),id(s,"appVersionId"),str(s,"resourceId"),str(s,"resourceVersionId"));
            jdbc.update("UPDATE sys_ai_application SET enabled=1,published_version_id=? WHERE app_id=?",id(s,"appVersionId"),id(s,"appId")); writeState(s);
            assertEquals(1,service.references(false,templateId,id(s,"templateVersionId")).size());
            service.saveTemplate(templateId,new TemplateDataService.Draft<>(3,definition(s,str(s,"prefix")+":template-v2")));
            service.testTemplate(templateId,test(s,4),true); service.publish(templateId,5);
            assertEquals(2,service.versions(false,templateId).size());
            assertEquals(str(s,"prefix")+":template-v1",((TemplateDefinition)service.version(false,templateId,id(s,"templateVersionId")).get("definition")).name());
            assertEquals(str(s,"templateVersionId"),jdbc.queryForObject("SELECT reference_version FROM sys_ai_application_reference WHERE app_version_id=? AND reference_kind='TEMPLATE'",String.class,id(s,"appVersionId")));
            var affected=service.enabled(true,resourceId,new TemplateDataService.Enabled(4,false)); assertEquals(1,((List<?>)affected.get("affectedApplications")).size());
            assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s)),control(new AtomicBoolean()))).code());
            service.enabled(true,resourceId,new TemplateDataService.Enabled(5,true));
            var outer=new org.springframework.transaction.support.TransactionTemplate(c.getBean(PlatformTransactionManager.class));
            outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.execute(status->{
                assertEquals(1,jdbc.queryForObject("SELECT enabled FROM sys_ai_data_resource WHERE id=?",Integer.class,resourceId));
                CompletableFuture.runAsync(()->service.enabled(true,resourceId,new TemplateDataService.Enabled(6,false))).join();
                assertEquals(1,jdbc.queryForObject("SELECT enabled FROM sys_ai_data_resource WHERE id=?",Integer.class,resourceId),"Plain RR read must be stale");
                assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s)),control(new AtomicBoolean()))).code());
                return null;
            });
            service.enabled(true,resourceId,new TemplateDataService.Enabled(7,true));
            service.saveResource(resourceId,new TemplateDataService.Draft<>(8,resource(s)));
            assertThrows(ServiceException.class,()->service.approve(resourceId,new TemplateDataService.Approval(9,true)));
            assertEquals("DRAFT",service.get(true,resourceId).get("state"));
            assertEquals("真实记录 {{user_request}} $1",service.renderPublished(request(s,invocation(s)),control(new AtomicBoolean())).messages().get(2).content());
            s.put("seedVerified",true); writeState(s);
            System.out.println("AI_V04_C_NATIVE seed=PASS originalSchema=1/current=2 approvedFieldsOnly=PASS actorTenantRecordScope=PASS revisionApproval=PASS immutableVersions=2 realBGuardImpact=PASS staleRRCurrentRead=PASS");
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void verifyAfterSeparateJvmAndMeasureBlockedReads() throws Exception {
        var source=TemplateDataMySqlIT.source(); var jdbc=new JdbcTemplate(source); var s=readState(); assertEquals(true,s.get("seedVerified"));
        try(var c=context(source)) {
            var service=c.getBean(TemplateDataService.class); login(id(s,"owner"),"000000",Set.of("lowcode:record:query"));
            assertEquals(2,service.versions(false,id(s,"templateId")).size()); assertEquals("DRAFT",service.get(true,id(s,"resourceId")).get("state"));
            assertEquals(1,service.versions(true,id(s,"resourceId")).size());
            assertEquals("真实记录 {{user_request}} $1",service.renderPublished(request(s,invocation(s)),control(new AtomicBoolean())).messages().get(2).content());
            assertEquals("1",service.options(1,20,":template-v1",str(s,"appId")).total());
            assertTrue(service.options(1,20,":template-v1",str(s,"appId")).records().get(0).available());
            System.out.println("AI_V04_C_NATIVE separateJvmPersistence=PASS approvedSnapshotPreserved=PASS optionsMySqlJson=PASS");
            measureBlocked(source,jdbc,service,s,false); measureBlocked(source,jdbc,service,s,true);
            s.put("restartVerified",true); writeState(s);
        } finally { SecurityContextHolder.clearContext(); }
    }
    private int blocked(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist WHERE USER='ai_v04_c' AND DB=DATABASE() AND INFO LIKE 'SELECT * FROM sys_lowcode_record WHERE%'",Integer.class); }
    @Test void verifyNativeControllerMethodPermissions() throws Exception {
        var source=TemplateDataMySqlIT.source(); var s=readState(); assertEquals(true,s.get("restartVerified"));
        try(var c=context(source)) {
            var templates=c.getBean(TemplateAdminController.class); var resources=c.getBean(DataResourceAdminController.class);
            var service=c.getBean(TemplateDataService.class);
            login(id(s,"owner"),"000000",Set.of("lowcode:record:query"));
            assertThrows(AccessDeniedException.class,()->templates.options(1,20,null,str(s,"appId")));
            assertThrows(AccessDeniedException.class,()->resources.get(id(s,"resourceId")));
            login(id(s,"owner"),"000000",Set.of("ai:application:edit"));
            assertEquals("1",templates.options(1,20,":template-v1",str(s,"appId")).getData().total());
            assertThrows(AccessDeniedException.class,()->templates.get(id(s,"templateId")));
            login(id(s,"otherTenantUser"),"qa_aiv04_c_b",Set.of("ai:template:query","ai:data:query"));
            assertThrows(AccessDeniedException.class,()->templates.options(1,20,null,null));
            assertThrows(AccessDeniedException.class,()->resources.get(id(s,"resourceId")));
            login(id(s,"owner"),"000000",Set.of("ai:data:test"));
            var denied=(TemplateDataService.TestResult)resources.test(id(s,"resourceId"),test(s,revision(service,true,id(s,"resourceId")))).getData();
            assertEquals("FORBIDDEN",denied.outcome()); assertTrue(denied.fields().isEmpty()); assertNull(denied.preview());
            s.put("methodPermissionsVerified",true); writeState(s);
            System.out.println("AI_V04_C_NATIVE realMethodSecurity=PASS appOptionsDoesNotGrantPromptRead=PASS tenantMisassignmentDenied=PASS configPermissionDoesNotGrantRecordRead=PASS httpAuthenticationFilter=NOT_RUN");
        } finally { SecurityContextHolder.clearContext(); }
    }
    private void measureBlocked(DataSource source,JdbcTemplate jdbc,TemplateDataService service,Map<String,Object> s,boolean cancel) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); var cancelled=new AtomicBoolean();
        try(var blocker=source.getConnection();var statement=blocker.createStatement()) {
            statement.execute("LOCK TABLES sys_lowcode_record WRITE");
            try {
                long start=System.nanoTime();
                var request=pool.submit(()->assertThrows(AiRuntimeException.class,()->service.renderPublished(request(s,invocation(s)),control(cancelled))));
                long waitUntil=System.nanoTime()+2_000_000_000L;
                while(blocked(jdbc)==0&&System.nanoTime()<waitUntil&&!request.isDone())Thread.sleep(20);
                assertTrue(blocked(jdbc)>0,"Actual MySQL SELECT must be blocked before cancellation/timeout evidence");
                if(cancel)cancelled.set(true);
                var failure=request.get(3,TimeUnit.SECONDS); long callerMillis=(System.nanoTime()-start)/1_000_000;
                assertEquals(cancel?ErrorCode.CANCELLED:ErrorCode.RESOURCE_TIMEOUT,failure.code());
                int activeAfterReturn=blocked(jdbc);
                long clearUntil=System.nanoTime()+3_000_000_000L;
                while(blocked(jdbc)>0&&System.nanoTime()<clearUntil)Thread.sleep(20);
                assertEquals(0,blocked(jdbc),"Statement must terminate while blocker remains held");
                long clearedMillis=(System.nanoTime()-start)/1_000_000;
                System.out.println("AI_V04_C_NATIVE blocked="+(cancel?"CANCEL":"TIMEOUT")+" callerMs="+callerMillis+" databaseSelectActiveAfterReturn="+activeAfterReturn+" statementClearedMs="+clearedMillis+" blockerHeld=true");
            } finally { statement.execute("UNLOCK TABLES"); }
        } finally { pool.shutdownNow(); }
    }
    @Test void cleanOnlyLedgerFixtures() throws Exception {
        var source=TemplateDataMySqlIT.source(); var jdbc=new JdbcTemplate(source); var s=readState();
        assertTrue(str(s,"prefix").startsWith("qa:aiv04_c_20261016:"));
        for(String key:List.of("appId","templateId","resourceId")) if(s.containsKey(key)) {
            String table=key.equals("appId")?"sys_ai_application":key.equals("templateId")?"sys_ai_prompt_template":"sys_ai_data_resource";
            String column=key.equals("appId")?"app_id":"id";
            assertTrue(jdbc.queryForObject("SELECT name FROM "+table+" WHERE "+column+"=?",String.class,id(s,key)).startsWith(str(s,"prefix")));
        }
        if(s.containsKey("appId")) {
            jdbc.update("DELETE FROM sys_ai_application_reference WHERE app_version_id IN (SELECT app_version_id FROM sys_ai_application_version WHERE app_id=?)",id(s,"appId"));
            jdbc.update("DELETE FROM sys_ai_application_version WHERE app_id=?",id(s,"appId")); jdbc.update("DELETE FROM sys_ai_application WHERE app_id=?",id(s,"appId"));
        }
        if(s.containsKey("templateId")) { jdbc.update("DELETE FROM sys_ai_prompt_template_version WHERE template_id=?",id(s,"templateId")); jdbc.update("DELETE FROM sys_ai_prompt_template WHERE id=?",id(s,"templateId")); }
        if(s.containsKey("resourceId")) { jdbc.update("DELETE FROM sys_ai_data_resource_version WHERE resource_id=?",id(s,"resourceId")); jdbc.update("DELETE FROM sys_ai_data_resource_check WHERE resource_id=?",id(s,"resourceId")); jdbc.update("DELETE FROM sys_ai_data_resource WHERE id=?",id(s,"resourceId")); }
        if(s.containsKey("formId")) {
            assertEquals(str(s,"formKey"),jdbc.queryForObject("SELECT form_key FROM sys_lowcode_form WHERE id=?",String.class,id(s,"formId")));
            for(String key:List.of("recordId","otherRecordId")) if(s.containsKey(key)) jdbc.update("DELETE FROM sys_lowcode_record WHERE id=? AND form_id=?",id(s,key),id(s,"formId"));
            jdbc.update("DELETE FROM sys_lowcode_form_version WHERE form_id=?",id(s,"formId")); jdbc.update("DELETE FROM sys_lowcode_form WHERE id=?",id(s,"formId"));
        }
        for(String key:List.of("owner","creator","otherTenantUser")) if(s.containsKey(key)) {
            assertTrue(jdbc.queryForObject("SELECT username FROM sys_user WHERE user_id=?",String.class,id(s,key)).startsWith(str(s,"prefix")));
            jdbc.update("DELETE FROM sys_user WHERE user_id=?",id(s,key));
        }
        assertEquals(((Number)s.get("baseline15198")).intValue(),jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=15198",Integer.class));
        org.flywaydb.core.Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).outOfOrder(false).load().validate();
        for(String table:List.of("sys_ai_prompt_template","sys_ai_prompt_template_version","sys_ai_data_resource","sys_ai_data_resource_version","sys_ai_data_resource_check","sys_ai_application","sys_lowcode_form","sys_lowcode_record"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),"Dedicated fixture tables must be empty after exact cleanup");
        try(var connection=source.getConnection()) { System.out.println("AI_V04_C_NATIVE driver="+connection.getMetaData().getDriverVersion()+" server="+connection.getMetaData().getDatabaseProductVersion()); }
        s.put("cleaned",true); writeState(s);
        System.out.println("AI_V04_C_NATIVE cleanup=PASS exactLedgerIdsOnly=true user15198Unchanged=true redisUsed=false providerCalls=0");
    }
}
