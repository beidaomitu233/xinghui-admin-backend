package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.xinghuiTec.ai.scene.SceneFailure.require;

/** Scene-facing identity/projection; execution/stream/cancellation remain in the existing request runtime. */
@Service
public class SceneRuntimeService {
    private final SceneStore scenes;
    private final SceneManagementService management;
    private final SceneExecutionStore execution;
    private final AiRuntimeService runtime;
    private final AiApplicationReferences references;
    private final ObjectProvider<AiSceneDataRuntime> data;
    public SceneRuntimeService(SceneStore scenes,SceneManagementService management,SceneExecutionStore execution,AiRuntimeService runtime,
            AiApplicationReferences references,ObjectProvider<AiSceneDataRuntime> data){this.scenes=scenes;this.management=management;this.execution=execution;this.runtime=runtime;this.references=references;this.data=data;}
    public List<SceneDtos.Entry> candidates(SceneDtos.Kind kind,String formId,TrustedActor actor){
        caller(actor);return scenes.candidates(kind,formId).stream().filter(h->visible(h,actor)).map(this::entry).toList();
    }
    public SceneDtos.Entry recordSummary(String formId,TrustedActor actor){
        caller(actor);SceneStore.decimal(formId);var candidates=scenes.candidates(SceneDtos.Kind.RECORD_SUMMARY,formId).stream().filter(h->visible(h,actor)).toList();
        require(candidates.size()<=1,409,"AMBIGUOUS_LEGACY_MAPPING");return candidates.isEmpty()?null:entry(candidates.get(0));
    }
    public SceneDtos.Request submit(String key,SceneDtos.Submission input,TrustedActor actor){return submit(key,null,null,input,actor);}
    public SceneDtos.Request test(String key,SceneDtos.TestInput input,TrustedActor actor){
        require(input!=null && input.confirmedCosts(),400,"COST_CONFIRMATION_REQUIRED");return submit(key,input.expectedRevision(),input.modelIndex(),input.submission(),actor);
    }
    private record Selected(SceneStore.Head head,SceneStore.Version version,AiRuntimeService.Submission input) {}
    private SceneDtos.Request submit(String key,Integer expected,Integer model,SceneDtos.Submission input,TrustedActor actor){
        caller(actor);
        Selected selected=references.withReferenceGuard(()->{
            var head=scenes.head(key,true);require(head.currentVersion()!=null,409,"SCENE_UNCONFIGURED");require(head.enabled(),409,"SCENE_DISABLED");
            require("MAPPED".equals(head.compatibilityStatus()),409,"AMBIGUOUS_LEGACY_MAPPING");
            if(expected!=null)expected(head,expected);
            var version=scenes.version(head.currentVersion());var policy=execution.policy(version.appVersion());
            require(expected==null?policy.permits(actor):policy.permitsSubject(actor),403,"FORBIDDEN");
            return new Selected(head,version,normalize(head,version,input));
        });
        AiRequestStore.Request accepted;
        if(selected.version.compiled()==null){
            require(expected==null,409,"LEGACY_EDITOR_UNAVAILABLE");
            accepted=runtime.submit(Long.parseLong(selected.head.appId()),selected.input,actor);
        }else accepted=runtime.submitScene(Long.parseLong(selected.head.appId()),Long.parseLong(selected.version.appVersion()),expected!=null,model==null?-1:model,selected.input,actor);
        return project(accepted);
    }
    public SceneDtos.Request status(long id,TrustedActor actor){caller(actor);return project(runtime.status(id,actor));}
    public SceneDtos.Request cancel(long id,TrustedActor actor){caller(actor);return project(runtime.cancel(id,actor));}
    public SceneDtos.Preview preview(String key,SceneDtos.PreviewInput input,TrustedActor actor){
        caller(actor);require(input!=null,400,"INVALID_INPUT");
        var selected=references.withReferenceGuard(()->{var h=scenes.head(key,true);expected(h,input.expectedRevision());require(h.currentVersion()!=null,409,"SCENE_UNCONFIGURED");
            var v=scenes.version(h.currentVersion());require(v.compiled()!=null,409,"LEGACY_EDITOR_UNAVAILABLE");
            require(execution.policy(v.appVersion()).permitsSubject(actor),403,"FORBIDDEN");
            return new Selected(h,v,normalize(h,v,new SceneDtos.Submission(input.businessRef(),input.inputs(),java.util.UUID.randomUUID().toString())));});
        var provider=data.getIfUnique();require(provider!=null,503,"DEPENDENCY_UNAVAILABLE");
        // Rendering is server-only. A management preview receives masks rather than the authorized record body.
        var context=new AiSceneDataRuntime.SceneContext(key,selected.version.id(),actor,selected.input.businessRef(),java.util.UUID.randomUUID().toString());
        var rendered=provider.render(selected.version.compiled(),context,new AiInputs(selected.input.inputs()),SceneManagementService.control(10));
        var original=List.of(new SceneDtos.Message("SYSTEM",selected.version.definition().promptText()));
        var messages=rendered.messages().stream().map(m->new SceneDtos.Message(m.role().name(),"[已脱敏]" )).toList();
        var inputs=new java.util.LinkedHashMap<String,String>();selected.input.inputs().keySet().forEach(name->inputs.put(name,"[已脱敏]"));
        var resources=new java.util.LinkedHashMap<String,String>();for(var use:rendered.dataUses())resources.put(use.objectKey(),"[已脱敏]");
        return new SceneDtos.Preview(key,selected.version.id(),original,messages,inputs,resources,rendered.notices().stream().map(AiSceneDataRuntime.Notice::code).toList());
    }
    private boolean visible(SceneStore.Head h,TrustedActor actor){
        if(h.currentVersion()==null || !"MAPPED".equals(h.compatibilityStatus()))return false;
        var v=scenes.version(h.currentVersion());var policy=execution.policy(v.appVersion());if(!policy.permits(actor))return false;
        try{
            if(h.kind()==SceneDtos.Kind.RECORD_SUMMARY){var service=data.getIfUnique();if(service==null)return false;service.fields("lowcode.record",h.formId(),actor,SceneManagementService.control(5));}
            else if(v.compiled()!=null && !v.compiled().selections().isEmpty()){var service=data.getIfUnique();if(service==null)return false;
                for(var selection:v.compiled().selections())service.fields(selection.objectKey(),selection.formId(),actor,SceneManagementService.control(5));}
            return true;
        }catch(AiRuntimeException denied){return false;}
    }
    private SceneDtos.Entry entry(SceneStore.Head h){return new SceneDtos.Entry(h.key(),h.name(),h.kind(),h.formId(),h.currentVersion(),h.revision(),management.availability(h));}
    private AiRuntimeService.Submission normalize(SceneStore.Head h,SceneStore.Version v,SceneDtos.Submission input){
        require(input!=null && input.inputs()!=null,400,"INVALID_INPUT");SceneStore.mutationId(input.idempotencyKey());
        BusinessRef business;
        if(h.kind()==SceneDtos.Kind.GENERAL && v.compiled()!=null && v.compiled().selections().isEmpty()){
            require(input.businessRef()==null || ("ai.scene".equals(input.businessRef().type()) && h.key().equals(input.businessRef().id())),400,"INVALID_INPUT");business=new BusinessRef("ai.scene",h.key());
        }else{
            require(input.businessRef()!=null && "lowcode.record".equals(input.businessRef().type()),400,"INVALID_INPUT");
            String[] ids=input.businessRef().id()==null?new String[0]:input.businessRef().id().split(":",-1);require(ids.length==2,400,"INVALID_INPUT");SceneStore.decimal(ids[0]);SceneStore.decimal(ids[1]);
            String form=h.formId();if(form==null && v.compiled()!=null && !v.compiled().selections().isEmpty())form=v.compiled().selections().get(0).formId();
            if(form!=null)require(form.equals(ids[0]),403,"RESOURCE_FORBIDDEN");business=new BusinessRef("lowcode.record",input.businessRef().id());
        }
        return new AiRuntimeService.Submission(business,new AiInputs(input.inputs()).values(),input.idempotencyKey());
    }
    SceneDtos.Request project(AiRequestStore.Request r){
        var version=r.sceneVersionId()!=null?scenes.version(r.sceneVersionId()):r.appVersionId()==null?null:scenes.applicationVersion(r.appVersionId());
        require(version!=null,404,"NOT_FOUND");return new SceneDtos.Request(r.requestId(),version.sceneKey(),version.id(),r.kind().name(),new SceneDtos.BusinessRef(r.businessType(),r.businessId()),
                r.status().name(),r.stage().name(),r.errorCode(),r.outputStarted(),r.attemptCount(),r.inputTokens(),r.outputTokens(),r.usageKnown(),r.deadline(),r.createdAt(),r.finishedAt());
    }
    private void expected(SceneStore.Head h,int expected){if(h.revision()!=expected)throw new SceneFailure(409,"VERSION_CONFLICT",new SceneDtos.ErrorDetails(List.of(),h.revision(),h.currentVersion(),null));}
    private void caller(TrustedActor actor){require(actor!=null && actor.servicePrincipalRef()==null && actor.authorities().contains("ai:scene:invoke"),403,"FORBIDDEN");}
}
