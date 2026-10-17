package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;

@Service
public class AiConfiguredProviderRuntime implements AiProviderRuntime {
    private final AiConfigurationService configs;
    private final OpenRouterTransport transport;
    public AiConfiguredProviderRuntime(AiConfigurationService configs,OpenRouterTransport transport) {
        this.configs=configs; this.transport=transport;
    }
    @Override public List<AvailableModel> availableModels(Requirements requirements) {
        var rows=configs.jdbc.query("SELECT config_id,active_version_id FROM sys_ai_configuration WHERE status='ACTIVE' AND active_version_id IS NOT NULL",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)});
        var result=new ArrayList<AvailableModel>();
        for(var row:rows) {
            var h=configs.head(row[0],false); var s=configs.snapshot(row[0],row[1]); var pairs=configs.pairs(row[1]);
            for(var m:s.models()) {
                var capabilities=OpenRouterCapabilities.forModel(m);
                if(!capabilities.supports(requirements)) continue;
                int count=(int)pairs.stream().filter(p->p.modelRef().equals(m.modelRef())).filter(p->s.keys().stream().anyMatch(k->matches(p,k)&&AiConfigurationService.associated(m,k)&&credentialUsable(k)&&p.proofHash().equals(configs.proof(s,m,k)))).count();
                if(count>0) result.add(new AvailableModel(new ModelVersionRef(m.modelRef(),s.configVersion()),Long.toString(row[0]),h.name(),s.providerCode(),m.modelCode(),capabilities,count));
            }
        }
        return List.copyOf(result);
    }
    private boolean credentialUsable(KeySpec key) {
        try { if(!configs.keyUsable(key)) return false; configs.resolveSecret(key); return true; } catch(RuntimeException failure) { return false; }
    }
    private boolean matches(AdmittedPair pair,KeySpec key) { return pair.keyRef().equals(key.keyRef())&&pair.keyVersion().equals(key.keyVersion()); }
    record Selected(long configId,AiConfigurationService.Head head,Snapshot snapshot,ModelSpec model,List<AdmittedPair> pairs) {}
    private Selected selected(ModelVersionRef ref,boolean currentOnly) {
        long modelId=AiConfigurationService.id(ref.modelRef()),version=AiConfigurationService.id(ref.configVersion());
        var rows=configs.jdbc.query("SELECT config_id FROM sys_ai_configuration_model WHERE model_ref=?",(rs,n)->rs.getLong(1),modelId);
        if(rows.isEmpty()) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
        long configId=rows.get(0); var head=configs.currentHead(configId);
        if(!"ACTIVE".equals(head.status())||(currentOnly&&!Objects.equals(head.active(),version))) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
        var snapshot=configs.snapshot(configId,version); var pairs=configs.pairs(version);
        var model=snapshot.models().stream().filter(m->m.modelRef().equals(ref.modelRef())).findFirst().orElseThrow(()->new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE));
        if(pairs.isEmpty()) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
        return new Selected(configId,head,snapshot,model,pairs);
    }
    @Override public Validation validateBindings(List<ModelBinding> bindings,Requirements requirements) {
        var violations=new ArrayList<Violation>();
        if(bindings.isEmpty()) violations.add(new Violation("models",ErrorCode.MODEL_UNAVAILABLE));
        for(int index=0;index<bindings.size();index++) {
            try {
                var binding=bindings.get(index); var selected=selected(binding.model(),true);
                if(!OpenRouterCapabilities.forModel(selected.model()).supports(requirements)) violations.add(new Violation("models["+index+"].requirements",ErrorCode.CAPABILITY_UNSUPPORTED));
                var requestedKeys=new HashSet<>(binding.allowedKeys());
                if(binding.fixedKey()!=null) requestedKeys.add(binding.fixedKey());
                requestedKeys.addAll(binding.fallbackKeys());
                boolean invalidRange=requestedKeys.stream().anyMatch(ref->selected.snapshot().keys().stream().noneMatch(k->k.keyRef().equals(ref.keyRef())&&k.keyVersion().equals(ref.keyVersion())
                        &&AiConfigurationService.associated(selected.model(),k)&&selected.pairs().stream().anyMatch(p->p.modelRef().equals(binding.model().modelRef())&&matches(p,k))));
                if(invalidRange) violations.add(new Violation("models["+index+"].keys",ErrorCode.NO_USABLE_KEY));
                var control=new InvocationControl() {
                    public java.time.Instant deadline() { return java.time.Instant.now().plusSeconds(10); }
                    public boolean cancelled() { return false; }
                };
                if(resolveTargets(binding,Set.of(),control).isEmpty()) violations.add(new Violation("models["+index+"]",ErrorCode.NO_USABLE_KEY));
            } catch(RuntimeException failure) { violations.add(new Violation("models["+index+"]",ErrorCode.MODEL_UNAVAILABLE)); }
        }
        return new Validation(violations);
    }
    @Override public List<Target> resolveTargets(ModelBinding binding,Set<KeyRef> attemptedKeys,InvocationControl control) {
        control.check(); var s=selected(binding.model(),false); var candidates=new ArrayList<Target>();
        List<KeyRef> fixed=new ArrayList<>();
        if(binding.keyMode()==KeyMode.FIXED) { fixed.add(binding.fixedKey()); fixed.addAll(binding.fallbackKeys()); }
        for(var key:s.snapshot().keys()) {
            control.check(); var ref=new KeyRef(key.keyRef(),key.keyVersion());
            if(!AiConfigurationService.associated(s.model(),key)||attemptedKeys.contains(ref)||(!binding.allowedKeys().isEmpty()&&!binding.allowedKeys().contains(ref))
                    ||(binding.keyMode()==KeyMode.FIXED&&!fixed.contains(ref))) continue;
            if(s.pairs().stream().noneMatch(p->p.modelRef().equals(binding.model().modelRef())&&matches(p,key)&&p.proofHash().equals(configs.proof(s.snapshot(),s.model(),key)))||!credentialUsable(key)) continue;
            candidates.add(new Target(binding.model(),ref,s.snapshot().providerCode(),s.model().modelCode(),key.label(),OpenRouterCapabilities.forModel(s.model())));
        }
        if(binding.keyMode()==KeyMode.FIXED) candidates.sort(Comparator.comparingInt(t->fixed.indexOf(t.key())));
        return List.copyOf(candidates);
    }
    @Override public ExecutionOutcome execute(ExecutionRequest request,StreamObserver observer,InvocationControl control) {
        control.check();
        try {
            // Re-read current status immediately before each new attempt, while retaining the pinned snapshot.
            var selected=selected(request.target().model(),false); var target=request.target();
            if(!OpenRouterCapabilities.forModel(selected.model()).supports(request.requirements())) throw new AiRuntimeException(ErrorCode.CAPABILITY_UNSUPPORTED);
            var key=selected.snapshot().keys().stream().filter(k->k.keyRef().equals(target.key().keyRef())&&k.keyVersion().equals(target.key().keyVersion()))
                    .findFirst().orElseThrow(()->new AiRuntimeException(ErrorCode.NO_USABLE_KEY));
            if(!AiConfigurationService.associated(selected.model(),key) || !configs.keyUsable(key)) throw new AiRuntimeException(ErrorCode.NO_USABLE_KEY);
            String secret=configs.resolveSecret(key);
            if(selected.pairs().stream().noneMatch(p->p.modelRef().equals(target.model().modelRef())&&matches(p,key)&&p.proofHash().equals(configs.proof(selected.snapshot(),selected.model(),key,secret)))) throw new AiRuntimeException(ErrorCode.NO_USABLE_KEY);
            if(!"ACTIVE".equals(configs.currentHead(selected.configId()).status())) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            control.check();
            var snapshot=selected.snapshot();
            var outcome="openrouter".equals(snapshot.providerCode())
                    ?transport.call(selected.model().modelCode(),snapshot.timeoutSeconds(),secret,request.messages(),request.requirements(),request.maxTokens(),observer,control)
                    :transport.callForProvider(snapshot.providerCode(),snapshot.endpointUrl(),selected.model().modelCode(),snapshot.timeoutSeconds(),secret,request.messages(),request.requirements(),request.maxTokens(),observer,control);
            if(outcome.errorCode()==ErrorCode.AUTH_FAILED) configs.keyRejected(key);
            return outcome;
        } catch(AiRuntimeException classified) { throw classified; }
        catch(RuntimeException failure) { throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE); }
    }
}
