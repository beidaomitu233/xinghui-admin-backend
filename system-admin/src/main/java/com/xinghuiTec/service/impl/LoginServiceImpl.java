package com.xinghuiTec.service.impl;

import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.domain.dto.loginDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.loginUser;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.exception.user.CaptchaException;
import com.xinghuiTec.exception.user.UserNotExistsException;
import com.xinghuiTec.service.CaptchaService;
import com.xinghuiTec.service.LoginService;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.SecurityUtils;
import com.xinghuiTec.domain.vo.UserInfoVO;
import com.xinghuiTec.domain.vo.RoleVO;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.mapper.SysRoleMenuMapper;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.service.SysRoleService;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xinghuiTec.constants.JwtConstants.TOKEN_EXPIRATION;
import static com.xinghuiTec.constants.RedisConstants.ADMIN_LOGIN_PREFIX;

@Service
public class LoginServiceImpl implements LoginService {

        @Resource
        private AuthenticationManager authenticationManager;

        @Autowired
        private CaptchaService captchaService;

        @Autowired
        private CaptchaProperties captchaProperties;

        @Resource
        private RedisCacheUtils redisCacheUtils;

        @Autowired
        private SysUserRoleMapper userRoleMapper;

        @Autowired
        private SysMenuMapper menuMapper;

        @Autowired
        private SysRoleMenuMapper roleMenuMapper;

        @Autowired
        private SysRoleService roleService;

        @Autowired
        private SysUserMapper userMapper;

        /** 手机号正则：中国大陆手机号 1xx-xxxx-xxxx */
        private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

        @Override
        public String login(loginDTO user) {
                // 前置校验（手机号格式 + 验证码）
                loginPreCheck(user);

                // 1. 认证：将手机号作为用户名传入 Spring Security 认证链
                // userDetailManageimpl.loadUserByUsername() 会忽略租户按手机号查询
                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(user.getPhone(), user.getPassword());
                Authentication authenticate = authenticationManager.authenticate(authenticationToken);

                if (Objects.isNull(authenticate)) {
                        throw new RuntimeException("手机号或密码错误");
                }

                // 2. 从认证结果获取用户信息（tenantId 已在 userDetailManageimpl 中从用户实体设置）
                loginUser loginUser = (loginUser) authenticate.getPrincipal();
                Long userId = loginUser.getUser().getUserId();
                String tenantId = loginUser.getTenantId();

                // 3. 生成 JWT（包含 userId 和 tenantId）
                String jwt = JwtUtil.createJWT(String.valueOf(userId), tenantId);

                // 4. 存储 loginUser 到 Redis
                Integer days = Math.toIntExact(TimeUnit.MILLISECONDS.toDays(TOKEN_EXPIRATION));
                redisCacheUtils.setCacheObject(ADMIN_LOGIN_PREFIX + userId, loginUser, days, TimeUnit.DAYS);

                return jwt;
        }

        @Override
        public String logout() {
                Long userId = SecurityUtils.getUser().getUserId();
                boolean b = redisCacheUtils.deleteObject(ADMIN_LOGIN_PREFIX + userId);
                if (!b) {
                        throw new RuntimeException("登出失败");
                }
                return "登出成功";
        }

        @Override
        public UserInfoVO getUserInfo() {
                SysUser user = SecurityUtils.getUser();
                return getUserInfo(user.getUserId());
        }

