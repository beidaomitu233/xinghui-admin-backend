package com.xinghuiTec.ai.modelhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.application.AiApplicationPolicy;
import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.scene.SceneFailure;
import com.xinghuiTec.config.SecretCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.modelhub.ModelHubDtos.*;

/** Logical identities resolve to current, admitted provider versions once per new request. No network health claims. */
@Service
public class ModelHubService {
    public static final int MODEL_LIMIT=512;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiConfigurationService configs;
    private final ObjectProvider<AiProviderRuntime> providers;
    private final AiApplicationReferences guard;
    private final SecretCipher cipher;
    public ModelHubService(JdbcTemplate jdbc,ObjectMapper json,AiConfigurationService configs,ObjectProvider<AiProviderRuntime> providers,AiApplicationReferences guard,SecretCipher cipher){this.jdbc=jdbc;this.json=json.copy();this.configs=configs;this.providers=providers;this.guard=guard;this.cipher=cipher;}
    public static void check(boolean value,int status,String code){if(!value)throw new SceneFailure(status,code);}
    public static long id(String value){check(value!=null&&value.matches("[1-9][0-9]{0,18}"),400,"INVALID_INPUT");try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new SceneFailure(400,"INVALID_INPUT");}}
    private record Stored(String id,int revision,Change definition,boolean enabled,long cursor,Instant updated) {}
    private Stored readStored(java.sql.ResultSet r)throws java.sql.SQLException{return new Stored(r.getString("model_id"),r.getInt("revision"),decode(r.getString("definition_json"),Change.class),r.getBoolean("enabled"),r.getLong("next_index"),r.getTimestamp("update_time").toInstant());}
    private Stored stored(String value,boolean lock){
        var rows=jdbc.query("SELECT * FROM sys_ai_business_model WHERE model_id=? AND deleted=FALSE"+(lock?" FOR UPDATE":""),(r,n)->readStored(r),id(value));
        check(rows.size()==1,404,"MODEL_NOT_FOUND");return rows.get(0);
    }
    public Model get(String value){return view(stored(value,false));}
    public Model bySlug(String slug){return view(find(slug,false));}
    private Stored find(String slug,boolean lock){
        check(slug!=null&&slug.matches("[a-z0-9][a-z0-9._:-]{0,79}"),400,"INVALID_MODEL_SLUG");
        var values=jdbc.query("SELECT model_id FROM sys_ai_business_model WHERE slug=? AND deleted=FALSE"+(lock?" FOR UPDATE":""),(r,n)->r.getString(1),slug);
        check(values.size()==1,404,"MODEL_NOT_FOUND");return stored(values.get(0),lock);
    }
    public Page page(int page,int size,String search){
        check(page>=1&&page<=100000&&size>=1&&size<=100&&(search==null||search.length()<=128),400,"INVALID_INPUT");
        String term=search==null?"":search.toLowerCase(Locale.ROOT).strip();
        String where=" WHERE deleted=FALSE";var args=new ArrayList<Object>();
        if(!term.isEmpty()){
            where+=" AND (LOWER(JSON_UNQUOTE(JSON_EXTRACT(definition_json,'$.name'))) LIKE ? ESCAPE '!' OR slug LIKE ? ESCAPE '!')";
            String pattern="%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(pattern);args.add(pattern);
        }
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_business_model"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var selected=jdbc.query("SELECT * FROM sys_ai_business_model"+where+" ORDER BY model_id LIMIT ? OFFSET ?",(r,n)->readStored(r),args.toArray());
        var context=healthContext(selected,new Requirements(OutputFormat.TEXT,true,false));
        return new Page(selected.stream().map(s->view(s,context)).toList(),Long.toString(Objects.requireNonNull(count)),page,size);
    }
    public Summary summary(){return guard.withReferenceGuard(()->{
        var rows=jdbc.query("SELECT * FROM sys_ai_business_model WHERE deleted=FALSE ORDER BY model_id LIMIT ?",(r,n)->readStored(r),MODEL_LIMIT+1);
        check(rows.size()<=MODEL_LIMIT,503,"MODEL_LIMIT_EXCEEDED");
        var context=healthContext(rows,new Requirements(OutputFormat.TEXT,true,false));var models=rows.stream().map(s->view(s,context)).toList();
        int enabled=(int)models.stream().filter(Model::enabled).count(),ready=(int)models.stream().filter(m->m.health().ready()).count();
        int protectedModels=(int)models.stream().filter(m->m.health().multiProviderProtected()).count();
        return new Summary(models.size(),enabled,ready,enabled-ready,models.size()-enabled,protectedModels,enabled-protectedModels,MODEL_LIMIT,Instant.now());
    });}
    public Model save(String value,Change input,TrustedActor actor){
        validate(input);return guard.withReferenceGuard(()->saveNow(value,input,actor,false));
    }
    private Model saveNow(String value,Change input,TrustedActor actor,boolean enableOnly){
        String hash=hash(List.of(value==null?"CREATE":value,input,enableOnly));String scope=actor.subjectId()+"@"+actor.tenantId();
        var replay=jdbc.queryForList("SELECT input_hash,result_json FROM sys_ai_business_model_mutation WHERE owner_scope=? AND mutation_id=?",scope,input.clientMutationId());
        if(!replay.isEmpty()){check(hash.equals(replay.get(0).get("input_hash")),409,"IDEMPOTENCY_CONFLICT");return decode((String)replay.get(0).get("result_json"),Model.class);}
        Stored old=value==null?null:stored(value,true);
        if(old!=null){check(input.expectedRevision()!=null&&input.expectedRevision()==old.revision(),409,"VERSION_CONFLICT");check(old.definition().slug().equals(input.slug()),409,"MODEL_SLUG_IMMUTABLE");}
        check((old!=null&&old.enabled()==input.enabled())||(!input.enabled()&&old==null)||actor.authorities().contains("ai:config:enable"),403,"FORBIDDEN");
        if(!enableOnly)for(var route:input.routes()){
            AiConfigurationDtos.View channel;
            try{channel=configs.get(id(route.channelId()));}catch(RuntimeException missing){throw new SceneFailure(400,"CHANNEL_UNAVAILABLE");}
            check(channel.draft()!=null&&channel.draft().models().stream().anyMatch(m->m.modelCode().equals(route.upstreamModel())),400,"UPSTREAM_NOT_CONFIGURED");
        }
        var routes=input.routes().stream().map(r->new Route(r.routeId()==null?UUID.randomUUID().toString():r.routeId(),r.channelId(),r.upstreamModel(),r.enabled())).toList();
        var normalized=new Change(null,null,input.name(),input.slug(),input.capabilities(),input.contextWindow(),input.enabled(),input.strategy(),input.fallback(),routes);
        if(old==null){
            check(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_business_model WHERE deleted=FALSE",Long.class)<MODEL_LIMIT,409,"MODEL_LIMIT_EXCEEDED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_business_model WHERE slug=?",Long.class,input.slug())==0L,409,"MODEL_SLUG_EXISTS");
            var keys=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement("INSERT INTO sys_ai_business_model(slug,definition_json,enabled) VALUES(?,?,?)",new String[]{"model_id"});s.setString(1,input.slug());s.setString(2,encode(normalized));s.setBoolean(3,input.enabled());return s;},keys);
            value=Objects.requireNonNull(keys.getKey()).toString();
        }else check(jdbc.update("UPDATE sys_ai_business_model SET definition_json=?,enabled=?,revision=revision+1,next_index=0,update_time=CURRENT_TIMESTAMP(3) WHERE model_id=? AND revision=?",encode(normalized),input.enabled(),id(value),old.revision())==1,409,"VERSION_CONFLICT");
        var result=get(value);jdbc.update("INSERT INTO sys_ai_business_model_mutation(owner_scope,mutation_id,input_hash,model_id,result_json) VALUES(?,?,?,?,?)",scope,input.clientMutationId(),hash,id(value),encode(result));return result;
    }
    public Model enabled(String value,Enabled input,TrustedActor actor){
        check(input!=null&&actor.authorities().contains("ai:config:enable"),403,"FORBIDDEN");
        return guard.withReferenceGuard(()->{var s=stored(value,true);var d=s.definition();var change=new Change(input.expectedRevision(),input.clientMutationId(),d.name(),d.slug(),d.capabilities(),d.contextWindow(),input.enabled(),d.strategy(),d.fallback(),d.routes());validate(change);return saveNow(value,change,actor,true);});
    }
    public void delete(String value,int revision){guard.withReferenceGuard(()->{
        var old=stored(value,true);check(old.revision()==revision,409,"VERSION_CONFLICT");
        var definitions=jdbc.query("SELECT v.definition_json FROM sys_ai_scene s JOIN sys_ai_scene_version v ON v.scene_version_id=s.current_version_id",(r,n)->r.getString(1));
        for(String definition:definitions)try{check(!old.definition().slug().equals(json.readTree(definition).path("businessModel").asText()),409,"SCENE_MODEL_IN_USE");}catch(java.io.IOException corrupt){throw new SceneFailure(503,"STATE_UNAVAILABLE");}
        jdbc.update("UPDATE sys_ai_business_model SET deleted=TRUE,enabled=FALSE,revision=revision+1,update_time=CURRENT_TIMESTAMP(3) WHERE model_id=?",id(value));return true;
    });}
    private void validate(Change d){
        check(d!=null,400,"INVALID_INPUT");text(d.name(),50);metadata(d.capabilities(),128);metadata(d.contextWindow(),32);
        check(d.slug()!=null&&d.slug().matches("[a-z0-9][a-z0-9._:-]{0,79}")&&d.strategy()!=null&&d.routes().size()<=16,400,"INVALID_INPUT");
        check(d.clientMutationId()!=null&&d.clientMutationId().matches("[a-fA-F0-9-]{36}"),400,"INVALID_MUTATION_ID");
        var identities=new HashSet<String>();var routeIds=new HashSet<String>();
        for(var r:d.routes()){check(r!=null,400,"INVALID_INPUT");id(r.channelId());text(r.upstreamModel(),128);check(identities.add(r.channelId()+"/"+r.upstreamModel()),400,"DUPLICATE_ROUTE");if(r.routeId()!=null)check(r.routeId().matches("[a-fA-F0-9-]{36}")&&routeIds.add(r.routeId()),400,"INVALID_ROUTE_ID");}
    }
    private void text(String s,int maximum){check(s!=null&&!s.isBlank()&&s.length()<=maximum&&s.codePoints().noneMatch(Character::isISOControl),400,"INVALID_INPUT");}
    private void metadata(String s,int maximum){check(s!=null&&s.length()<=maximum&&s.codePoints().noneMatch(Character::isISOControl),400,"INVALID_INPUT");}
    private boolean textCapable(Change d){return !d.capabilities().contains("向量")&&!d.capabilities().toLowerCase(Locale.ROOT).contains("embedding");}
    private List<AiProviderRuntime.AvailableModel> available(Requirements requirements){var p=providers.getIfUnique();return p==null?List.of():p.availableModels(requirements);}
    private record Channel(String provider,String name,String status,Set<String> upstreamModels) {}
    private record HealthContext(List<AiProviderRuntime.AvailableModel> available,Map<String,Channel> channels) {}
    private HealthContext healthContext(List<Stored> models,Requirements requirements){
        var available=available(requirements);var ids=models.stream().flatMap(m->m.definition().routes().stream()).map(Route::channelId).distinct().toList();
        var channels=new HashMap<String,Channel>();
        if(!ids.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            jdbc.query("SELECT c.config_id,c.config_name AS name,c.provider_code,c.status,v.snapshot_json FROM sys_ai_configuration c LEFT JOIN sys_ai_configuration_version v ON v.version_id=c.draft_version_id AND v.config_id=c.config_id WHERE c.config_id IN ("+placeholders+")",(org.springframework.jdbc.core.RowCallbackHandler)r->{
                try{var snapshot=json.readValue(r.getString("snapshot_json"),AiConfigurationDtos.Snapshot.class);
                    channels.put(r.getString("config_id"),new Channel(r.getString("provider_code"),r.getString("name"),r.getString("status"),snapshot.models().stream().map(AiConfigurationDtos.ModelSpec::modelCode).collect(java.util.stream.Collectors.toSet())));
                }catch(Exception unavailable){/* Missing/corrupt draft is unavailable, never a ready binding. */}
            },ids.stream().map(ModelHubService::id).toArray());
        }
        return new HealthContext(available,channels);
    }
    private Model view(Stored s){return view(s,healthContext(List.of(s),new Requirements(OutputFormat.TEXT,true,false)));}
    private Model view(Stored s,HealthContext context){
        var d=s.definition();var available=context.available();
        var routes=new ArrayList<RouteView>();var distinct=new HashSet<String>();int ready=0;
        for(var r:d.routes()){
            var reasons=new ArrayList<String>();String provider=null,name=null;boolean configured=false;
            if(!r.enabled())reasons.add("BINDING_DISABLED");
            var channel=context.channels().get(r.channelId());
            if(channel==null)reasons.add("CHANNEL_UNAVAILABLE");else{provider=channel.provider();name=channel.name();configured=channel.upstreamModels().contains(r.upstreamModel());if(!configured)reasons.add("UPSTREAM_NOT_CONFIGURED");if(!"ACTIVE".equals(channel.status()))reasons.add("CHANNEL_NOT_ACTIVE");}
            boolean usable=available.stream().anyMatch(a->a.configId().equals(r.channelId())&&a.modelCode().equals(r.upstreamModel()));
            if(!usable)reasons.add("NO_VERIFIED_ACTIVE_PAIR");if(!textCapable(d))reasons.add("UNSUPPORTED_CAPABILITY");
            boolean routeReady=reasons.isEmpty();if(routeReady){ready++;distinct.add(provider);}
            routes.add(new RouteView(r.routeId(),r.channelId(),r.upstreamModel(),r.enabled(),new RouteHealth(routeReady,List.copyOf(reasons),provider,name)));
        }
        var reasons=new ArrayList<String>();if(!s.enabled())reasons.add("MODEL_DISABLED");if(ready==0)reasons.add("NO_READY_BINDING");
        return new Model(s.id(),s.revision(),d.name(),d.slug(),d.capabilities(),d.contextWindow(),s.enabled(),d.strategy(),d.fallback(),List.copyOf(routes),new Health(s.enabled()&&ready>0,ready,distinct.size(),s.enabled()&&distinct.size()>=2&&d.fallback(),List.copyOf(reasons)),s.updated());
    }
    public record Frozen(String modelId,int revision,AiApplicationPolicy policy) {}
    public Frozen freeze(AiApplicationPolicy base){return guard.withReferenceGuard(()->{
        var s=find(base.businessModel(),true);var context=healthContext(List.of(s),base.requirements());var model=view(s,context);check(model.health().ready(),409,"BUSINESS_MODEL_NOT_READY");
        var available=context.available();var bindings=new ArrayList<AiProviderRuntime.ModelBinding>();
        for(var r:model.routes())if(r.health().ready()){
            var matches=available.stream().filter(a->a.configId().equals(r.channelId())&&a.modelCode().equals(r.upstreamModel())).toList();
            if(matches.size()==1)bindings.add(new AiProviderRuntime.ModelBinding(matches.get(0).model(),AiProviderRuntime.KeyMode.AUTO,Set.of(),null,List.of()));
        }
        check(!bindings.isEmpty(),409,"BUSINESS_MODEL_NOT_READY");
        if(model.strategy()==Strategy.ROUND_ROBIN){int first=(int)Math.floorMod(s.cursor(),(long)bindings.size());Collections.rotate(bindings,-first);jdbc.update("UPDATE sys_ai_business_model SET next_index=? WHERE model_id=?",s.cursor()==Long.MAX_VALUE?0:s.cursor()+1,id(s.id()));}
        if(!model.fallback()&&bindings.size()>1)bindings.subList(1,bindings.size()).clear();
        var concrete=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,bindings,model.fallback(),base.template(),base.inputNames(),base.requirements(),base.scope(),base.allowedSubjects(),base.allowedAuthorities(),base.formId(),base.timeoutSeconds(),base.maxAttempts(),base.maxConcurrent(),base.maxTokens());
        return new Frozen(s.id(),s.revision(),concrete);
    });}
    public void pin(long requestId,Frozen frozen,TrustedActor actor,boolean test){jdbc.update("INSERT INTO sys_ai_business_model_request(request_id,model_id,model_revision,owner_scope,route_json,test_request) VALUES(?,?,?,?,?,?)",requestId,id(frozen.modelId()),frozen.revision(),actor.subjectId()+"@"+actor.tenantId(),encode(frozen.policy()),test);}
    private String hash(Object value){try{return cipher.fingerprint("ai:business-model:v1",json.writeValueAsBytes(value));}catch(Exception e){throw new SceneFailure(503,"STATE_UNAVAILABLE");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new SceneFailure(503,"STATE_UNAVAILABLE");}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new SceneFailure(503,"STATE_UNAVAILABLE");}}
}
