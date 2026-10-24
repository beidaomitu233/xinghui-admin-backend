package com.xinghuiTec.service;

import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.TenantHelper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.xinghuiTec.constants.JwtConstants.TOKEN_EXPIRATION;
import static com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX;

/** Existing accounts only. OTPs never appear in responses or controller logs. */
@Service
public class SmsLoginService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "global:{sms_login}:";
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
        if not redis.call('SET',KEYS[1],'1','EX',60,'NX') then return 0 end
        redis.call('HSET',KEYS[2],'phone',ARGV[1],'digest',ARGV[2],'ready','0','attempts','0')
        redis.call('EXPIRE',KEYS[2],300)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
        if redis.call('HGET',KEYS[1],'phone')~=ARGV[1] or redis.call('HGET',KEYS[1],'ready')~='1' then return 0 end
        local attempts=redis.call('HINCRBY',KEYS[1],'attempts',1)
        if attempts>5 then redis.call('DEL',KEYS[1]); return 0 end
        if redis.call('HGET',KEYS[1],'digest')~=ARGV[2] then
          if attempts>=5 then redis.call('DEL',KEYS[1]) end
          return 0
        end
        redis.call('DEL',KEYS[1]); return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVATE = new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==0 then return 0 end
        redis.call('HSET',KEYS[1],'ready','1'); return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final RuntimeSmsService sms;
    private final BusinessConfigSource configs;
    private final UserDetailsService users;
    private final RedisCacheUtils sessions;
    private final CaptchaService captchas;
    private final CaptchaProperties captchaProperties;
    private final PublishedConfigReader published;

    public SmsLoginService(StringRedisTemplate redis, RuntimeSmsService sms, BusinessConfigSource configs,
                           UserDetailsService users, RedisCacheUtils sessions, CaptchaService captchas,
                           CaptchaProperties captchaProperties, PublishedConfigReader published) {
        this.redis=redis; this.sms=sms; this.configs=configs; this.users=users;
        this.sessions=sessions; this.captchas=captchas; this.captchaProperties=captchaProperties; this.published=published;
    }
    public boolean available() {
        try {
            var snapshot=configs.current("sms").orElse(null);
            if(snapshot==null || !snapshot.enabled()) return false;
            sms.validate(snapshot);
            return true;
        } catch(RuntimeException unavailable) { return false; }
    }
    public record Challenge(String requestId,int expiresInSeconds,int retryAfterSeconds) {}
    private static void phone(String phone) {
        if(phone==null || !phone.matches("1[3-9][0-9]{9}")) throw new ServiceException("请输入正确的手机号");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public Challenge send(String phone,String uuid,String captcha) {
        phone(phone);
        if(!available()) throw new ServiceException("短信登录暂未配置，请使用密码登录");
        if(published.isCaptchaEnabled(Boolean.TRUE.equals(captchaProperties.getEnabled())) && !captchas.validateCaptcha(uuid,captcha))
            throw new ServiceException("图片验证码错误或已过期");
        String id=UUID.randomUUID().toString(), code=String.format("%06d",RANDOM.nextInt(1_000_000));
        String key=PREFIX+"challenge:"+id;
        if(!Long.valueOf(1).equals(redis.execute(CREATE,List.of(PREFIX+"phone:"+digest(phone),key),phone,digest(id+":"+code))))
            throw new ServiceException("请稍后再获取验证码");
        try {
            // Identical response for absent/disabled accounts; no automatic registration.
            var details=TenantHelper.ignore(()->users.loadUserByUsername(phone));
            if(details instanceof LoginUser login && login.isEnabled()) {
                var variables=new LinkedHashMap<String,String>(); variables.put("code",code);
                var result=sms.send(phone,variables);
                if(result==null || !result.isSuccess()) throw new ServiceException("短信发送失败，请稍后重试");
                if(!Long.valueOf(1).equals(redis.execute(ACTIVATE,List.of(key))))
                    throw new ServiceException("验证码已过期，请重新获取");
            }
        } catch(UsernameNotFoundException unknown) {
            // Do not reveal registration state in the response.
        } catch(RuntimeException failed) {
            redis.delete(key);
            throw new ServiceException("短信发送失败，请稍后重试");
        }
        return new Challenge(id,300,60);
    }
    public String login(String phone,String requestId,String code) {
        phone(phone);
        if(!available()) throw new ServiceException("短信登录暂不可用，请使用密码登录");
        if(requestId==null || !requestId.matches("[a-f0-9-]{36}") || code==null || !code.matches("[0-9]{6}"))
            throw new ServiceException("验证码错误或已过期");
        if(!Long.valueOf(1).equals(redis.execute(CONSUME,List.of(PREFIX+"challenge:"+requestId),phone,digest(requestId+":"+code))))
            throw new ServiceException("验证码错误或已过期");
        try {
            var details=TenantHelper.ignore(()->users.loadUserByUsername(phone));
            if(!(details instanceof LoginUser login) || !login.isEnabled()) throw new ServiceException("账号不可用，请联系管理员");
            var user=login.getUser();
            String jwt=JwtUtil.createJWT(String.valueOf(user.getUserId()),login.getTenantId());
            login.setToken(jwt);
            sessions.setCacheObject(ADMIN_LOGIN_PREFIX+user.getUserId(),login,
                    Math.toIntExact(TimeUnit.MILLISECONDS.toSeconds(TOKEN_EXPIRATION)),TimeUnit.SECONDS);
            return jwt;
        } catch(UsernameNotFoundException unknown) { throw new ServiceException("账号不可用，请联系管理员"); }
    }
}