        @Override
        public UserInfoVO getUserInfo(Long userId) {
                if (userId == null) {
                        return getUserInfo();
                }

                String cacheKey = RedisConstants.USER_INFO_PREFIX + userId;
                UserInfoVO cachedUserInfo = redisCacheUtils.getCacheObject(cacheKey);

                if (cachedUserInfo != null) {
                        return cachedUserInfo;
                }

                LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(SysUserRole::getUserId, userId);
                List<SysUserRole> userRoles = userRoleMapper.selectList(wrapper);

                List<RoleVO> roles = new ArrayList<>();
                List<String> permissions = new ArrayList<>();

                if (!userRoles.isEmpty()) {
                        List<Long> roleIds = userRoles.stream()
                                        .map(SysUserRole::getRoleId)
                                        .collect(Collectors.toList());

                        if (!roleIds.isEmpty()) {
                                List<SysRole> sysRoles = roleService.listByIds(roleIds);
                                for (SysRole role : sysRoles) {
                                        roles.add(new RoleVO(role.getRoleId(), role.getRoleName(), role.getRoleKey()));
                                }
                        }

                        if (!roleIds.isEmpty()) {
                                List<String> perms = menuMapper.selectPermsByRoleIds(roleIds);
                                if (perms != null) {
                                        permissions.addAll(perms);
                                }
                        }
                }

                permissions = permissions.stream()
                                .filter(p -> p != null && !p.isEmpty())
                                .distinct()
                                .collect(Collectors.toList());

                SysUser user = userMapper.selectById(userId);
                if (user == null) {
                        throw new RuntimeException("用户不存在");
                }

                UserInfoVO userInfoVO = new UserInfoVO(user, roles, permissions);

                redisCacheUtils.setCacheObject(cacheKey, userInfoVO, RedisConstants.USER_INFO_TTL_SEC,
                                TimeUnit.SECONDS);

                return userInfoVO;
        }

        @Override
        public List<SysMenuVO> getUserRouter() {
                SysUser user = SecurityUtils.getUser();
                return getUserRouter(user.getUserId());
        }

        @Override
        public List<SysMenuVO> getUserRouter(Long userId) {
                if (userId == null) {
                        return getUserRouter();
                }

                String routerCacheKey = RedisConstants.USER_ROUTER_PREFIX + userId;
                List<SysMenuVO> cachedRouter = redisCacheUtils.getCacheList(routerCacheKey);

                if (cachedRouter != null) {
                        return cachedRouter;
                }

                LambdaQueryWrapper<SysUserRole> userRoleWrapper = new LambdaQueryWrapper<>();
                userRoleWrapper.eq(SysUserRole::getUserId, userId);
                List<SysUserRole> userRoles = userRoleMapper.selectList(userRoleWrapper);

                if (userRoles.isEmpty()) {
                        return Collections.emptyList();
                }

                List<Long> roleIds = userRoles.stream()
                                .map(SysUserRole::getRoleId)
                                .collect(Collectors.toList());

                List<SysMenu> menus = menuMapper.selectMenuByUserId(userId);
                if (menus == null || menus.isEmpty()) {
                        return Collections.emptyList();
                }

                List<SysMenu> filteredMenus = menus.stream()
                                .filter(menu -> !"F".equals(menu.getMenuType()))
                                .filter(menu -> "0".equals(menu.getStatus()))
                                .collect(Collectors.toList());

                List<SysMenuVO> menuVOs = cn.hutool.core.bean.BeanUtil.copyToList(filteredMenus, SysMenuVO.class);

                List<SysMenuVO> menuTree = buildMenuTree(menuVOs);

                redisCacheUtils.setCacheList(routerCacheKey, menuTree, RedisConstants.USER_ROUTER_TTL_SEC,
                                TimeUnit.SECONDS);

                return menuTree;
        }

        /**
         * 登录前置校验（手机号格式 + 验证码）
         */
        private void loginPreCheck(loginDTO loginDTO) {
                String phone = loginDTO.getPhone();
                String password = loginDTO.getPassword();
                String code = loginDTO.getCode();
                String uuid = loginDTO.getUuid();

                // 手机号或密码为空
                if (!StringUtils.hasText(phone) || !StringUtils.hasText(password)) {
                        throw new UserNotExistsException();
                }

                // 手机号格式校验
                if (!PHONE_PATTERN.matcher(phone).matches()) {
                        throw new RuntimeException("手机号格式不正确，请输入11位中国大陆手机号");
                }

                // 验证码校验
                if (captchaProperties.getEnabled()) {
                        if (!StringUtils.hasText(code) || !StringUtils.hasText(uuid)) {
                                throw new CaptchaException();
                        }
                        boolean isValid = captchaService.validateCaptcha(uuid, code);
                        if (!isValid) {
                                throw new CaptchaException();
                        }
                }
        }

        private List<SysMenuVO> buildMenuTree(List<SysMenuVO> menus) {
                return com.xinghuiTec.utils.TreeUtils.buildTree(
                                menus,
                                SysMenuVO::getMenuId,
                                SysMenuVO::getParentId,
                                SysMenuVO::setChildren,
                                SysMenuVO::getOrderNum
                );
        }

}
