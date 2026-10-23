package com.xinghuiTec.wechat;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.TenantHelper;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import static com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX;
import static com.xinghuiTec.constants.JwtConstants.TOKEN_EXPIRATION;

@Service
public class WechatAccountService {
    private final SysUserMapper users;
    private final UserDetailsService details;
    private final RedisCacheUtils redis;
    public WechatAccountService(SysUserMapper users,UserDetailsService details,RedisCacheUtils redis) {
        this.users=users;this.details=details;this.redis=redis;
    }
    public LoginUser enabled(long id,String tenant) {
        var user=TenantHelper.ignore(()->users.selectById(id));
        if(user==null || !Integer.valueOf(1).equals(user.getStatus()) || !Objects.equals(tenant,user.getTenantId()))
            throw new WechatFailure(403,"关联产品账号不可用，请联系管理员");
        var principal=TenantHelper.ignore(()->details.loadUserByUsername(user.getMobile()));
        if(!(principal instanceof LoginUser login) || !Objects.equals(id,login.getUser().getUserId())
                || !Objects.equals(tenant,login.getTenantId()) || !login.isEnabled()) throw new WechatFailure(403,"关联产品账号不可用");
        return login;
    }
    public String login(long id,String tenant) {
        var user=enabled(id,tenant);
        String token=JwtUtil.createJWT(String.valueOf(id),tenant);user.setToken(token);
        redis.setCacheObject(ADMIN_LOGIN_PREFIX+id,user,Math.toIntExact(TimeUnit.MILLISECONDS.toSeconds(TOKEN_EXPIRATION)),TimeUnit.SECONDS);
        return token;
    }
}
