package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xinghuiTec.ai.prompt.AiBusinessRenderException.Code.*;

/** Thin, bounded transport adapter to AI-03. No model configuration/credentials are read here. */
@Service
public class AiSceneReadService {
    private record Conversion<C extends AiBusinessVariableResolver.BusinessContext>(
            AiSceneContextConverter<C> converter, Class<C> type, Set<String> allowed, Set<String> required) {}
    private static final Set<String> FORBIDDEN_FIELDS=Set.of("userId","tenantId","authorities","actor","username",
            "roleId","permission","className","beanName","sql","url","expression");
    private final Map<String,Conversion<?>> conversions;
    private final AiBusinessPromptRenderer renderer;
    private final AiVariableResolverRegistry resolvers;
    private final AiPromptSceneRules rules;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(512).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public AiSceneReadService(List<AiSceneContextConverter<?>> converters,AiBusinessPromptRenderer renderer,
                              AiVariableResolverRegistry resolvers,AiPromptSceneRules rules) {
        this.renderer=renderer; this.resolvers=resolvers; this.rules=rules;
        Map<String,Conversion<?>> registered=new HashMap<>();
        for(var converter:converters) {
            try {
                String app=rules.appKey(converter.appKey());
                var conversion=capture(converter);
                if(resolvers.require(app).contextType()!=conversion.type()) throw new AiBusinessRenderException(INVALID_REGISTRATION);
                if(registered.putIfAbsent(app,conversion)!=null) throw new AiBusinessRenderException(DUPLICATE_REGISTRATION);
            } catch(AiBusinessRenderException invalid) { throw new AiBusinessRenderException(
                    invalid.code()==DUPLICATE_REGISTRATION ? DUPLICATE_REGISTRATION : INVALID_REGISTRATION); }
            catch(RuntimeException invalid) { throw new AiBusinessRenderException(INVALID_REGISTRATION); }
        }
        conversions=Map.copyOf(registered);
    }

    private <C extends AiBusinessVariableResolver.BusinessContext> Conversion<C> capture(AiSceneContextConverter<C> converter) {
        var allowed=Set.copyOf(converter.allowedFields()); var required=Set.copyOf(converter.requiredFields());
        if(converter.contextType()==null || allowed.size()>16 || !allowed.containsAll(required)
                || allowed.stream().anyMatch(field->!field.matches("[A-Za-z][A-Za-z0-9_]{0,31}") || FORBIDDEN_FIELDS.contains(field))) {
            throw new AiBusinessRenderException(INVALID_REGISTRATION);
        }
        return new Conversion<>(converter,converter.contextType(),allowed,required);
    }

    public AiBusinessPromptRenderer.RenderedPrompt read(String appKey,String sceneKey,byte[] body) {
        var actor=renderer.authenticatedActor();
        Conversion<?> conversion=appKey==null ? null : conversions.get(appKey);
        if(conversion==null) throw new AiBusinessRenderException(APP_NOT_REGISTERED);
        if(!actor.authorities().contains(resolvers.require(appKey).requiredAuthority())) {
            throw new AiBusinessRenderException(BUSINESS_PERMISSION_REQUIRED);
        }
        try { rules.appKey(appKey); rules.sceneKey(sceneKey); }
        catch(RuntimeException invalid) { throw new AiBusinessRenderException(CONTEXT_INVALID); }
        return renderer.render(appKey,sceneKey,convert(conversion,body));
    }

    public boolean registeredApp(String appKey) { return appKey!=null && conversions.containsKey(appKey); }

    private <C extends AiBusinessVariableResolver.BusinessContext> C convert(Conversion<C> conversion,byte[] body) {
        try {
            if(body==null || body.length==0 || body.length>16_384) throw new AiBusinessRenderException(CONTEXT_INVALID);
            JsonNode envelope=json.readTree(body);
            if(envelope==null || !envelope.isObject() || envelope.size()!=1 || !envelope.has("context")) throw new AiBusinessRenderException(CONTEXT_INVALID);
            JsonNode context=envelope.get("context");
            if(!context.isObject() || context.size()>16) throw new AiBusinessRenderException(CONTEXT_INVALID);
            Map<String,String> fields=new HashMap<>();
            var names=context.fieldNames();
            while(names.hasNext()) {
                String name=names.next(); JsonNode value=context.get(name);
                if(!conversion.allowed().contains(name) || !value.isTextual() || value.textValue().isBlank()
                        || value.textValue().length()>256) throw new AiBusinessRenderException(CONTEXT_INVALID);
                fields.put(name,value.textValue());
            }
            if(!fields.keySet().containsAll(conversion.required())) throw new AiBusinessRenderException(CONTEXT_INVALID);
            C typed=conversion.converter().convert(Map.copyOf(fields));
            if(!conversion.type().isInstance(typed)) throw new AiBusinessRenderException(CONTEXT_INVALID);
            return typed;
        } catch(Exception invalid) { throw new AiBusinessRenderException(CONTEXT_INVALID); }
    }
}
