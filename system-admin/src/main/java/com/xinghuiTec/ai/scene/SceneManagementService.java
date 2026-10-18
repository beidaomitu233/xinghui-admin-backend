package com.xinghuiTec.ai.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xinghuiTec.ai.application.AiApplicationPolicy;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.config.SecretCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

import static com.xinghuiTec.ai.scene.SceneFailure.require;

/** Direct-save orchestration only; neither static compilation nor save invokes a provider. */
@Service
public class SceneManagementService {
    private final SceneStore scenes;
    private final SceneExecutionStore execution;
    private final AiApplicationReferences references;
    private final ObjectProvider<AiSceneDataRuntime> data;
    private final ObjectProvider<AiProviderRuntime> providers;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;
    private ObjectProvider<AiSceneLegacyTemplates> legacy;
    @org.springframework.beans.factory.annotation.Autowired
    public void legacyTemplates(ObjectProvider<AiSceneLegacyTemplates> legacy){this.legacy=legacy;}
    public SceneManagementService(SceneStore scenes,SceneExecutionStore execution,AiApplicationReferences references,
            ObjectProvider<AiSceneDataRuntime> data,ObjectProvider<AiProviderRuntime> providers,SecretCipher cipher,ObjectMapper mapper) {
        this.scenes=scenes;this.execution=execution;this.references=references;this.data=data;this.providers=providers;this.cipher=cipher;
        this.mapper=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public SceneDtos.Page page(int page,int size,String search) {
        return new SceneDtos.Page(scenes.page(page,size,search).stream().map(this::summary).toList(),Long.toString(scenes.count(search)),page,size);
    }
    public SceneDtos.Workspace workspace(int page,int size,String search,String selected,TrustedActor actor) {
        return references.withReferenceGuard(()->new SceneDtos.Workspace(page(page,size,search),selected==null?null:detail(selected,actor)));
    }
    public SceneDtos.Detail detail(String key,TrustedActor actor) {
        var h=scenes.head(key,false);SceneDtos.Definition definition=null;
        SceneDtos.LegacyDetails legacyDetails=null;
        if(h.currentVersion()!=null){var current=scenes.version(h.currentVersion());definition=current.definition();
            if(current.compiled()==null && h.legacyBaseVersion()!=null){var view=legacyView(h,actor);
                var editor=view.editor()==null?null:new SceneDtos.LegacyEditor(view.editor().prompt(),view.editor().outputInstructions(),view.editor().selections().stream().map(s->new SceneDtos.DataObject(s.objectKey(),s.formId(),s.fields())).toList());
                legacyDetails=new SceneDtos.LegacyDetails(view.compatibility().name(),view.reasons(),view.requiresConfirmation(),view.conversionNotes(),editor,
                        view.originalMessages().stream().map(m->new SceneDtos.LegacyMessage(m.section(),m.text())).toList(),view.originalDefinition().values());
                if(editor!=null)definition=new SceneDtos.Definition(h.name(),h.purpose(),editor.promptText(),editor.outputRequirement(),editor.dataObjects(),wirePolicy(execution.policy(h.legacyBaseVersion())));
            }
        }
        return new SceneDtos.Detail(summary(h),definition==null?null:singlePrompt(definition),legacyDetails);
    }
    public SceneDtos.Detail create(SceneDtos.Create input,TrustedActor actor) {
        require(input!=null && input.kind()!=null,400,"INVALID_INPUT");text(input.name(),128,"name");purpose(input.purpose());SceneStore.mutationId(input.clientMutationId());
        if(input.kind()==SceneDtos.Kind.RECORD_SUMMARY)SceneStore.decimal(input.formId());else require(input.formId()==null,400,"INVALID_INPUT");
        return references.withReferenceGuard(()->{
            if(input.kind()==SceneDtos.Kind.RECORD_SUMMARY){var source=data.getIfUnique();require(source!=null,503,"DEPENDENCY_UNAVAILABLE");source.fields("lowcode.record",input.formId(),actor,control(5));}
            String key=input.kind()==SceneDtos.Kind.RECORD_SUMMARY?"lowcode.record.summary."+input.formId():"scene."+UUID.nameUUIDFromBytes((actor.subjectId()+":"+actor.tenantId()+":"+input.clientMutationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","");
            String hash=fingerprint("CREATE",Map.of("input",input,"subject",actor.subjectId(),"tenant",actor.tenantId()));
            var replay=scenes.mutation(key,input.clientMutationId());if(replay!=null){require(replay.fingerprint().equals(hash),409,"IDEMPOTENCY_CONFLICT");return replay.result().detail();}
            // Creation has a stable identity but no executable snapshot until a valid configuration save.
            scenes.create(key,input,actor.subjectId());var result=detail(key,actor);scenes.mutation(key,input.clientMutationId(),hash,new SceneDtos.SaveResult(result,input.clientMutationId(),null));return result;
        });
    }
    public SceneDtos.SaveResult save(String key,SceneDtos.Save input,TrustedActor actor) {
        require(input!=null && input.definition()!=null && input.expectedRevision()>=0,400,"INVALID_INPUT");SceneStore.mutationId(input.clientMutationId());
        return references.withReferenceGuard(()->{
            var head=scenes.head(key,true);String fingerprint=fingerprint("SAVE",input);
            var replay=scenes.mutation(key,input.clientMutationId());if(replay!=null){require(replay.fingerprint().equals(fingerprint),409,"IDEMPOTENCY_CONFLICT");return replay.result();}
            expected(head,input.expectedRevision());var definition=bodyRouting(head,singlePrompt(input.definition()));validate(definition);
            var selections=selections(head,definition);
            var runtime=data.getIfUnique();require(runtime!=null,503,"DEPENDENCY_UNAVAILABLE");
            AiSceneDataRuntime.CompiledScene compiled;
            try {
                var editor=new AiSceneDataRuntime.EditorInput(definition.promptText(),"",selections,definition.dataBindings());
                if(head.legacyBaseVersion()!=null){
                    var view=legacyView(head,actor);require(view.compatibility()==AiSceneLegacyTemplates.Compatibility.CONVERTIBLE,409,"LEGACY_READ_ONLY");
                    boolean first=scenes.version(head.currentVersion()).compiled()==null;
                    require(!first || !view.requiresConfirmation() || input.confirmedLegacyConversion(),409,"LEGACY_CONVERSION_CONFIRMATION_REQUIRED");
                    compiled=legacy.getIfUnique().compileEdited(view.version(),legacyInvocation(head,actor),editor,control(10));
                }else compiled=runtime.compile(editor,actor,control(10));
            }
            catch(AiSceneDataRuntime.InvalidConfiguration invalid) {throw new SceneFailure(400,"INVALID_SCENE_CONFIGURATION",new SceneDtos.ErrorDetails(invalid.diagnostics().stream().map(d->new SceneDtos.FieldError(fieldPath(d.path()),d.code().name())).toList(),head.revision(),head.currentVersion(),null));}
            require(compiled!=null,503,"DEPENDENCY_UNAVAILABLE");
            var policy=policy(head,definition,compiled);
            if(head.appId()==null){String app=execution.createApplication(head,policy,actor.subjectId());scenes.attachApplication(head,app);head=scenes.head(key,true);}
            String appVersion=execution.append(head,policy,definition,actor.subjectId());
            var version=scenes.insertVersion(head,appVersion,definition,compiled,actor.subjectId());scenes.activate(head,version,definition);
            var result=new SceneDtos.SaveResult(detail(key,actor),input.clientMutationId(),version.createdAt());scenes.mutation(key,input.clientMutationId(),fingerprint,result);return result;
        });
    }
    public SceneRoutingDtos.View routing(String key) {
        return references.withReferenceGuard(()->{
            var head=scenes.head(key,true);
            return new SceneRoutingDtos.View(key,head.revision(),head.currentVersion(),head.currentVersion()==null?null:wirePolicy(execution.policy(scenes.version(head.currentVersion()).appVersion())));
        });
    }
    public SceneRoutingDtos.Saved routing(String key,SceneRoutingDtos.Change input,TrustedActor actor) {
        require(input!=null && input.modelPolicy()!=null && input.expectedRevision()>=0,400,"INVALID_INPUT");SceneStore.mutationId(input.clientMutationId());
        return references.withReferenceGuard(()->{
            var head=scenes.head(key,true);String hash=fingerprint("ROUTING",input);
            var replay=scenes.mutation(key,input.clientMutationId());
            if(replay!=null) {
                require(replay.fingerprint().equals(hash),409,"IDEMPOTENCY_CONFLICT");
                var detail=replay.result().detail();
                return new SceneRoutingDtos.Saved(new SceneRoutingDtos.View(key,detail.scene().revision(),detail.scene().currentVersionId(),detail.definition().modelPolicy()),input.clientMutationId(),replay.result().effectiveFrom());
            }
            expected(head,input.expectedRevision());require(head.currentVersion()!=null,409,"SCENE_UNCONFIGURED");
            var current=scenes.version(head.currentVersion());require(current.definition()!=null && current.compiled()!=null,409,"LEGACY_EDITOR_UNAVAILABLE");
            var d=current.definition();
            var definition=new SceneDtos.Definition(d.name(),d.purpose(),d.promptText(),d.outputRequirement(),d.dataObjects(),input.modelPolicy(),d.dataBindings());
            validate(definition);var policy=policy(head,definition,current.compiled());
            var provider=providers.getIfUnique();require(provider!=null,503,"DEPENDENCY_UNAVAILABLE");
            var validation=provider.validateBindings(policy.models(),policy.requirements());
            require(validation.valid(),409,"ROUTING_UNAVAILABLE");
            String appVersion=execution.append(head,policy,definition,actor.subjectId());
            var version=scenes.insertRoutingVersion(head,current,appVersion,definition,actor.subjectId());scenes.activate(head,version,definition);
            var detail=new SceneDtos.Detail(summary(scenes.head(key,false)),definition);
            var result=new SceneDtos.SaveResult(detail,input.clientMutationId(),version.createdAt());scenes.mutation(key,input.clientMutationId(),hash,result);
            return new SceneRoutingDtos.Saved(new SceneRoutingDtos.View(key,detail.scene().revision(),version.id(),definition.modelPolicy()),input.clientMutationId(),version.createdAt());
        });
    }
    private boolean sameRouting(SceneDtos.ModelPolicy left,SceneDtos.ModelPolicy right) {
        if(left.mode()!=right.mode() || left.fallback()!=right.fallback() || left.outputFormat()!=right.outputFormat()
                || left.timeoutSeconds()!=right.timeoutSeconds() || left.maxAttempts()!=right.maxAttempts()
                || left.maxConcurrent()!=right.maxConcurrent() || left.maxTokens()!=right.maxTokens() || left.models().size()!=right.models().size()) return false;
        for(int i=0;i<left.models().size();i++) {
            var one=left.models().get(i);var two=right.models().get(i);
            if(!Objects.equals(one.model(),two.model()) || one.keyMode()!=two.keyMode() || !Set.copyOf(one.allowedKeys()).equals(Set.copyOf(two.allowedKeys()))
                    || !Objects.equals(one.fixedKey(),two.fixedKey()) || !one.fallbackKeys().equals(two.fallbackKeys())) return false;
        }
        return true;
    }
    private SceneDtos.Definition bodyRouting(SceneStore.Head head,SceneDtos.Definition definition) {
        SceneDtos.ModelPolicy route=definition.modelPolicy();
        if(head.currentVersion()!=null) {
            require(definition.initialModel()==null,409,"ROUTING_MANAGEMENT_REQUIRED");
            var preserved=wirePolicy(execution.policy(scenes.version(head.currentVersion()).appVersion()));
            require(route==null || sameRouting(route,preserved),409,"ROUTING_MANAGEMENT_REQUIRED");
            route=preserved;
        } else if(definition.initialModel()!=null) {
            require(route==null,400,"INVALID_INPUT");
            var model=definition.initialModel();SceneStore.decimal(model.modelRef());SceneStore.decimal(model.configVersion());
            route=new SceneDtos.ModelPolicy(SceneDtos.ModelMode.FIXED,List.of(new SceneDtos.ModelBinding(model,SceneDtos.KeyMode.AUTO,List.of(),null,List.of())),false,SceneDtos.OutputFormat.TEXT,120,8,2,1024);
            var provider=providers.getIfUnique();require(provider!=null,503,"DEPENDENCY_UNAVAILABLE");
            require(provider.validateBindings(List.of(new AiProviderRuntime.ModelBinding(new ModelVersionRef(model.modelRef(),model.configVersion()),AiProviderRuntime.KeyMode.AUTO,Set.of(),null,List.of())),new Requirements(OutputFormat.TEXT,true,false)).valid(),409,"ROUTING_UNAVAILABLE");
        }
        return new SceneDtos.Definition(definition.name(),definition.purpose(),definition.promptText(),definition.outputRequirement(),definition.dataObjects(),route,definition.dataBindings());
    }
    public SceneDtos.SaveResult enabled(String key,SceneDtos.Enabled input,TrustedActor actor) {
        require(input!=null && input.expectedRevision()>=0,400,"INVALID_INPUT");SceneStore.mutationId(input.clientMutationId());
        return references.withReferenceGuard(()->{
            var head=scenes.head(key,true);String hash=fingerprint("ENABLED",input);var replay=scenes.mutation(key,input.clientMutationId());
            if(replay!=null){require(replay.fingerprint().equals(hash),409,"IDEMPOTENCY_CONFLICT");return replay.result();}
            expected(head,input.expectedRevision());var updated=scenes.enabled(head,input.enabled());
            var result=new SceneDtos.SaveResult(detail(key,actor),input.clientMutationId(),updated.updatedAt());scenes.mutation(key,input.clientMutationId(),hash,result);return result;
        });
    }
    SceneDtos.Summary summary(SceneStore.Head h) {
        return new SceneDtos.Summary(h.key(),h.name(),h.purpose(),h.kind(),h.formId(),h.source(),h.enabled(),h.revision(),h.currentVersion(),availability(h),h.updatedAt());
    }
    SceneDtos.Availability availability(SceneStore.Head h) {
        if(!h.enabled())return new SceneDtos.Availability(SceneDtos.AvailabilityState.DISABLED,List.of("SCENE_DISABLED"));
        if(h.currentVersion()==null)return new SceneDtos.Availability(SceneDtos.AvailabilityState.UNCONFIGURED,List.of("SCENE_UNCONFIGURED"));
        if(!"MAPPED".equals(h.compatibilityStatus()))return new SceneDtos.Availability(SceneDtos.AvailabilityState.UNAVAILABLE,List.of("AMBIGUOUS_LEGACY_MAPPING"));
        try {
            var version=scenes.version(h.currentVersion());var policy=execution.policy(version.appVersion());var provider=providers.getIfUnique();
            if(provider==null || data.getIfUnique()==null)return new SceneDtos.Availability(SceneDtos.AvailabilityState.UNAVAILABLE,List.of("DEPENDENCY_UNAVAILABLE"));
            var validation=provider.validateBindings(policy.models(),policy.requirements());
            return new SceneDtos.Availability(validation.valid()?SceneDtos.AvailabilityState.READY:SceneDtos.AvailabilityState.UNAVAILABLE,
                    validation.violations().stream().map(v->v.code().name()).distinct().toList());
        }catch(RuntimeException unavailable){return new SceneDtos.Availability(SceneDtos.AvailabilityState.UNAVAILABLE,List.of("MODEL_UNAVAILABLE"));}
    }
    private AiApplicationPolicy policy(SceneStore.Head h,SceneDtos.Definition d,AiSceneDataRuntime.CompiledScene compiled) {
        var p=d.modelPolicy();var models=p.models().stream().map(b->new AiProviderRuntime.ModelBinding(
                new ModelVersionRef(b.model().modelRef(),b.model().configVersion()),AiProviderRuntime.KeyMode.valueOf(b.keyMode().name()),
                b.allowedKeys().stream().map(k->new KeyRef(k.keyRef(),k.keyVersion())).collect(java.util.stream.Collectors.toSet()),
                b.fixedKey()==null?null:new KeyRef(b.fixedKey().keyRef(),b.fixedKey().keyVersion()),b.fallbackKeys().stream().map(k->new KeyRef(k.keyRef(),k.keyVersion())).toList())).toList();
        Set<String> subjects=Set.of();Set<String> authorities=Set.of("ai:scene:invoke");AiApplicationPolicy.Scope scope=AiApplicationPolicy.Scope.FRONTEND;
        // Migration never expands an existing application's permitted caller set or scope.
        if(h.legacyBaseVersion()!=null){var old=execution.policy(h.legacyBaseVersion());subjects=old.allowedSubjects();authorities=old.allowedAuthorities();scope=old.scope();}
        else if(h.currentVersion()!=null){var old=execution.policy(scenes.version(h.currentVersion()).appVersion());subjects=old.allowedSubjects();authorities=old.allowedAuthorities();scope=old.scope();}
        return new AiApplicationPolicy(AiApplicationPolicy.ModelMode.valueOf(p.mode().name()),models,p.fallback(),null,
                compiled.inputVariables().stream().map(AiSceneDataRuntime.InputVariable::name).collect(java.util.stream.Collectors.toSet()),
                new Requirements(OutputFormat.valueOf(p.outputFormat().name()),true,false),scope,subjects,authorities,h.formId(),
                p.timeoutSeconds(),p.maxAttempts(),p.maxConcurrent(),p.maxTokens());
    }
    private AiSceneLegacyTemplates.LegacyView legacyView(SceneStore.Head head,TrustedActor actor){
        var bridge=legacy==null?null:legacy.getIfUnique();require(bridge!=null,503,"DEPENDENCY_UNAVAILABLE");
        var base=execution.policy(head.legacyBaseVersion());require(base.template()!=null,409,"LEGACY_READ_ONLY");
        return bridge.inspect(base.template(),legacyInvocation(head,actor),control(10));
    }
    private Invocation legacyInvocation(SceneStore.Head head,TrustedActor actor){
        require(actor!=null,403,"FORBIDDEN");return new Invocation(actor,head.appId(),head.legacyBaseVersion(),new BusinessRef("ai.scene",head.key()),UUID.randomUUID().toString());
    }
    private SceneDtos.ModelPolicy wirePolicy(AiApplicationPolicy policy){
        var bindings=policy.models().stream().map(b->new SceneDtos.ModelBinding(new SceneDtos.ModelRef(b.model().modelRef(),b.model().configVersion()),SceneDtos.KeyMode.valueOf(b.keyMode().name()),
                b.allowedKeys().stream().map(k->new SceneDtos.KeyRef(k.keyRef(),k.keyVersion())).toList(),b.fixedKey()==null?null:new SceneDtos.KeyRef(b.fixedKey().keyRef(),b.fixedKey().keyVersion()),
                b.fallbackKeys().stream().map(k->new SceneDtos.KeyRef(k.keyRef(),k.keyVersion())).toList())).toList();
        return new SceneDtos.ModelPolicy(SceneDtos.ModelMode.valueOf(policy.modelMode().name()),bindings,policy.modelFallback(),SceneDtos.OutputFormat.valueOf(policy.requirements().outputFormat().name()),policy.timeoutSeconds(),policy.maxAttempts(),policy.maxConcurrent(),policy.maxTokens());
    }
    private String fieldPath(String path){
        if(path.equals("prompt"))return "definition.promptText";
        if(path.equals("outputInstructions"))return "definition.outputRequirement";
        if(path.matches("selections(?:\\[[0-9]{1,2}\\])?(?:\\.[A-Za-z]{1,32})?"))return path.replaceFirst("selections","definition.dataObjects");
        return "definition";
    }
    private List<AiSceneDataRuntime.Selection> selections(SceneStore.Head h,SceneDtos.Definition d) {
        require(d.dataObjects().size()<=1,400,"INVALID_INPUT");
        if(h.kind()==SceneDtos.Kind.RECORD_SUMMARY && d.dataObjects().isEmpty()
                && d.dataBindings().stream().noneMatch(b->("lowcode."+h.formId()).equals(b.sourceKey()) && b.row()!=null && b.row().currentRecord()))throw SceneFailure.field("definition.dataObjects","REQUIRED");
        return d.dataObjects().stream().map(o->{String form=o.formId()==null?h.formId():o.formId();SceneStore.decimal(form);
            if(h.kind()==SceneDtos.Kind.RECORD_SUMMARY)require(h.formId().equals(form),400,"INVALID_INPUT");
            return new AiSceneDataRuntime.Selection(o.objectKey(),form,o.fields());}).toList();
    }
    private void validate(SceneDtos.Definition d) {
        require(d.dataBindings().size()<=16,400,"INVALID_INPUT");
        if(d.promptText()==null)throw SceneFailure.field("definition.promptText","REQUIRED");
        text(d.name(),128,"name");purpose(d.purpose());require(d.modelPolicy()!=null,400,"INVALID_INPUT");
        var p=d.modelPolicy();require(p.mode()!=null && p.outputFormat()!=null && p.models()!=null && !p.models().isEmpty() && p.models().size()<=16,400,"INVALID_INPUT");
        require(!(p.mode()==SceneDtos.ModelMode.FIXED && !p.fallback() && p.models().size()!=1),400,"INVALID_INPUT");
        require(p.timeoutSeconds()>=1 && p.timeoutSeconds()<=120 && p.maxAttempts()>=1 && p.maxAttempts()<=8 && p.maxConcurrent()>=1 && p.maxConcurrent()<=10 && p.maxTokens()>=1 && p.maxTokens()<=4096,400,"INVALID_INPUT");
        for(var b:p.models()){require(b!=null && b.model()!=null && b.keyMode()!=null,400,"INVALID_INPUT");SceneStore.decimal(b.model().modelRef());SceneStore.decimal(b.model().configVersion());
            require(b.allowedKeys().size()<=32 && b.fallbackKeys().size()<=32,400,"INVALID_INPUT");
            for(var k:b.allowedKeys()){SceneStore.decimal(k.keyRef());SceneStore.decimal(k.keyVersion());}for(var k:b.fallbackKeys()){SceneStore.decimal(k.keyRef());SceneStore.decimal(k.keyVersion());}
            if(b.fixedKey()!=null){SceneStore.decimal(b.fixedKey().keyRef());SceneStore.decimal(b.fixedKey().keyVersion());}}
    }
    private void expected(SceneStore.Head h,int expected){if(h.revision()!=expected)throw new SceneFailure(409,"VERSION_CONFLICT",new SceneDtos.ErrorDetails(List.of(),h.revision(),h.currentVersion(),null));}
    static SceneDtos.Definition singlePrompt(SceneDtos.Definition d){
        String prompt=d.promptText();
        if(d.outputRequirement()!=null && !d.outputRequirement().isEmpty())prompt=(prompt==null?"":prompt)+"\n\n输出要求：\n"+d.outputRequirement();
        return new SceneDtos.Definition(d.name(),d.purpose(),prompt,"",d.dataObjects(),d.modelPolicy(),d.dataBindings(),d.initialModel());
    }
    private String fingerprint(String kind,Object input){try{return cipher.fingerprint("ai:scene-mutation:v1",mapper.writeValueAsBytes(Map.of("kind",kind,"input",input)));}catch(Exception invalid){throw new SceneFailure(503,"CREDENTIAL_UNAVAILABLE");}}
    private void text(String text,int limit,String field){if(text==null || text.isBlank() || text.length()>limit || text.codePoints().anyMatch(Character::isISOControl))throw SceneFailure.field(field,"INVALID_INPUT");}
    private void purpose(String value){if(value==null || value.length()>512 || value.codePoints().anyMatch(Character::isISOControl))throw SceneFailure.field("purpose","INVALID_INPUT");}
    static InvocationControl control(int seconds){return new InvocationControl(){private final Instant deadline=Instant.now().plusSeconds(seconds);public Instant deadline(){return deadline;}public boolean cancelled(){return Thread.currentThread().isInterrupted();}};}
}
