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
    private AiConversationRuntime conversations;
    private com.xinghuiTec.security.PlatformAccessService platformAccess;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void platformAccess(com.xinghuiTec.security.PlatformAccessService platformAccess){this.platformAccess=platformAccess;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void conversations(AiConversationRuntime conversations){this.conversations=conversations;}
    public SceneRuntimeService(SceneStore scenes,SceneManagementService management,SceneExecutionStore execution,AiRuntimeService runtime,
            AiApplicationReferences references,ObjectProvider<AiSceneDataRuntime> data){this.scenes=scenes;this.management=management;this.execution=execution;this.runtime=runtime;this.references=references;this.data=data;}
    public List<SceneDtos.Entry> candidates(SceneDtos.Kind kind,String formId,TrustedActor actor){
        caller(actor);return scenes.candidates(kind,formId).stream().filter(h->visible(h,actor)).map(h->entry(h,actor)).toList();
    }
    public SceneDtos.Entry recordSummary(String formId,TrustedActor actor){
        caller(actor);SceneStore.decimal(formId);var candidates=scenes.candidates(SceneDtos.Kind.RECORD_SUMMARY,formId).stream().filter(h->visible(h,actor)).toList();
        require(candidates.size()<=1,409,"AMBIGUOUS_LEGACY_MAPPING");return candidates.isEmpty()?null:entry(candidates.get(0),actor);
    }
    public SceneDtos.Request submit(String key,SceneDtos.Submission input,TrustedActor actor){return submit(key,null,null,input,actor);}
    public SceneDtos.Request test(String key,SceneDtos.TestInput input,TrustedActor actor){
        require(input!=null && input.confirmedCosts(),400,"COST_CONFIRMATION_REQUIRED");return submit(key,input.expectedRevision(),input.modelIndex(),input.submission(),actor);
    }
    private record Selected(SceneStore.Head head,SceneStore.Version version,AiRuntimeService.Submission input,AiRequestStore.Request replay) {
        Selected(SceneStore.Head head,SceneStore.Version version,AiRuntimeService.Submission input){this(head,version,input,null);}
    }
    private SceneDtos.Request submit(String key,Integer expected,Integer model,SceneDtos.Submission input,TrustedActor actor){
        caller(actor);
        Selected selected=references.withReferenceGuard(()->{
            var head=scenes.head(key,true);require(head.currentVersion()!=null,409,"SCENE_UNCONFIGURED");require(head.enabled(),409,"SCENE_DISABLED");
            require("MAPPED".equals(head.compatibilityStatus()),409,"AMBIGUOUS_LEGACY_MAPPING");
            if(head.appId()!=null){
                require(input!=null && input.inputs()!=null,400,"INVALID_INPUT");SceneStore.mutationId(input.idempotencyKey());
                var ref=input.businessRef()==null?new BusinessRef("ai.scene",key):new BusinessRef(input.businessRef().type(),input.businessRef().id());
                var replay=runtime.replayScene(Long.parseLong(head.appId()),key,expected!=null,model==null?-1:model,
                        new AiRuntimeService.Submission(ref,input.inputs(),input.idempotencyKey(),conversationTarget(input)),actor);
                if(replay!=null)return new Selected(head,scenes.version(replay.sceneVersionId()),null,replay);
            }
            if(expected!=null)expected(head,expected);
            var version=scenes.version(head.currentVersion());var policy=execution.policy(version.appVersion());
            require(expected==null?policy.permits(actor):policy.permitsSubject(actor),403,"FORBIDDEN");
            return new Selected(head,version,normalize(head,version,input));
        });
        if(selected.replay()!=null)return project(selected.replay());
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
        // C enforces current data/secret boundaries. Ordinary authorized business values are useful preview content.
        var context=new AiSceneDataRuntime.SceneContext(key,selected.version.id(),actor,selected.input.businessRef(),java.util.UUID.randomUUID().toString());
        var rendered=provider.render(selected.version.compiled(),context,new AiInputs(selected.input.inputs()),SceneManagementService.control(10));
        var original=List.of(new SceneDtos.Message("USER",SceneManagementService.singlePrompt(selected.version.definition()).promptText()));
        var messages=rendered.messages().stream().map(m->new SceneDtos.Message(m.role().name(),m.content())).toList();
        var inputs=new java.util.LinkedHashMap<String,String>();selected.input.inputs().forEach((name,value)->inputs.put(name,String.valueOf(value)));
        var resources=new java.util.LinkedHashMap<String,String>();for(var use:rendered.dataUses())resources.put(use.objectKey(),"已读取授权字段");
        var preview=rendered.bindingPreview();
        return new SceneDtos.Preview(key,selected.version.id(),original,messages,inputs,resources,rendered.notices().stream().map(AiSceneDataRuntime.Notice::code).toList(),preview==null?java.util.Map.of():preview.values(),preview==null?List.of():preview.reads());
    }
    private boolean visible(SceneStore.Head h,TrustedActor actor){
        if(h.currentVersion()==null || !"MAPPED".equals(h.compatibilityStatus()))return false;
        var v=scenes.version(h.currentVersion());var policy=execution.policy(v.appVersion());if(!policy.permits(actor))return false;
        try{
            if(h.kind()==SceneDtos.Kind.RECORD_SUMMARY){var service=data.getIfUnique();if(service==null)return false;service.fields("lowcode.record",h.formId(),actor,SceneManagementService.control(5));}
            else if(v.compiled()!=null && !v.compiled().selections().isEmpty()){var service=data.getIfUnique();if(service==null)return false;
                for(var selection:v.compiled().selections())service.fields(selection.objectKey(),selection.formId(),actor,SceneManagementService.control(5));}
            if(v.compiled()!=null && !v.compiled().bindings().isEmpty()){var service=data.getIfUnique();if(service==null)return false;
                for(var binding:v.compiled().bindings())service.databaseFields(binding.binding().sourceKey(),actor,SceneManagementService.control(5));}
            return true;
        }catch(AiRuntimeException denied){return false;}
    }
    private SceneDtos.Entry entry(SceneStore.Head h,TrustedActor actor){
        var version=scenes.version(h.currentVersion());String source=null,label=null;boolean required=false;
        if(h.kind()==SceneDtos.Kind.RECORD_SUMMARY){required=true;source="lowcode."+h.formId();label=h.name();}
        else if(version.compiled()!=null){
            var current=version.compiled().bindings().stream().filter(b->b.binding().row()!=null && b.binding().row().currentRecord()).toList();
            if(!current.isEmpty()){required=true;if(current.stream().map(b->b.binding().sourceKey()).distinct().count()==1){source=current.get(0).binding().sourceKey();label=current.get(0).binding().label();}}
            else if(!version.compiled().selections().isEmpty()){required=true;source="lowcode."+version.compiled().selections().get(0).formId();label=h.name();}
        }else required=!execution.snapshot(version.appVersion()).resources().isEmpty();
        if(source!=null){
            try{
                var service=data.getIfUnique();require(service!=null,503,"DEPENDENCY_UNAVAILABLE");
                service.databaseFields(source,actor,SceneManagementService.control(5));
                if(source.startsWith("lowcode."))require(actor.authorities().contains("lowcode:record:list"),403,"FORBIDDEN");
            }catch(AiRuntimeException|SceneFailure denied){source=null;label=null;}
        }
        boolean picker=platformAccess!=null && platformAccess.isPlatformIdentity() && actor.authorities().stream().anyMatch(p->java.util.Set.of("ai:scene:query","ai:scene:add","ai:scene:edit","ai:scene:test").contains(p));
        return new SceneDtos.Entry(h.key(),h.name(),h.kind(),h.formId(),h.currentVersion(),h.revision(),management.availability(h),required,source,label,!required || (source!=null && picker));
    }
    private AiRuntimeService.Submission normalize(SceneStore.Head h,SceneStore.Version v,SceneDtos.Submission input){
        require(input!=null && input.inputs()!=null,400,"INVALID_INPUT");SceneStore.mutationId(input.idempotencyKey());
        BusinessRef business;
        boolean currentBinding=v.compiled()!=null && v.compiled().bindings().stream().anyMatch(b->b.binding().row()!=null && b.binding().row().currentRecord());
        boolean noSelections=v.compiled()!=null?v.compiled().selections().isEmpty():execution.snapshot(v.appVersion()).resources().isEmpty();
        if(h.kind()==SceneDtos.Kind.GENERAL && noSelections && !currentBinding && input.conversationId()!=null && input.businessRef()!=null){
            business=new BusinessRef(input.businessRef().type(),input.businessRef().id());
        }else if(h.kind()==SceneDtos.Kind.GENERAL && noSelections && !currentBinding){
            require(input.businessRef()==null || ("ai.scene".equals(input.businessRef().type()) && h.key().equals(input.businessRef().id())),400,"INVALID_INPUT");business=new BusinessRef("ai.scene",h.key());
        }else if(currentBinding && input.businessRef()!=null && "database.record".equals(input.businessRef().type())){
            require(h.kind()==SceneDtos.Kind.GENERAL && input.businessRef().id()!=null && input.businessRef().id().matches("db1\\.[A-Za-z0-9_-]{1,2044}"),400,"INVALID_INPUT");
            business=new BusinessRef("database.record",input.businessRef().id());
        }else{
            require(input.businessRef()!=null && "lowcode.record".equals(input.businessRef().type()),400,"INVALID_INPUT");
            String[] ids=input.businessRef().id()==null?new String[0]:input.businessRef().id().split(":",-1);require(ids.length==2,400,"INVALID_INPUT");SceneStore.decimal(ids[0]);SceneStore.decimal(ids[1]);
            String form=h.formId();if(form==null && v.compiled()!=null && !v.compiled().selections().isEmpty())form=v.compiled().selections().get(0).formId();
            if(form!=null)require(form.equals(ids[0]),403,"RESOURCE_FORBIDDEN");business=new BusinessRef("lowcode.record",input.businessRef().id());
        }
        return new AiRuntimeService.Submission(business,new AiInputs(input.inputs()).values(),input.idempotencyKey(),conversationTarget(input));
    }
    private AiConversationRuntime.Target conversationTarget(SceneDtos.Submission input){
        if(input.conversationId()==null){require(input.expectedConversationRevision()==null,400,"INVALID_INPUT");return null;}
        SceneStore.decimal(input.conversationId());require(input.expectedConversationRevision()!=null && input.expectedConversationRevision()>=0,400,"INVALID_INPUT");
        return new AiConversationRuntime.Target(input.conversationId(),input.expectedConversationRevision());
    }
    SceneDtos.Request project(AiRequestStore.Request r){
        var version=r.sceneVersionId()!=null?scenes.version(r.sceneVersionId()):r.appVersionId()==null?null:scenes.applicationVersion(r.appVersionId());
        var link=conversations==null?null:conversations.link(Long.parseLong(r.requestId()));
        require(version!=null,404,"NOT_FOUND");return new SceneDtos.Request(r.requestId(),version.sceneKey(),version.id(),r.kind().name(),new SceneDtos.BusinessRef(r.businessType(),r.businessId()),
                r.status().name(),r.stage().name(),r.errorCode(),r.outputStarted(),r.attemptCount(),r.inputTokens(),r.outputTokens(),r.usageKnown(),r.deadline(),r.createdAt(),r.finishedAt(),link==null?null:link.conversationId(),link==null?null:link.userMessageId(),link==null?null:link.assistantMessageId());
    }
    private void expected(SceneStore.Head h,int expected){if(h.revision()!=expected)throw new SceneFailure(409,"VERSION_CONFLICT",new SceneDtos.ErrorDetails(List.of(),h.revision(),h.currentVersion(),null));}
    private void caller(TrustedActor actor){require(actor!=null && actor.servicePrincipalRef()==null && actor.authorities().contains("ai:scene:invoke"),403,"FORBIDDEN");}
}
