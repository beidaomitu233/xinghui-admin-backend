package com.xinghuiTec.service.impl;

import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.constants.redisConstants;
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
import java.util.stream.Collectors;

import static com.xinghuiTec.constants.jwtConstans.TOKEN_EXPIRATION;
import static com.xinghuiTec.constants.redisConstants.ADMIN_LOGIN_PREFIX;

@Service
public class LoginServiceImpl implements LoginService {
        /*
         * 基于spring security实现用户登入
         * 1.通过authenticationManager.authenticate()方法进行用户认证
         * 2.生成并返回jwt令牌
         *
         * authenticate()方法实际上就是会调用我们重写的userDetailManageimpl.loadUserByUsername()方法
         *
         */

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

        @Override
        public String login(loginDTO user) {
                // 前置校验(包括验证码验证)
                loginPreCheck(user);

                // 1.通过authenticationManager.authenticate()方法进行用户认证
                // Authentication实现类:UsernamePasswordAuthenticationToken,绑定我们的用户账户和密码
                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                                user.getUsername(), user.getPassword());
                // authenticate()从authenticationToken中获取到用户账户和密码,然后传入并调用loadUserByUsername()方法
                Authentication authenticate = authenticationManager.authenticate(authenticationToken);

                // 如果没有匹配到用户,抛出异常
                if (Objects.isNull(authenticate)) {
                        throw new RuntimeException("账号或密码错误");
                }

                // 从authenticate中获取用户信息,就是loadUserByUsername()方法的返回值
                loginUser loginUser = (loginUser) authenticate.getPrincipal();
                String userId = loginUser.getUser().getUserId();

                // 2.生成并返回JWT (JWT只包含userId,不包含完整用户信息)
                String jwt = JwtUtil.createJWT(userId);

                // 将毫秒转化为天
                Integer days = Math.toIntExact(TimeUnit.MILLISECONDS.toDays(TOKEN_EXPIRATION));

                // 3.存储 loginUser 对象到 Redis (而不是存储JWT)
                // 这样 loginFilter 可以直接从 Redis 获取完整的用户信息,无需查询数据库
                redisCacheUtils.setCacheObject(ADMIN_LOGIN_PREFIX + userId, loginUser, days, TimeUnit.DAYS);

