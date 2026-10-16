package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiApplicationReferences;
import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.ai.model.AiModelConfigService;
import com.xinghuiTec.ai.model.AiModelCredentialService;
import com.xinghuiTec.config.SecretCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;

@Service
public class AiConfigurationService {
    final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final AiModelConfigRules rules;
    private final TransactionTemplate transactions;
    private final ObjectProvider<AiApplicationReferences> references;
    private final AiModelConfigService legacy;
    private final AiModelCredentialService legacyCredentials;

    public AiConfigurationService(JdbcTemplate jdbc, ObjectMapper json, SecretCipher cipher,
                                  AiModelConfigRules rules, TransactionTemplate transactions,
                                  ObjectProvider<AiApplicationReferences> references,
                                  AiModelConfigService legacy, AiModelCredentialService legacyCredentials) {
        this.jdbc=jdbc; this.json=json; this.cipher=cipher; this.rules=rules;
        this.transactions=transactions; this.references=references;
        this.legacy=legacy; this.legacyCredentials=legacyCredentials;
    }

    record Head(long id, String name, String environment, String provider, String status, int revision,
                Long draft, Long active, Long latestTest, Long runningTest, Long legacyId, LocalDateTime updated) {}
    Head head(long id, boolean lock) {
        var rows=jdbc.query("SELECT * FROM sys_ai_configuration WHERE config_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Head(rs.getLong("config_id"),rs.getString("config_name"),rs.getString("environment_name"),
                        rs.getString("provider_code"),rs.getString("status"),rs.getInt("revision"),
                        nullableId(rs.getObject("draft_version_id")),nullableId(rs.getObject("active_version_id")),
                        nullableId(rs.getObject("latest_test_id")),nullableId(rs.getObject("running_test_id")),
                        nullableId(rs.getObject("legacy_model_id")),rs.getTimestamp("update_time").toLocalDateTime()),id);
        if(rows.isEmpty()) throw fail(404,"AI配置不存在");
        return rows.get(0);
    }
    private Long nullableId(Object value) { return value==null?null:((Number)value).longValue(); }
    /** Suspend an outer application RR snapshot; emergency state must be current on every attempt. */
    Head currentHead(long configId) {
        var current=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return current.execute(tx->head(configId,true));
    }
    static String str(Long id) { return id==null?null:Long.toString(id); }
    public static long id(String value) {
        try { long result=Long.parseLong(value); if(result<1) throw new NumberFormatException(); return result; }
        catch(RuntimeException invalid) { throw fail(400,"引用ID无效"); }
    }
    static AiConfigurationException fail(int status,String message) { return new AiConfigurationException(status,message); }
    static void checkRevision(Head h,int expected) {
        if(h.revision()!=expected) throw fail(409,"配置已修改，请刷新后重试");
        if("ARCHIVED".equals(h.status())) throw fail(409,"已归档配置不可修改");
    }
    long insert(String sql,Object... args) {
        var holder=new GeneratedKeyHolder();
        String table=sql.substring("INSERT INTO ".length(),sql.indexOf('('));
        String primary=Map.of("sys_ai_configuration","config_id","sys_ai_configuration_model","model_ref",
                "sys_ai_configuration_key","key_ref","sys_ai_configuration_key_material","key_version",
                "sys_ai_configuration_version","version_id","sys_ai_configuration_test","test_id",
                "sys_ai_configuration_test_pair","pair_id").get(table);
        if(primary==null) throw new IllegalArgumentException("Unknown configuration table");
        jdbc.update(connection->{
            var statement=connection.prepareStatement(sql,new String[]{primary});
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception invalid) { throw fail(500,"配置序列化失败"); }
    }
    public Snapshot snapshot(long configId,long version) {
        var rows=jdbc.query("SELECT snapshot_json FROM sys_ai_configuration_version WHERE config_id=? AND version_id=?",
                (rs,n)->rs.getString(1),configId,version);
        if(rows.isEmpty()) throw fail(404,"配置版本不存在");
        try { return json.readValue(rows.get(0),Snapshot.class); }
        catch(Exception corrupt) { throw fail(503,"配置快照不可读取"); }
    }
    List<AdmittedPair> pairs(long version) {
        String data=jdbc.queryForObject("SELECT admitted_pairs_json FROM sys_ai_configuration_version WHERE version_id=?",String.class,version);
        if(data==null) return List.of();
        try { return json.readValue(data,new TypeReference<List<AdmittedPair>>(){}); }
        catch(Exception corrupt) { throw fail(503,"启用快照不可读取"); }
    }
    private List<Pair> safePairs(long version) { return pairs(version).stream().map(AdmittedPair::safe).toList(); }
    public View get(long id) {
        var h=head(id,false);
        return new View(str(h.id()),h.name(),h.environment(),h.provider(),h.status(),h.revision(),str(h.active()),
                h.draft()==null?null:snapshot(id,h.draft()),h.active()==null?List.of():safePairs(h.active()),
                str(h.latestTest()),h.updated(),str(h.legacyId()));
    }
    public Page page(int pageNum,int pageSize,String name,String provider,String environment,String status) {
        if(pageNum<1||pageNum>100000||pageSize<1||pageSize>100) throw fail(400,"分页参数无效");
        var where=new StringBuilder(); var args=new ArrayList<Object>();
        for(var entry:List.of(new String[]{"config_name",name},new String[]{"provider_code",provider},
                new String[]{"environment_name",environment},new String[]{"status",status})) {
            if(entry[1]!=null&&!entry[1].isBlank()) { where.append(where.isEmpty()?" WHERE ":" AND ").append(entry[0]).append(" LIKE ?"); args.add("%"+entry[1].trim()+"%"); }
        }
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration"+where,Long.class,args.toArray()));
        args.add(pageSize); args.add((pageNum-1)*pageSize);
        var ids=jdbc.query("SELECT config_id FROM sys_ai_configuration"+where+" ORDER BY config_id DESC LIMIT ? OFFSET ?",(rs,n)->rs.getLong(1),args.toArray());
        var rows=ids.stream().map(configId->{
            var h=head(configId,false); var view=get(configId);
            var enabled="ACTIVE".equals(h.status())&&h.active()!=null?pairs(h.active()):List.<AdmittedPair>of();
            var time=h.latestTest()==null?null:jdbc.queryForObject("SELECT update_time FROM sys_ai_configuration_test WHERE test_id=?",java.sql.Timestamp.class,h.latestTest());
            return new Row(view.id(),view.name(),view.environment(),view.providerCode(),view.status(),view.revision(),
                    view.draft()==null?0:view.draft().models().size(),(int)enabled.stream().map(AdmittedPair::keyRef).distinct().count(),time==null?null:time.toLocalDateTime());
        }).toList();
        var legacyModels=jdbc.query("SELECT model_id,provider_code,model_code FROM sys_ai_model_config ORDER BY model_id DESC LIMIT 100",(rs,n)->
                new LegacyModel(rs.getString("model_id"),rs.getString("provider_code"),rs.getString("model_code")));
        return new Page(rows,total,pageNum,pageSize,legacyModels,OpenRouterCapabilities.jsonObjectModels());
    }
    public List<Version> versions(long configId) {
        head(configId,false);
        return jdbc.query("SELECT * FROM sys_ai_configuration_version WHERE config_id=? ORDER BY version_id DESC",(rs,n)->{
            long version=rs.getLong("version_id");
            return new Version(str(version),snapshot(configId,version),safePairs(version),rs.getTimestamp("create_time").toLocalDateTime(),rs.getString("admitted_pairs_json")!=null);
        },configId);
    }

    public View create(Create request) {
        if(request==null) throw fail(400,"请填写配置");
        if(request.legacyModelId()!=null) return importLegacy(id(request.legacyModelId()));
        return transactions.execute(tx->{
            var d=Objects.requireNonNull(request.draft()); validate(d);
            long config=insert("INSERT INTO sys_ai_configuration(config_name,environment_name,provider_code) VALUES(?,?,?)",d.name().trim(),d.environment().trim(),d.providerCode());
            saveSnapshot(config,d,null);
            return get(config);
        });
    }
    public View save(long configId,DraftChange change) {
        Supplier<View> operation=()->transactions.execute(tx->{
            var h=head(configId,true); checkRevision(h,change.revision()); validate(change);
            if(h.runningTest()!=null) throw fail(409,"请先取消或等待当前测试结束");
            var previous=snapshot(configId,h.draft());
            if(!h.provider().equals(change.providerCode())||!previous.endpointUrl().equals(change.endpointUrl())||!previous.protocol().equals(change.protocol()))
                throw fail(409,"变更提供商、协议或地址请复制为新配置");
            saveSnapshot(configId,change,previous);
            return get(configId);
        });
        var refs=references.getIfAvailable();
        // A never needs a fake reference service to edit a not-yet-live draft.
        // protectReferences rejects active configuration changes if the real service is missing.
        return refs==null?operation.get():refs.withReferenceGuard(operation);
    }
    private void validate(DraftChange d) {
        if(d==null||d.name()==null||d.name().isBlank()||d.name().length()>100||d.environment()==null||!d.environment().matches("[A-Za-z0-9_-]{1,32}"))
            throw fail(400,"请填写配置名称和环境");
        rules.providerCode(d.providerCode()); rules.endpointUrl(d.endpointUrl()); rules.timeoutSeconds(d.timeoutSeconds());
        if(!"openrouter".equals(d.providerCode())||!"OPENAI_CHAT".equals(d.protocol())||!"https://openrouter.ai/api/v1/chat/completions".equals(d.endpointUrl()))
            throw fail(400,"当前仅支持官方OpenRouter Chat端点，其他提供商尚未实现");
        if(d.models()==null||d.keys()==null||d.models().size()>16||d.keys().size()>16||d.models().size()*d.keys().size()>64)
            throw fail(400,"最多16个模型、16个Key及64个搭配");
        var codes=new HashSet<String>();
        for(var m:d.models()) {
            if(!codes.add(rules.modelCode(m.modelCode()))) throw fail(400,"模型代号重复");
            if(m.jsonObject()&&!OpenRouterCapabilities.supportsJson(m.modelCode())) throw fail(400,"该模型没有经核对的JSON对象能力，请保留文本模式");
        }
    }
    private void saveSnapshot(long configId,DraftChange d,Snapshot previous) {
        var models=new ArrayList<ModelSpec>(); var keys=new ArrayList<KeySpec>();
        var oldModels=previous==null?List.<ModelSpec>of():previous.models();
        var oldKeys=previous==null?List.<KeySpec>of():previous.keys();
        var seen=new HashSet<String>();
        for(var input:d.models()) {
            String code=rules.modelCode(input.modelCode()); String ref=input.modelRef();
            if(ref!=null&&!ref.isBlank()) {
                String wanted=ref;
                if(oldModels.stream().noneMatch(m->m.modelRef().equals(wanted)&&m.modelCode().equals(code))) throw fail(400,"模型引用与代号不匹配");
            } else {
                var existing=jdbc.query("SELECT model_ref FROM sys_ai_configuration_model WHERE config_id=? AND model_code=?",(rs,n)->rs.getLong(1),configId,code);
                ref=str(existing.isEmpty()?insert("INSERT INTO sys_ai_configuration_model(config_id,model_code) VALUES(?,?)",configId,code):existing.get(0));
            }
            models.add(new ModelSpec(ref,code,input.jsonObject(),input.jsonObject()?OpenRouterCapabilities.EVIDENCE:null));
        }
        for(var input:d.keys()) {
            String ref=input.keyRef();
            KeySpec old=null;
            if(ref!=null&&!ref.isBlank()) {
                String wanted=ref;
                old=oldKeys.stream().filter(k->k.keyRef().equals(wanted)).findFirst().orElseThrow(()->fail(400,"Key引用不存在于当前草稿"));
            } else ref=str(insert("INSERT INTO sys_ai_configuration_key(config_id) VALUES(?)",configId));
            if(!seen.add(ref)) throw fail(400,"Key引用重复");
            String label=input.label()==null||input.label().isBlank()?"Key "+(keys.size()+1):input.label().trim();
            if(label.length()>80||label.chars().anyMatch(c->c<32)) throw fail(400,"Key标签无效");
            String mode=input.mode(); String version;
            if("DB".equals(mode)) {
                if(input.apiKey()!=null&&!input.apiKey().isBlank()) {
                    String material=input.apiKey().trim();
                    if(material.length()>8192) throw fail(400,"Key长度无效");
                    String encrypted=cipher.encrypt("ai:v04:key:"+ref,material);
                    version=str(insert("INSERT INTO sys_ai_configuration_key_material(key_ref,credential_mode,credential_ciphertext) VALUES(?,?,?)",id(ref),"DB",encrypted));
                } else {
                    if(old==null||!"DB".equals(old.mode())) throw fail(400,"新增数据库Key不能为空");
                    version=old.keyVersion();
                }
                keys.add(new KeySpec(ref,version,label,mode,null));
            } else if("ENV".equals(mode)) {
                if(input.apiKey()!=null&&!input.apiKey().isBlank()) throw fail(400,"ENV模式不能填写Key明文");
                String env=rules.credentialEnvName(input.envName());
                if(env==null) throw fail(400,"请填写环境变量引用");
                version=old!=null&&"ENV".equals(old.mode())&&env.equals(old.envName())?old.keyVersion():str(insert(
                        "INSERT INTO sys_ai_configuration_key_material(key_ref,credential_mode,credential_env_name) VALUES(?,?,?)",id(ref),"ENV",env));
                keys.add(new KeySpec(ref,version,label,mode,env));
            } else throw fail(400,"Key来源无效");
        }
        var h=head(configId,false);
        if(h.active()!=null) protectReferences(configId,h.active(),models,keys);
        long version=insert("INSERT INTO sys_ai_configuration_version(config_id,snapshot_json) VALUES(?,?)",configId,"{}");
        var snap=new Snapshot(str(version),d.providerCode(),d.endpointUrl(),d.protocol(),d.timeoutSeconds(),List.copyOf(models),List.copyOf(keys));
        jdbc.update("UPDATE sys_ai_configuration_version SET snapshot_json=? WHERE version_id=?",encode(snap),version);
        jdbc.update("UPDATE sys_ai_configuration SET config_name=?,environment_name=?,draft_version_id=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE config_id=?",d.name().trim(),d.environment().trim(),version,configId);
    }
    private <T> T guarded(Supplier<T> action) {
        var refs=references.getIfAvailable();
        if(refs==null) throw fail(503,"应用引用服务尚未安装，维护操作暂不可用");
        return refs.withReferenceGuard(action);
    }
    private void protectReferences(long configId,long active,List<ModelSpec> models,List<KeySpec> keys) {
        var old=snapshot(configId,active); var admitted=pairs(active);
        var refs=references.getIfAvailable();
        if(refs==null) throw fail(503,"应用引用服务尚未安装，维护操作暂不可用");
        for(var m:old.models()) {
            boolean remains=models.stream().anyMatch(x->x.modelRef().equals(m.modelRef()));
            boolean keyRemains=admitted.stream().filter(p->p.modelRef().equals(m.modelRef()))
                    .allMatch(p->keys.stream().anyMatch(k->k.keyRef().equals(p.keyRef())&&k.keyVersion().equals(p.keyVersion())));
            if((!remains||!keyRemains)&&!refs.modelReferences(new ModelVersionRef(m.modelRef(),str(active))).isEmpty())
                throw fail(409,"在线应用引用该模型，请先迁移应用或使用紧急停用");
        }
    }
    public List<AiApplicationReferences.ApplicationReference> impact(long configId) {
        var refs=references.getIfAvailable(); if(refs==null) throw fail(503,"应用引用服务尚未安装");
        head(configId,false);
        return versions(configId).stream().filter(Version::activated).flatMap(v->v.snapshot().models().stream()
                .flatMap(m->refs.modelReferences(new ModelVersionRef(m.modelRef(),v.configVersion())).stream())).distinct().toList();
    }
    public View activate(long configId,Activate change) {
        return guarded(()->transactions.execute(tx->{
            var h=head(configId,true); checkRevision(h,change.revision());
            long version=id(change.configVersion());
            if(!Objects.equals(h.draft(),version)||h.runningTest()!=null) throw fail(409,"草稿或测试状态已变化");
            var s=snapshot(configId,version);
            if(s.models().isEmpty()||s.keys().isEmpty()) throw fail(409,"至少配置一个模型和Key");
            var accepted=new ArrayList<AdmittedPair>();
            for(var model:s.models()) for(var key:s.keys()) if(validProof(s,model,key)) accepted.add(new AdmittedPair(model.modelRef(),key.keyRef(),key.keyVersion(),proof(s,model,key)));
            if(accepted.isEmpty()) throw fail(409,"没有当前版本验证通过的搭配");
            if(accepted.size()!=s.models().size()*s.keys().size()&&!change.onlyAvailable()) throw fail(409,"部分搭配未通过，请明确选择仅启用可用部分");
            if(h.active()!=null) {
                var refs=references.getIfAvailable();
                for(var prior:pairs(h.active())) if(accepted.stream().noneMatch(p->p.modelRef().equals(prior.modelRef())&&p.keyRef().equals(prior.keyRef())&&p.keyVersion().equals(prior.keyVersion()))
                        &&!refs.modelReferences(new ModelVersionRef(prior.modelRef(),str(h.active()))).isEmpty())
                    throw fail(409,"新版本会移除在线应用所需搭配，请先迁移应用");
            }
            String prior=jdbc.queryForObject("SELECT admitted_pairs_json FROM sys_ai_configuration_version WHERE version_id=?",String.class,version);
            if(prior!=null) throw fail(409,"该版本已启用过，请保存新草稿后启用");
            jdbc.update("UPDATE sys_ai_configuration_version SET admitted_pairs_json=? WHERE version_id=?",encode(accepted),version);
            jdbc.update("UPDATE sys_ai_configuration SET active_version_id=?,status='ACTIVE',revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE config_id=?",version,configId);
            return get(configId);
        }));
    }
    public View disable(long configId,int revision) {
        return transactions.execute(tx->{
            var h=head(configId,true); checkRevision(h,revision);
            jdbc.update("UPDATE sys_ai_configuration SET status='DISABLED',revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE config_id=?",configId);
            return get(configId);
        });
    }
    public View archive(long configId,int revision) {
        return guarded(()->transactions.execute(tx->{
            var h=head(configId,true); checkRevision(h,revision);
            if(h.runningTest()!=null||!impact(configId).isEmpty()) throw fail(409,"配置仍被应用引用或正在测试");
            jdbc.update("UPDATE sys_ai_configuration SET status='ARCHIVED',revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE config_id=?",configId);
            return get(configId);
        }));
    }
    String proof(Snapshot snapshot,ModelSpec model,KeySpec key) {
        String material;
        try { material=resolveSecret(key); } catch(RuntimeException unavailable) { material="UNAVAILABLE"; }
        return proof(snapshot,model,key,material);
    }
    String proof(Snapshot snapshot,ModelSpec model,KeySpec key,String material) {
        try {
            // Include current material evidence so an ENV deployment change cannot retain an old test proof.
            String materialHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
            var parts=new ArrayList<Object>(List.of(snapshot.providerCode(),snapshot.endpointUrl(),snapshot.protocol(),snapshot.timeoutSeconds(),model.modelRef(),model.modelCode(),key.keyRef(),key.keyVersion(),materialHash));
            // Retain the original TEXT proof byte-for-byte; opting into JSON must invalidate it.
            if(model.jsonObject()) { parts.add("JSON_OBJECT"); parts.add(model.capabilityEvidence()); }
            String input=encode(parts);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception invalid) { throw fail(500,"搭配签名失败"); }
    }
    boolean validProof(Snapshot s,ModelSpec model,KeySpec key) {
        var rows=jdbc.query("SELECT status,checked_at FROM sys_ai_configuration_test_pair WHERE proof_hash=? AND checked_at IS NOT NULL ORDER BY pair_id DESC LIMIT 1",(rs,n)->
                "SUCCESS".equals(rs.getString(1))&&rs.getTimestamp(2).toLocalDateTime().isAfter(LocalDateTime.now().minusDays(7)),proof(s,model,key));
        return !rows.isEmpty()&&rows.get(0);
    }
    /** No secret leaves this package. ENV material is resolved at execution, never returned as evidence. */
    String resolveSecret(KeySpec key) {
        var current=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        current.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return current.execute(tx->readSecret(key));
    }
    private String readSecret(KeySpec key) {
        var rows=jdbc.query("SELECT * FROM sys_ai_configuration_key_material WHERE key_ref=? AND key_version=?",(rs,n)->{
            Long legacyId=nullableId(rs.getObject("legacy_model_id"));
            if(legacyId!=null) {
                if(legacy.get(legacyId).revision()!=rs.getInt("legacy_revision")) throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE);
                return legacyCredentials.resolve(legacyId);
            }
            if("DB".equals(rs.getString("credential_mode"))) return cipher.decrypt("ai:v04:key:"+key.keyRef(),rs.getString("credential_ciphertext"));
            String value=System.getenv(rs.getString("credential_env_name"));
            if(value==null||value.isBlank()) throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE);
            return value;
        },id(key.keyRef()),id(key.keyVersion()));
        if(rows.size()!=1) throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE);
        return rows.get(0);
    }
    private View importLegacy(long legacyId) {
        return transactions.execute(tx->{
            var old=legacy.get(legacyId);
            var existing=jdbc.query("SELECT config_id FROM sys_ai_configuration WHERE legacy_model_id=?",(rs,n)->rs.getLong(1),legacyId);
            if(!existing.isEmpty()) return get(existing.get(0));
            if(!"openrouter".equals(old.providerCode())) throw fail(400,"旧提供商尚不支持导入新配置");
            if(!List.of("https://openrouter.ai/api/v1","https://openrouter.ai/api/v1/chat/completions").contains(old.endpointUrl())) throw fail(400,"旧地址不支持新传输");
            long config=insert("INSERT INTO sys_ai_configuration(config_name,environment_name,provider_code,legacy_model_id) VALUES(?,?,?,?)","原有模型 "+old.modelCode(),"legacy",old.providerCode(),legacyId);
            long model=insert("INSERT INTO sys_ai_configuration_model(config_id,model_code) VALUES(?,?)",config,old.modelCode());
            var keys=new ArrayList<KeySpec>();
            if(old.credentialConfigured()) {
                long key=insert("INSERT INTO sys_ai_configuration_key(config_id) VALUES(?)",config);
                long material=insert("INSERT INTO sys_ai_configuration_key_material(key_ref,credential_mode,credential_env_name,legacy_model_id,legacy_revision) VALUES(?,?,?,?,?)",key,old.credentialMode(),old.credentialEnvName(),legacyId,old.revision());
                keys.add(new KeySpec(str(key),str(material),"原有Key引用",old.credentialMode(),old.credentialEnvName()));
            }
            long version=insert("INSERT INTO sys_ai_configuration_version(config_id,snapshot_json) VALUES(?,?)",config,"{}");
            var s=new Snapshot(str(version),old.providerCode(),"https://openrouter.ai/api/v1/chat/completions","OPENAI_CHAT",old.timeoutSeconds(),List.of(new ModelSpec(str(model),old.modelCode())),List.copyOf(keys));
            jdbc.update("UPDATE sys_ai_configuration_version SET snapshot_json=? WHERE version_id=?",encode(s),version);
            jdbc.update("UPDATE sys_ai_configuration SET draft_version_id=? WHERE config_id=?",version,config);
            return get(config);
        });
    }
}
