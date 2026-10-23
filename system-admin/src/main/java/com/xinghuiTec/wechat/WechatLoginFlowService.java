package com.xinghuiTec.wechat;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.domain.entity.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Server-owned flow state. A verified identity confers no product permissions by itself. */
@Service
public class WechatLoginFlowService {
    private final JdbcTemplate jdbc;
    private final WechatConfigService website;
    private final BusinessConfigService configs;
    private final WechatStateStore states;
    private final WechatIdentityClient identity;
    private final WechatAccountService accounts;
    public WechatLoginFlowService(JdbcTemplate jdbc,WechatConfigService website,BusinessConfigService configs,
                                  WechatStateStore states,WechatIdentityClient identity,WechatAccountService accounts) {
        this.jdbc=jdbc;this.website=website;this.configs=configs;this.states=states;this.identity=identity;this.accounts=accounts;
    }
    record Config(boolean enabled,String client,String callback,String secret,String scope,int revision) {
        @Override public String toString() { return "WechatLoginConfig[REDACTED]"; }
        String origin() { var uri=URI.create(callback);return uri.getScheme()+"://"+uri.getRawAuthority(); }
        boolean available() { return enabled && !client.isBlank() && !callback.isBlank() && secret!=null && !secret.isBlank(); }
    }
    record Flow(String hash,String browser,String channel,String client,int revision,String target,String status,String identity,Timestamp expires) {}
    public record Started(String flow,String authorizationUrl,int expiresInSeconds) {}
    public record Status(String status,String returnPath,int expiresInSeconds) {}
    public record Completed(String accessToken,String returnPath) {
        @Override public String toString() { return "WechatLoginCompleted[token=REDACTED]"; }
    }
    private Config config(String channel) {
        if("website".equals(channel)) {
            var c=website.get();return new Config(c.enabled(),c.clientId(),c.callbackUri(),website.currentSecret(),"snsapi_login",c.revision());
        }
        if(!"mp".equals(channel)) throw new WechatFailure(400,"微信登录场景无效");
        var c=configs.current("wechatMp").orElse(null);
        if(c==null) return new Config(false,"","",null,"snsapi_base",0);
        return new Config(c.enabled(),c.parameters().get("clientId"),c.parameters().get("callbackUri"),c.secrets().get("appSecret"),c.parameters().get("scope"),c.revision());
    }
    public Map<String,Boolean> capability() {
        return Map.of("website",config("website").available() && states.available(),"mp",config("mp").available() && states.available());
    }
    public static String safeReturn(String target) {
        if(target==null || target.isBlank()) return "/";
        try {
            var uri=URI.create(target);String path=uri.getRawPath();
            if(target.length()>2048 || target.chars().anyMatch(c->c<32 || c==127) || uri.isAbsolute() || uri.getRawAuthority()!=null
                    || uri.getRawFragment()!=null || target.contains("\\") || path==null || !path.matches("/[A-Za-z0-9/_.-]*") || path.contains("//")
                    || !uri.normalize().equals(uri) || Arrays.stream(path.split("/")).anyMatch(segment->segment.equals(".") || segment.equals(".."))
                    || path.equals("/auth") || path.startsWith("/auth/") || path.equals("/login") || path.equals("/api") || path.startsWith("/api/")) throw new IllegalArgumentException();
            return target;
        } catch(RuntimeException invalid) { throw new WechatFailure(400,"返回地址须为允许的站内业务页面"); }
    }
    static String browserHash(String binding) {
        if(!WechatStateStore.valid(binding)) throw new WechatFailure(403,"微信登录浏览器校验失败");
        return WechatStateStore.hash(binding);
    }
    private void origin(Config config,String origin) {
        if(!config.available() || !Objects.equals(config.origin(),origin)) throw new WechatFailure(403,"微信登录来源或配置无效");
    }
    @Transactional
    public Started start(String channel,String target,String binding,String origin,String ip) {
        Config c=config(channel);origin(c,origin);
        String browser=browserHash(binding),state=WechatStateStore.alphanumericState(),flow=WechatStateStore.random(),returnPath=safeReturn(target);
        states.create(state,binding,c.revision(),ip);
        jdbc.update("DELETE FROM sys_wechat_login_flow WHERE expires_at < ?",Timestamp.from(Instant.now().minusSeconds(86400)));
        jdbc.update("INSERT INTO sys_wechat_login_flow(flow_hash,state_hash,browser_hash,channel,client_id,config_revision,return_path,status,expires_at) VALUES(?,?,?,?,?,?,?,'WAITING',?)",
                WechatStateStore.hash(flow),WechatStateStore.hash(state),browser,channel,c.client(),c.revision(),returnPath,Timestamp.from(Instant.now().plusSeconds(WechatStateStore.TTL)));
        String url="https://open.weixin.qq.com/connect/"+("mp".equals(channel)?"oauth2/authorize":"qrconnect")+"?appid="+WechatIdentityClient.encode(c.client())
                +"&redirect_uri="+WechatIdentityClient.encode(c.callback())+"&response_type=code&scope="+c.scope()+"&state="+state+"#wechat_redirect";
        return new Started(flow,url,WechatStateStore.TTL);
    }
    public boolean hasState(String state) {
        return WechatStateStore.valid(state) && !jdbc.queryForList("SELECT flow_hash FROM sys_wechat_login_flow WHERE state_hash=?",String.class,WechatStateStore.hash(state)).isEmpty();
    }
    private Optional<Flow> find(String field,String hash,boolean lock) {
        return jdbc.query("SELECT flow_hash,browser_hash,channel,client_id,config_revision,return_path,status,identity_hash,expires_at FROM sys_wechat_login_flow WHERE "+field+"=?"+(lock?" FOR UPDATE":""),
                (rs,row)->new Flow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9)),hash).stream().findFirst();
    }
    private Flow owned(String flow,String binding,boolean lock) {
        if(!WechatStateStore.valid(flow)) throw new WechatFailure(410,"微信登录流程不存在或已过期");
        var found=find("flow_hash",WechatStateStore.hash(flow),lock).orElseThrow(()->new WechatFailure(410,"微信登录流程不存在或已过期"));
        if(!Objects.equals(found.browser(),browserHash(binding))) throw new WechatFailure(403,"微信登录浏览器校验失败");
        return found;
    }
    private boolean expired(Flow flow) { return !flow.expires().toInstant().isAfter(Instant.now()); }
    private boolean current(Flow flow) {
        var c=config(flow.channel());return c.available() && c.revision()==flow.revision() && c.client().equals(flow.client());
    }
    private void lockConfig(String channel) {
        if("website".equals(channel)) jdbc.queryForList("SELECT revision FROM sys_wechat_website_config WHERE id=1 FOR UPDATE");
        else jdbc.queryForList("SELECT revision FROM sys_business_config WHERE section='wechatMp' FOR UPDATE");
    }
    private void status(Flow f,String status) { jdbc.update("UPDATE sys_wechat_login_flow SET status=? WHERE flow_hash=?",status,f.hash()); }
    /** Both website and MP use this seam once. Browser and config are checked before code exchange. */
    @Transactional
    public String callback(String channel,String state,String code,String binding) {
        if(!WechatStateStore.valid(state)) return "invalid";
        var found=find("state_hash",WechatStateStore.hash(state),true);
        if(found.isEmpty()) return "invalid";
        var f=found.get();
        if(!Objects.equals(channel,f.channel()) || !Objects.equals(f.browser(),browserHash(binding))) return "invalid";
        if(!"WAITING".equals(f.status())) return "invalid";
        lockConfig(channel);
        if(expired(f) || !current(f)) { status(f,"EXPIRED");return "expired"; }
        if(!states.consume(state,binding,f.revision())) { status(f,"FAILED");return "invalid"; }
        if(code==null || code.isBlank()) { status(f,"CANCELLED");return "cancelled"; }
        // Redis state has now been irreversibly consumed even if an upstream request fails.
        try {
            var c=config(channel);String openid=identity.exchange(c.client(),c.secret(),code,c.scope());
            if(!current(f)) { status(f,"EXPIRED");return "expired"; }
            String hash=WechatStateStore.hash(channel+":"+c.client()+":"+openid);
            boolean bound=!jdbc.queryForList("SELECT user_id FROM sys_wechat_identity_binding WHERE identity_hash=?",Long.class,hash).isEmpty();
            jdbc.update("UPDATE sys_wechat_login_flow SET identity_hash=?,status=? WHERE flow_hash=?",hash,bound?"READY_LOGIN":"READY_BIND",f.hash());
            return "verified";
        } catch(RuntimeException unavailable) { status(f,"FAILED");return "failed"; }
    }
    public Status status(String flow,String binding) {
        var f=owned(flow,binding,false);
        return new Status(expired(f) || !current(f)?"EXPIRED":f.status(),f.target(),Math.max(0,(int)(f.expires().toInstant().getEpochSecond()-Instant.now().getEpochSecond())));
    }
    @Transactional
    public void cancel(String flow,String binding,String origin) {
        var f=owned(flow,binding,true);
        if(!Objects.equals(config(f.channel()).origin(),origin)) throw new WechatFailure(403,"微信登录来源无效");
        if(!"COMPLETED".equals(f.status())) status(f,"CANCELLED");
    }
    private Flow ready(String flow,String binding,String origin,String expected) {
        var f=owned(flow,binding,true);lockConfig(f.channel());origin(config(f.channel()),origin);
        if(expired(f) || !current(f) || !expected.equals(f.status())) throw new WechatFailure(410,"微信登录状态已变更，请重新发起");
        return f;
    }
    @Transactional
    public Completed complete(String flow,String binding,String origin) {
        var f=ready(flow,binding,origin,"READY_LOGIN");
        var bindingRow=jdbc.queryForMap("SELECT user_id,tenant_id FROM sys_wechat_identity_binding WHERE identity_hash=?",f.identity());
        String token=accounts.login(((Number)bindingRow.get("user_id")).longValue(),(String)bindingRow.get("tenant_id"));
        status(f,"COMPLETED");return new Completed(token,f.target());
    }
    @Transactional
    public Status bind(String flow,String binding,String origin,LoginUser principal) {
        var f=ready(flow,binding,origin,"READY_BIND");
        long user=principal.getUser().getUserId();String tenant=principal.getTenantId();
        accounts.enabled(user,tenant);
        try {
            jdbc.update("INSERT INTO sys_wechat_identity_binding(identity_hash,channel,client_id,user_id,tenant_id) VALUES(?,?,?,?,?)",f.identity(),f.channel(),f.client(),user,tenant);
        } catch(DuplicateKeyException conflict) { throw new WechatFailure(409,"微信身份或产品账号已关联，请联系管理员"); }
        status(f,"COMPLETED");return new Status("COMPLETED",f.target(),0);
    }
}
