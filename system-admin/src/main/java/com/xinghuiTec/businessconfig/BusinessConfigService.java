package com.xinghuiTec.businessconfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.oss.config.OssRuntimeSettings;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.mail.config.RuntimeMailService;
import com.xinghuiTec.wechat.WechatConfigService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class BusinessConfigService implements BusinessConfigSource {
    private static final Map<String,Set<String>> PARAMETERS=Map.of(
            "wechat",Set.of("clientId","callbackUri","credentialMode","secretEnvName"),
            "wechatMp",Set.of("clientId","callbackUri","scope"),
            "oss",Set.of("provider","endpoint","bucket","basePath"),
            "sms",Set.of("provider","signature","templateId","region","sdkAppId"),
            "mail",Set.of("provider","host","port","security","user","from"));
    private static final Map<String,Set<String>> SECRET_FIELDS=Map.of(
            "wechat",Set.of("appSecret"),"wechatMp",Set.of("appSecret"),"oss",Set.of("accessKey","secretKey"),"sms",Set.of("accessKeyId","accessKeySecret"),"mail",Set.of("password"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final WechatConfigService wechat;
    private final OssRuntimeSettings oss;
    private final RuntimeSmsService sms;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.xinghuiTec.mail.config.MailProperties deploymentMail;
    public BusinessConfigService(JdbcTemplate jdbc,ObjectMapper json,SecretCipher cipher,WechatConfigService wechat,OssRuntimeSettings oss,RuntimeSmsService sms) {
        this.jdbc=jdbc;this.json=json;this.cipher=cipher;this.wechat=wechat;this.oss=oss;this.sms=sms;
    }
    public record Change(Boolean enabled,Map<String,String> parameters,
                         @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) Map<String,String> secrets,List<String> clearSecrets,Integer revision) {
        @Override public String toString() { return "BusinessConfigChange[secrets=REDACTED]"; }
        @JsonAnySetter public void unknown(String name,Object ignored) { throw new IllegalArgumentException("配置字段无效"); }
    }
    public record View(String section,boolean enabled,Map<String,String> parameters,Map<String,Boolean> secretsConfigured,
                       int revision,String updateTime,String source,String effectiveState,boolean encryptionReady) {}
    public static class Failure extends RuntimeException {
        public final int status;
        public Failure(int status,String message) { super(message);this.status=status; }
    }
    private record Stored(boolean enabled,String parameters,String secrets,int revision,String updateTime) {
        @Override public String toString() { return "StoredBusinessConfig[secrets=REDACTED]"; }
    }
    private void section(String section) { if(!PARAMETERS.containsKey(section)) throw new Failure(400,"配置分区不存在"); }
    public List<View> all() { return List.of(get("wechat"),get("wechatMp"),get("sms"),get("mail"),get("oss")); }
    public View get(String section) {
        section(section);
        if(section.equals("wechat")) return wechatView();
        var stored=stored(section,false);
        if(stored.isEmpty()) {
            var defaults=new LinkedHashMap<String,String>();
            PARAMETERS.get(section).forEach(name->defaults.put(name,""));
            defaults.put("provider",section.equals("oss")?"minio":"alibaba");
            if(section.equals("sms")) defaults.put("region","cn-hangzhou");
            if(section.equals("mail")) defaults.putAll(Map.of("provider","qq","host","smtp.qq.com","port","465","security","SSL"));
            if(section.equals("wechatMp")) { defaults.remove("provider");defaults.put("scope","snsapi_base"); }
            boolean deployment=section.equals("oss") && oss.deploymentEnabled() || section.equals("mail") && deploymentMail!=null && Boolean.TRUE.equals(deploymentMail.getEnabled());
            if(deployment && section.equals("mail")) defaults.putAll(Map.of("provider","smtp","host",deploymentMail.getHost(),"port",String.valueOf(deploymentMail.getPort()),
                    "user",deploymentMail.getUser(),"from",deploymentMail.getFrom(),"security",Boolean.TRUE.equals(deploymentMail.getSslEnable())?"SSL":"STARTTLS"));
            return new View(section,deployment,defaults,flags(section,Map.of()),0,null,"DEPLOYMENT",deployment?"DEPLOYMENT_ACTIVE":"DISABLED",cipher.ready());
        }
        var value=stored.get();
        Map<String,String> secrets=secretMap(section,value.secrets());
        return new View(section,value.enabled(),map(value.parameters()),flags(section,secrets),value.revision(),value.updateTime(),"DATABASE",
                !value.enabled()?"DISABLED":Set.of("sms","mail").contains(section)?"ADAPTED_SEND_UNVERIFIED":section.equals("wechatMp")?"AUTHORIZATION_READY_IDENTITY_UNVERIFIED":"ACTIVE",cipher.ready());
    }
    private View wechatView() {
        return jdbc.queryForObject("SELECT enabled,client_id,secret_env_name,callback_uri,credential_mode,secret_ciphertext,revision,update_time FROM sys_wechat_website_config WHERE id=1",
                (rs,row)-> {
                    String mode=rs.getString("credential_mode"),encrypted=rs.getString("secret_ciphertext");
                    Map<String,String> parameters=Map.of("clientId",rs.getString("client_id"),"secretEnvName",rs.getString("secret_env_name"),"callbackUri",WechatConfigService.canonicalCallback(rs.getString("callback_uri")),"credentialMode",mode);
                    boolean configured="DB".equals(mode)?encrypted!=null:WechatConfigService.secret(rs.getString("secret_env_name"))!=null;
                    if(encrypted!=null && "DB".equals(mode)) cipher.decrypt("business:wechat",encrypted);
                    boolean enabled=rs.getBoolean("enabled");
                    return new View("wechat",enabled,parameters,Map.of("appSecret",configured),rs.getInt("revision"),String.valueOf(rs.getTimestamp("update_time")),"DATABASE",
                            !enabled?"DISABLED":configured?"AUTHORIZATION_READY_IDENTITY_UNVERIFIED":"SECRET_UNAVAILABLE",cipher.ready());
                });
    }
    private Map<String,Boolean> flags(String section,Map<String,String> secrets) {
        var flags=new LinkedHashMap<String,Boolean>();
        SECRET_FIELDS.get(section).forEach(name->flags.put(name,secrets.get(name)!=null && !secrets.get(name).isBlank()));
        return flags;
    }
    private Optional<Stored> stored(String section,boolean lock) {
        return jdbc.query("SELECT enabled,parameters_json,secrets_ciphertext,revision,update_time FROM sys_business_config WHERE section=?"+(lock?" FOR UPDATE":""),
                (rs,row)->new Stored(rs.getBoolean(1),rs.getString(2),rs.getString(3),rs.getInt(4),String.valueOf(rs.getTimestamp(5))),section).stream().findFirst();
    }
    private Map<String,String> map(String encoded) {
        try { return json.readValue(encoded,new TypeReference<LinkedHashMap<String,String>>() {}); }
        catch(Exception invalid) { throw new Failure(503,"配置内容不可用，请重新保存"); }
    }
    private String encode(Map<String,String> values) {
        try { return json.writeValueAsString(values); }
        catch(Exception invalid) { throw new Failure(400,"配置内容无效"); }
    }
    private Map<String,String> secretMap(String section,String encrypted) {
        return encrypted==null?new LinkedHashMap<>():map(cipher.decrypt("business:"+section,encrypted));
    }
    private Map<String,String> parameters(String section,Map<String,String> requested) {
        if(requested==null || !PARAMETERS.get(section).containsAll(requested.keySet())) throw new Failure(400,"配置字段无效");
        var values=new LinkedHashMap<String,String>();
        for(var name:PARAMETERS.get(section)) {
            String value=requested.getOrDefault(name,"");
            if(value==null || value.length()>2048 || value.chars().anyMatch(c->c<32 || c==127)) throw new Failure(400,"配置字段格式无效");
            values.put(name,value.trim());
        }
        if(section.equals("oss")) oss.validateParameters(values);
        if(section.equals("sms")) {
            if(!List.of("alibaba","tencent").contains(values.get("provider"))) throw new Failure(400,"短信供应商无效");
            if(values.get("signature").length()>128 || values.get("templateId").length()>128
                    || !values.get("region").matches("[a-z0-9-]{0,64}") || !values.get("sdkAppId").matches("[0-9]{0,64}")) throw new Failure(400,"短信配置字段格式无效");
            // Keep vendor-specific parameters out of the other vendor's runtime snapshot.
            if("alibaba".equals(values.get("provider"))) values.put("sdkAppId","");
            else values.put("region","");
        }
        if(section.equals("mail")) RuntimeMailService.validateParameters(values);
        if(section.equals("wechatMp")) {
            if(!values.get("clientId").isEmpty() && !values.get("clientId").matches("[A-Za-z0-9_-]{1,64}")) throw new Failure(400,"服务号 AppID 格式无效");
            if(!Set.of("snsapi_base","snsapi_userinfo").contains(values.get("scope"))) throw new Failure(400,"服务号授权范围无效");
            if(!values.get("callbackUri").isEmpty()) wechat.validateMpCallback(values.get("callbackUri"));
        }
        return values;
    }
    private Map<String,String> mergeSecrets(String section,Change change,Map<String,String> existing) {
        var incoming=change.secrets()==null?Map.<String,String>of():change.secrets();
        var clear=change.clearSecrets()==null?List.<String>of():change.clearSecrets();
        if(!SECRET_FIELDS.get(section).containsAll(incoming.keySet()) || !SECRET_FIELDS.get(section).containsAll(clear)) throw new Failure(400,"密钥字段无效");
        var values=new LinkedHashMap<>(existing);
        for(var name:clear) {
            if(incoming.get(name)!=null && !incoming.get(name).isBlank()) throw new Failure(400,"清除密钥时不能同时填写新值");
            values.remove(name);
        }
        incoming.forEach((name,value)-> {
            if(value==null || value.isBlank()) return;
            if(value.length()>8192 || value.chars().anyMatch(c->c<32 || c==127)) throw new Failure(400,"密钥格式无效");
            values.put(name,value.trim());
        });
        return values;
    }
    @Transactional
    public View save(String section,Change change) {
        section(section);
        if(change==null || change.enabled()==null || change.revision()==null || change.revision()<0) throw new Failure(400,"配置字段无效");
        var parameters=parameters(section,change.parameters());
        if(section.equals("wechat")) {
            var previous=jdbc.queryForMap("SELECT secret_ciphertext,client_id,credential_mode FROM sys_wechat_website_config WHERE id=1 FOR UPDATE");
            String existing=(String)previous.get("secret_ciphertext");
            if(!Objects.equals(previous.get("client_id"),parameters.get("clientId")) || !Objects.equals(previous.get("credential_mode"),parameters.get("credentialMode"))) existing=null;
            var incoming=change.secrets()==null?Map.<String,String>of():change.secrets();
            var clear=change.clearSecrets()==null?List.<String>of():change.clearSecrets();
            String replacement=incoming.get("appSecret");
            mergeSecrets(section,change,Map.of()); // Validate fields and explicit clear conflicts.
            if("ENV".equals(parameters.get("credentialMode")) && replacement!=null && !replacement.isBlank()) throw new Failure(400,"请先选择数据库密钥来源");
            String encrypted=clear.contains("appSecret")?null:replacement!=null && !replacement.isBlank()?cipher.encrypt("business:wechat",replacement.trim()):existing;
            if("DB".equals(parameters.get("credentialMode")) && encrypted!=null) cipher.decrypt("business:wechat",encrypted);
            wechat.saveUnified(change.enabled(),parameters.get("clientId"),parameters.get("secretEnvName"),parameters.get("callbackUri"),parameters.get("credentialMode"),encrypted,change.revision());
            return get(section);
        }
        var existing=stored(section,true);
        if(existing.map(Stored::revision).orElse(0)!=change.revision()) throw new Failure(409,"配置已更新，请刷新后重试");
        boolean replacesAll=SECRET_FIELDS.get(section).stream().allMatch(name->
                change.clearSecrets()!=null && change.clearSecrets().contains(name)
                || change.secrets()!=null && change.secrets().get(name)!=null && !change.secrets().get(name).isBlank());
        boolean providerChanged=existing.isPresent() && (!Objects.equals(map(existing.get().parameters()).get("provider"),parameters.get("provider"))
                || section.equals("wechatMp") && !Objects.equals(map(existing.get().parameters()).get("clientId"),parameters.get("clientId"))
                || section.equals("mail") && List.of("host","user").stream().anyMatch(key->!Objects.equals(map(existing.get().parameters()).get(key),parameters.get(key))));
        var secrets=mergeSecrets(section,change,existing.isEmpty() || replacesAll || providerChanged?Map.of():secretMap(section,existing.get().secrets()));
        int revision=change.revision()+1;
        if(revision<=0) throw new Failure(400,"配置版本无效");
        var snapshot=new Snapshot(section,change.enabled(),revision,parameters,secrets);
        if(section.equals("oss")) oss.validate(snapshot);
        else if(section.equals("sms")) sms.validate(snapshot);
        else if(section.equals("mail")) RuntimeMailService.validate(snapshot);
        else if(change.enabled() && (parameters.get("clientId").isBlank() || parameters.get("callbackUri").isBlank() || secrets.getOrDefault("appSecret","").isBlank()))
            throw new Failure(400,"启用前请补齐服务号配置及密钥");
        String encoded=encode(parameters),encrypted=secrets.isEmpty()?null:cipher.encrypt("business:"+section,encode(secrets));
        try {
            if(existing.isEmpty()) jdbc.update("INSERT INTO sys_business_config(section,enabled,parameters_json,secrets_ciphertext,revision) VALUES(?,?,?,?,?)",section,change.enabled(),encoded,encrypted,revision);
            else if(jdbc.update("UPDATE sys_business_config SET enabled=?,parameters_json=?,secrets_ciphertext=?,revision=?,update_time=CURRENT_TIMESTAMP WHERE section=? AND revision=?",
                    change.enabled(),encoded,encrypted,revision,section,change.revision())!=1) throw new Failure(409,"配置已更新，请刷新后重试");
            jdbc.update("INSERT INTO sys_business_config_history(section,revision,enabled,parameters_json,secrets_ciphertext) VALUES(?,?,?,?,?)",section,revision,change.enabled(),encoded,encrypted);
        } catch(DuplicateKeyException conflict) { throw new Failure(409,"配置已更新，请刷新后重试"); }
        return get(section);
    }
    @Override public Optional<Snapshot> current(String section) {
        section(section);
        if(section.equals("wechat")) return Optional.empty();
        return stored(section,false).map(value->new Snapshot(section,value.enabled(),value.revision(),map(value.parameters()),secretMap(section,value.secrets())));
    }
    @Override public Optional<Snapshot> historical(String section,int revision) {
        if(!"oss".equals(section) || revision<1) return Optional.empty();
        return jdbc.query("SELECT enabled,parameters_json,secrets_ciphertext FROM sys_business_config_history WHERE section=? AND revision=?",
                (rs,row)->new Snapshot(section,rs.getBoolean(1),revision,map(rs.getString(2)),secretMap(section,rs.getString(3))),section,revision).stream().findFirst();
    }
}
