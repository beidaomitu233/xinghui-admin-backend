package com.xinghuiTec.wechat;

import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.config.SecretCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.function.Function;

@Service
public class WechatConfigService {
    private final JdbcTemplate jdbc;
    private final AiModelConfigRules rules;
    private final boolean localHttp;
    private final SecretCipher cipher;
    public WechatConfigService(JdbcTemplate jdbc,AiModelConfigRules rules,boolean localHttp) { this(jdbc,rules,localHttp,new SecretCipher()); }
    @Autowired
    public WechatConfigService(JdbcTemplate jdbc, AiModelConfigRules rules,
                               @Value("${wechat.allow-local-http:false}") boolean localHttp,SecretCipher cipher) {
        this.jdbc = jdbc; this.rules = rules; this.localHttp = localHttp;this.cipher=cipher;
    }
    public record Config(boolean enabled, String clientId, String secretEnvName,
                         String callbackUri, int revision, boolean secretAvailable) {}
    public record Change(Boolean enabled, String clientId, String secretEnvName,
                         String callbackUri, Integer revision) {}
    public Config get() { return read(false); }
    private Config read(boolean lock) {
        return jdbc.queryForObject("SELECT enabled,client_id,secret_env_name,callback_uri,revision,credential_mode,secret_ciphertext "
                + "FROM sys_wechat_website_config WHERE id=1" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Config(rs.getBoolean(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), resolve(rs.getString(6),rs.getString(7),rs.getString(3)) != null));
    }
    // A DB row lock orders config updates against both flow creation and consumption across instances.
    @Transactional
    public <T> T withCurrent(Function<Config, T> operation) { return operation.apply(read(true)); }

    @Transactional
    public Config save(Change change) {
        if (change == null || change.enabled() == null || change.revision() == null || change.revision() < 0)
            throw new WechatFailure(400, "配置字段无效");
        String client = clean(change.clientId(), 64), env = clean(change.secretEnvName(), 128);
        String callback = clean(change.callbackUri(), 2048);
        if (!client.isEmpty() && !client.matches("[A-Za-z0-9_-]{1,64}"))
            throw new WechatFailure(400, "网站应用标识格式无效");
        try { rules.credentialEnvName(env); }
        catch (RuntimeException e) { throw new WechatFailure(400, "仅可填写密钥环境变量名称"); }
        if (!callback.isEmpty()) validateCallback(callback);
        if (change.enabled() && (client.isEmpty() || env.isEmpty() || callback.isEmpty()))
            throw new WechatFailure(400, "启用前请补齐网站应用配置");
        int count = jdbc.update("UPDATE sys_wechat_website_config SET enabled=?,client_id=?,"
                + "secret_env_name=?,callback_uri=?,revision=revision+1 WHERE id=1 AND revision=?",
                change.enabled(), client, env, callback, change.revision());
        if (count != 1) throw new WechatFailure(409, "配置已更新，请刷新后重试");
        return get();
    }
    void validateCallback(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
            boolean https = "https".equals(uri.getScheme()) && host != null
                    && host.matches("(?i)[a-z0-9-]+(?:\\.[a-z0-9-]+)+") && !host.matches("[0-9.]+")
                    && !loopback;
            if (!(https || localHttp && loopback && "http".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("/auth/wechat/callback".equals(uri.getRawPath())
                    || "/api/auth/wechat/callback".equals(uri.getRawPath())) || uri.getPort() == 0)
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new WechatFailure(400, "回调须为固定HTTPS域名及微信回调路径"); }
    }
    public static String origin(Config config) {
        URI uri = URI.create(config.callbackUri());
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
    public static String secret(String name) {
        if (name == null || name.isBlank()) return null;
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
    public String currentSecret() {
        return jdbc.queryForObject("SELECT credential_mode,secret_ciphertext,secret_env_name FROM sys_wechat_website_config WHERE id=1",
                (rs,row)->resolve(rs.getString(1),rs.getString(2),rs.getString(3)));
    }
    private String resolve(String mode,String encrypted,String env) {
        if("ENV".equals(mode)) return secret(env);
        if(!"DB".equals(mode)) throw new WechatFailure(503,"微信凭据来源无效");
        return encrypted==null?null:cipher.decrypt("business:wechat",encrypted);
    }
    @Transactional
    public void saveUnified(boolean enabled,String client,String env,String callback,String mode,String encrypted,int revision) {
        client=clean(client,64);env=clean(env,128);callback=clean(callback,2048);
        if(!client.isEmpty() && !client.matches("[A-Za-z0-9_-]{1,64}")) throw new WechatFailure(400,"网站应用标识格式无效");
        if(!"ENV".equals(mode) && !"DB".equals(mode)) throw new WechatFailure(400,"凭据来源无效");
        rules.credentialEnvName(env);
        if(!callback.isEmpty()) validateCallback(callback);
        if(enabled && (client.isEmpty() || callback.isEmpty() || resolve(mode,encrypted,env)==null))
            throw new WechatFailure(400,"启用前请补齐网站应用配置及可用密钥");
        if(jdbc.update("UPDATE sys_wechat_website_config SET enabled=?,client_id=?,secret_env_name=?,callback_uri=?,credential_mode=?,secret_ciphertext=?,revision=revision+1 WHERE id=1 AND revision=?",
                enabled,client,env,callback,mode,encrypted,revision)!=1) throw new WechatFailure(409,"配置已更新，请刷新后重试");
    }
    private String clean(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw new WechatFailure(400, "配置字段过长");
        return result;
    }
}
