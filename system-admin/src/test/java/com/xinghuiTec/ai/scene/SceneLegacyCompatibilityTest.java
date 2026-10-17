package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.template.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.lowcode.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real first-phase resource approval/template/test/publication with a simulated provider; then two scene saves. */
class SceneLegacyCompatibilityTest {
    private SceneTestSupport fixture;private LowcodeResourceReader reader;private String key="lowcode.record.summary.1",baseVersion,resourceVersion;
    @BeforeEach void setup()throws Exception{
        fixture=new SceneTestSupport();fixture.script(fixture.read("V20261016_190__ai_template_data_v04.sql"));
        var user=new SysUser();user.setUserId(101L);user.setTenantId("tenant_a");user.setStatus(1);var login=new LoginUser(user,new ArrayList<>(fixture.actor.authorities()));login.setTenantId("tenant_a");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
        var identity=new LowcodeIdentity(true);var schema=new LowcodeSchema(fixture.mapper);reader=new LowcodeResourceReader(new LowcodeService(fixture.jdbc,schema,identity),identity,fixture.jdbc,fixture.manager);
        var templates=new TemplateDataService(fixture.jdbc,fixture.mapper,reader,fixture.beans.getBeanProvider(AiApplicationReferences.class));fixture.beans.addBean("templates",templates);
        var nativePolicy=fixture.definition("initial",true).modelPolicy();var binding=new AiProviderRuntime.ModelBinding(new ModelVersionRef("17","8"),AiProviderRuntime.KeyMode.AUTO,Set.of(),null,List.of());
        var incomplete=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,List.of(binding),false,null,Set.of("user_request"),new Requirements(OutputFormat.TEXT,true,false),AiApplicationPolicy.Scope.FRONTEND,Set.of("101"),Set.of(),"1",10,2,2,100);
        String app=fixture.applications.create(new AiApplicationStore.Draft("Legacy","Legacy purpose","lowcode.record.summary/1",incomplete),101L);
        String resource=templates.createResource(new ResourceDefinition("Record","lowcode","Owner","lowcode.record","1",List.of("subject"),Set.of("101"),Set.of(app),2000));
        templates.testResource(Long.parseLong(resource),new TemplateDataService.Test(1,app,"1:11",Map.of()));
        templates.submit(Long.parseLong(resource),2);var approved=templates.approve(Long.parseLong(resource),new TemplateDataService.Approval(3,true));
        resourceVersion=templates.versions(true,Long.parseLong(resource)).get(0).get("id").toString();
        var mapping=new TemplateDefinition.Binding("current_record",resource,resourceVersion,true,false,Map.of("formId",new TemplateDefinition.Parameter(TemplateDefinition.ParameterSource.BUSINESS_REF,"formId"),"recordId",new TemplateDefinition.Parameter(TemplateDefinition.ParameterSource.BUSINESS_REF,"recordId")),Map.of("subject","source_text"));
        var definition=new TemplateDefinition("Legacy template","Legacy purpose","Owner",List.of(new TemplateDefinition.Message(TemplateDefinition.Section.INSTRUCTION,"Base instructions"),new TemplateDefinition.Message(TemplateDefinition.Section.INPUT,"{{user_request}}"),new TemplateDefinition.Message(TemplateDefinition.Section.DATA,"{{source_text}}")),
                List.of(new TemplateDefinition.Variable("user_request",TemplateDefinition.Type.STRING,true,false,null,2048,false,false),new TemplateDefinition.Variable("source_text",TemplateDefinition.Type.STRING,true,false,null,2048,true,false)),List.of(mapping),12000);
        String template=templates.createTemplate(definition);templates.testTemplate(Long.parseLong(template),new TemplateDataService.Test(1,app,"1:11",Map.of("user_request",fixture.mapper.valueToTree("brief"))),true);templates.publish(Long.parseLong(template),2);
        String templateVersion=templates.versions(false,Long.parseLong(template)).get(0).get("id").toString();
        var complete=new AiApplicationPolicy(incomplete.modelMode(),incomplete.models(),false,new TemplateVersionRef(template,templateVersion),incomplete.inputNames(),incomplete.requirements(),incomplete.scope(),incomplete.allowedSubjects(),incomplete.allowedAuthorities(),"1",10,2,2,100);
        var draft=fixture.applications.updateDraft(Long.parseLong(app),0,new AiApplicationStore.Draft("Legacy","Legacy purpose","lowcode.record.summary/1",complete));
        var tested=fixture.runtime.test(Long.parseLong(app),draft.revision(),0,new AiRuntimeService.Submission(new BusinessRef("lowcode.record","1:11"),Map.of("user_request","brief"),UUID.randomUUID().toString()),fixture.actor);
        Instant until=Instant.now().plusSeconds(5);AiRequestStore.Request result;
        do{result=fixture.runtime.status(Long.parseLong(tested.requestId()),fixture.actor);if(result.status().terminal())break;Thread.sleep(10);}while(Instant.now().isBefore(until));
        assertEquals(AiRequestStatus.SUCCEEDED,result.status());baseVersion=fixture.versions.publish(Long.parseLong(app),draft.revision(),List.of(tested.requestId()),fixture.actor).appVersionId();
        fixture.jdbc.update("INSERT INTO sys_ai_scene(scene_key,name,purpose,scene_kind,form_id,source,app_id,legacy_base_app_version_id,enabled,created_by) VALUES(?,'Legacy','Legacy purpose','RECORD_SUMMARY','1','LEGACY_V04',?,?,TRUE,101)",key,app,baseVersion);
        fixture.jdbc.update("INSERT INTO sys_ai_scene_version(scene_key,version_number,saved_revision,app_version_id,source,legacy_template_id,legacy_template_version_id,created_by) VALUES(?,1,0,?,'LEGACY_V04',?,?,101)",key,baseVersion,template,templateVersion);
        fixture.jdbc.update("UPDATE sys_ai_scene SET current_version_id=(SELECT scene_version_id FROM sys_ai_scene_version WHERE scene_key=?) WHERE scene_key=?",key,key);
    }
    @AfterEach void close(){if(reader!=null)reader.close();if(fixture!=null)fixture.close();SecurityContextHolder.clearContext();}
    @Test void twoExplicitSavesKeepOriginalSubjectAndApprovedResourceBaselineAndNeverAutoConvertOnRead(){
        var original=fixture.management.detail(key,fixture.actor);assertEquals("CONVERTIBLE",original.legacy().compatibility());assertTrue(original.legacy().requiresConfirmation());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version",Integer.class));
        assertEquals("LEGACY_CONVERSION_CONFIRMATION_REQUIRED",assertThrows(SceneFailure.class,()->fixture.management.save(key,new SceneDtos.Save(0,UUID.randomUUID().toString(),original.definition()),fixture.actor)).getMessage());
        var first=fixture.management.save(key,new SceneDtos.Save(0,UUID.randomUUID().toString(),original.definition(),true),fixture.actor);
        var second=fixture.management.save(key,new SceneDtos.Save(1,UUID.randomUUID().toString(),first.detail().definition()),fixture.actor);
        var head=fixture.scenes.head(key,false);assertEquals(baseVersion,head.legacyBaseVersion());assertEquals(SceneDtos.Source.LEGACY_V04,head.source());
        var version=fixture.scenes.version(second.detail().scene().currentVersionId());var policy=fixture.execution.policy(version.appVersion());
        assertEquals(Set.of("101"),policy.allowedSubjects());assertTrue(policy.allowedAuthorities().isEmpty());assertEquals(AiApplicationPolicy.Scope.FRONTEND,policy.scope());
        assertEquals(List.of("subject"),version.compiled().legacyScopes().get(0).fields());assertEquals(resourceVersion,version.compiled().legacyScopes().get(0).resource().resourceVersionId());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application_reference WHERE app_version_id=? AND reference_kind='RESOURCE'",Integer.class,version.appVersion()));
        assertNull(second.detail().legacy());
    }
    @Test void builtinFieldSelectionCannotWidenOriginalSubjectApprovalAfterConversion(){
        var original=fixture.management.detail(key,fixture.actor);var first=fixture.management.save(key,new SceneDtos.Save(0,UUID.randomUUID().toString(),original.definition(),true),fixture.actor);
        var d=first.detail().definition();var widened=new SceneDtos.Definition(d.name(),d.purpose(),"Use summary","",List.of(new SceneDtos.DataObject("lowcode.record","1",List.of("summary"))),d.modelPolicy());
        var denied=assertThrows(AiRuntimeException.class,()->fixture.management.save(key,new SceneDtos.Save(1,UUID.randomUUID().toString(),widened),fixture.actor));assertEquals(ErrorCode.RESOURCE_FORBIDDEN,denied.code());
        assertEquals(1,fixture.management.detail(key,fixture.actor).scene().revision());
        var other=new TrustedActor("102","tenant_b",Set.of("ai:scene:invoke","lowcode:record:query"),null);int calls=fixture.provider.calls.get();
        assertThrows(SceneFailure.class,()->fixture.service.submit(key,fixture.input(true,UUID.randomUUID().toString()),other));assertEquals(calls,fixture.provider.calls.get());
    }
}
