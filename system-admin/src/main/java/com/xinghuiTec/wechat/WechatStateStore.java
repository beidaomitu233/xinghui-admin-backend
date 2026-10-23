package com.xinghuiTec.wechat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Component
public class WechatStateStore {
    public static final int TTL = 300;
    static final String PREFIX = "global:{wechat_auth}:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            redis.replicate_commands()
            local t=redis.call('TIME'); local now=tonumber(t[1])
            redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now)
            if tonumber(redis.call('GET',KEYS[2]) or '0')>=3 or
               tonumber(redis.call('GET',KEYS[3]) or '0')>=10 or redis.call('ZCARD',KEYS[4])>=1000 then return 0 end
            if not redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2],'NX') then return 0 end
            if redis.call('INCR',KEYS[2])==1 then redis.call('EXPIRE',KEYS[2],ARGV[2]) end
            if redis.call('INCR',KEYS[3])==1 then redis.call('EXPIRE',KEYS[3],60) end
            redis.call('ZADD',KEYS[4],now+tonumber(ARGV[2]),KEYS[1]); redis.call('EXPIRE',KEYS[4],ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
            redis.call('DEL',KEYS[1]); redis.call('ZREM',KEYS[2],KEYS[1]); return 1
            """, Long.class);
    public WechatStateStore(StringRedisTemplate redis) { this.redis = redis; }
    public boolean available() {
        try { return "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping())); }
        catch (RuntimeException e) { return false; }
    }
    public void create(String state, String binding, int revision, String address) {
        try {
            Long result = redis.execute(CREATE, List.of(PREFIX + "state:" + hash(state),
                    PREFIX + "browser:" + hash(binding), PREFIX + "ip:" + hash(address), PREFIX + "pending"),
                    hash(binding) + ":" + revision, String.valueOf(TTL));
            if (!Long.valueOf(1).equals(result)) throw new WechatFailure(429, "授权请求过于频繁，请稍后重试");
        } catch (WechatFailure e) { throw e; }
        catch (RuntimeException e) { throw new WechatFailure(503, "微信授权暂时不可用"); }
    }
    /** WX-02 must call this seam once before identity exchange. It confers no identity. */
    public boolean consume(String state, String binding, int revision) {
        if (!valid(state) || !valid(binding)) return false;
        try {
            return Long.valueOf(1).equals(redis.execute(CONSUME,
                    List.of(PREFIX + "state:" + hash(state), PREFIX + "pending"), hash(binding) + ":" + revision));
        } catch (RuntimeException e) { throw new WechatFailure(503, "微信授权暂时不可用"); }
    }
    public static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    /** Service-account OAuth documents restrict state to ASCII letters and digits. */
    public static String alphanumericState() {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        var result=new StringBuilder(43);
        for(int i=0;i<43;i++) result.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return result.toString();
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