                return jwt;
        }

        @Override
        public String logout() {
                // 从Redis中清除登录信息
                // 1.从auth中获取当前用户id
                String userId = SecurityUtils.getUser().getUserId();
                boolean b = redisCacheUtils.deleteObject(userId);
                if (!b) {
                        throw new RuntimeException("登出失败");
                }
                return "登出成功";
        }

        @Override
        public UserInfoVO getUserInfo() {
                // 1. 获取当前登录用户
                SysUser user = SecurityUtils.getUser();
                return getUserInfo(user.getUserId());
        }

        @Override
        public UserInfoVO getUserInfo(String userId) {
                // 如果userId为空，获取当前登录用户信息
                if (!StringUtils.hasText(userId)) {
                        return getUserInfo();
                }

                // 2. 尝试从缓存获取用户信息
                String cacheKey = redisConstants.USER_INFO_PREFIX + userId;
                UserInfoVO cachedUserInfo = redisCacheUtils.getCacheObject(cacheKey);

                if (cachedUserInfo != null) {
                        return cachedUserInfo;
                }

                // 3. 缓存未命中，从数据库查询
                // 获取用户角色列表 (关联表)
                LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(SysUserRole::getUserId, userId);
                List<SysUserRole> userRoles = userRoleMapper.selectList(wrapper);

                List<RoleVO> roles = new ArrayList<>();
                List<String> permissions = new ArrayList<>();

                if (!userRoles.isEmpty()) {
                        // 获取所有角色ID
                        List<Long> roleIds = userRoles.stream()
                                        .map(SysUserRole::getRoleId)
                                        .collect(Collectors.toList());

                        // 查询角色详细信息并构建 RoleVO 列表
                        if (!roleIds.isEmpty()) {
                                List<SysRole> sysRoles = roleService.listByIds(roleIds);
                                for (SysRole role : sysRoles) {
                                        roles.add(new RoleVO(role.getRoleId(), role.getRoleName(), role.getRoleKey()));
                                }
                        }

                        // 获取权限
                        for (Long roleId : roleIds) {
                                List<String> perms = menuMapper.selectPermsByUserId(roleId);
                                if (perms != null) {
                                        permissions.addAll(perms);
                                }
                        }
                }

                // 去重权限
                permissions = permissions.stream()
                                .filter(p -> p != null && !p.isEmpty())
                                .distinct()
                                .collect(Collectors.toList());

                // 查询用户基本信息
                SysUser user = userMapper.selectById(userId);
                if (user == null) {
                        throw new RuntimeException("用户不存在");
                }

                UserInfoVO userInfoVO = new UserInfoVO(user, roles, permissions);

                // 4. 将结果存入缓存
                redisCacheUtils.setCacheObject(cacheKey, userInfoVO, redisConstants.USER_INFO_TTL_SEC,
                                TimeUnit.SECONDS);

                return userInfoVO;
        }

        @Override
        public List<SysMenuVO> getUserRouter() {
                // 1. 获取当前用户
                SysUser user = SecurityUtils.getUser();
                return getUserRouter(user.getUserId());
        }

        @Override
        public List<SysMenuVO> getUserRouter(String userId) {
                // 如果userId为空，获取当前登录用户路由
                if (!StringUtils.hasText(userId)) {
                        return getUserRouter();
                }

                // 2. 尝试从缓存获取路由信息
                String routerCacheKey = redisConstants.USER_ROUTER_PREFIX + userId;
                List<SysMenuVO> cachedRouter = redisCacheUtils.getCacheList(routerCacheKey);

                if (cachedRouter != null) {
                        return cachedRouter;
                }

                // 3. 查询用户角色列表
                LambdaQueryWrapper<SysUserRole> userRoleWrapper = new LambdaQueryWrapper<>();
                userRoleWrapper.eq(SysUserRole::getUserId, userId);
                List<SysUserRole> userRoles = userRoleMapper.selectList(userRoleWrapper);

                if (userRoles.isEmpty()) {
                        // 如果用户没有角色,返回空菜单列表
                        return Collections.emptyList();
                }

                // 4. 获取角色ID列表
                List<Long> roleIds = userRoles.stream()
                                .map(SysUserRole::getRoleId)
                                .collect(Collectors.toList());

                // 5. 查询角色菜单关联表,获取菜单ID
                LambdaQueryWrapper<SysRoleMenu> roleMenuWrapper = new LambdaQueryWrapper<>();
                roleMenuWrapper.in(SysRoleMenu::getRoleId, roleIds);
                List<SysRoleMenu> roleMenus = roleMenuMapper.selectList(roleMenuWrapper);

                // 提取并去重菜单ID
                List<Long> menuIds = roleMenus.stream()
                                .map(SysRoleMenu::getMenuId)
                                .distinct()
                                .collect(Collectors.toList());

                if (menuIds.isEmpty()) {
                        return Collections.emptyList();
                }

                // 6. 查询菜单详情,过滤掉按钮 (menuType != 'F')
                List<SysMenu> menus = menuMapper.selectByIds(menuIds);

                // 过滤：只保留目录(M)和菜单(C),去除按钮(F)，且状态正常
                List<SysMenu> filteredMenus = menus.stream()
                                .filter(menu -> !"F".equals(menu.getMenuType()))
                                .filter(menu -> "0".equals(menu.getStatus()))
                                .collect(Collectors.toList());

                // 使用 BeanUtil 批量转换为 VO
                List<SysMenuVO> menuVOs = cn.hutool.core.bean.BeanUtil.copyToList(filteredMenus, SysMenuVO.class);

                // 7. 构建树形结构并排序
                List<SysMenuVO> menuTree = buildMenuTree(menuVOs);

                // 8. 存入缓存
                redisCacheUtils.setCacheList(routerCacheKey, menuTree, redisConstants.USER_ROUTER_TTL_SEC,
                                TimeUnit.SECONDS);

                return menuTree;
        }

        /**
         * 登录前置校验
         * 
         * @param loginDTO 登录信息
         */
        public void loginPreCheck(loginDTO loginDTO) {
                String username = loginDTO.getUsername();
                String password = loginDTO.getPassword();
                String code = loginDTO.getCode();
                String uuid = loginDTO.getUuid();

                // 用户名或密码为空 错误
                if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                        throw new UserNotExistsException();
                }

                // 验证码校验(如果启用)
                if (captchaProperties.getEnabled()) {
                        // 检查验证码参数是否为空
                        if (!StringUtils.hasText(code) || !StringUtils.hasText(uuid)) {
                                throw new CaptchaException();
                        }

                        // 验证验证码
                        boolean isValid = captchaService.validateCaptcha(uuid, code);
                        if (!isValid) {
                                throw new CaptchaException();
                        }
                }

                // IP黑名单校验
                // String blackStr = configService.selectConfigByKey("sys.login.blackIPList");
                // if (IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr())) {

                // throw new BlackListException();
                // }
        }

        /**
         * 构建树形结构
         * 使用通用TreeUtils工具类构建菜单树
         *
         * @param menus 扁平的菜单列表
         * @return 树形结构的菜单列表
         */
        private List<SysMenuVO> buildMenuTree(List<SysMenuVO> menus) {
                // 使用通用TreeUtils工具类构建树形结构
                return com.xinghuiTec.utils.TreeUtils.buildTree(
                                menus,
                                SysMenuVO::getMenuId, // ID获取器
                                SysMenuVO::getParentId, // 父ID获取器
                                SysMenuVO::setChildren, // 子节点设置器
                                SysMenuVO::getOrderNum // 排序字段获取器
                );
        }

}
