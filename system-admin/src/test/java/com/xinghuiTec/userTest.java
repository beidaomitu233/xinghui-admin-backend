package com.xinghuiTec;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.service.SysUserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.List;

@SpringBootTest
public class userTest {

    @Resource
    private SysUserService sysUserService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysUserRoleMapper sysUserRoleMapper;

    @Resource
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * 测试添加用户（增）
     * 创建一个测试用户，roleId关联为1
     */
    @Test
    public void testAddUser() {
        // 创建用户对象
        String simpleUUID = IdUtil.simpleUUID();
        SysUser user = new SysUser();
        user.setUserId(simpleUUID);
        user.setUsername("testuser3");
        user.setPassword(passwordEncoder.encode("123456")); // 加密密码
        user.setNickname("测试用户");
        user.setEmail("testuser@example.com");
        user.setMobile("13800138000");
        user.setAvatar("https://example.com/avatar.jpg");
        user.setStatus(1); // 1正常 0停用
        user.setLoginIp("127.0.0.1");
        user.setLoginDate(new Date());
        user.setCreateTime(new Date());


        // 保存用户
        int saveResult = sysUserMapper.insert(user);
        System.out.println("保存用户结果: " + saveResult);
        System.out.println("新增用户ID: " + user.getUserId());

        // 关联角色（roleId = 1）
//        if (user.getUserId() != null) {
//            SysUserRole userRole = new SysUserRole();
//            userRole.setUserId(user.getUserId());
//            userRole.setRoleId(1L); // 角色ID为1
//            int insertResult = sysUserRoleMapper.insert(userRole);
//            System.out.println("关联角色结果: " + insertResult);
//            System.out.println("用户 " + user.getUsername() + " 已关联角色ID: 1");
//        }
    }

    /**
     * 测试查询用户（查）
     */
    @Test
    public void testQueryUser() {
        // 查询所有用户
        List<SysUser> userList = sysUserService.list();
        System.out.println("查询到用户总数: " + userList.size());
        userList.forEach(user -> {
            System.out.println("用户ID: " + user.getUserId() +
                    ", 用户名: " + user.getUsername() +
                    ", 昵称: " + user.getNickname());
        });

        // 根据用户名查询
        QueryWrapper<SysUser> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", "testuser");
        SysUser user = sysUserService.getOne(queryWrapper);
        if (user != null) {
            System.out.println("\n根据用户名查询到用户: " + user.getUsername());
            System.out.println("用户详情: " + user);

            // 查询用户的角色关联
            QueryWrapper<SysUserRole> roleQueryWrapper = new QueryWrapper<>();
            roleQueryWrapper.eq("user_id", user.getUserId());
            List<SysUserRole> userRoles = sysUserRoleMapper.selectList(roleQueryWrapper);
            System.out.println("用户关联的角色数量: " + userRoles.size());
            userRoles.forEach(ur -> {
                System.out.println("角色ID: " + ur.getRoleId());
            });
        } else {
            System.out.println("未找到用户: testuser");
        }
    }

    /**
     * 测试更新用户（改）
     */
    @Test
    public void testUpdateUser() {
        // 先查询用户
        QueryWrapper<SysUser> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", "testuser");
        SysUser user = sysUserService.getOne(queryWrapper);

        if (user != null) {
            // 更新用户信息
            user.setNickname("更新后的测试用户");
            user.setEmail("updated@example.com");
            user.setMobile("13900139000");
            user.setUpdateTime(new Date());

            boolean updateResult = sysUserService.updateById(user);
            System.out.println("更新用户结果: " + updateResult);
            System.out.println("更新后的昵称: " + user.getNickname());
        } else {
            System.out.println("未找到要更新的用户");
        }
    }

    /**
     * 测试删除用户（删）
     */
    @Test
    public void testDeleteUser() {
        // 先查询用户
        QueryWrapper<SysUser> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", "testuser");
        SysUser user = sysUserService.getOne(queryWrapper);

        if (user != null) {
            String  userId = user.getUserId();

            // 先删除用户角色关联
            QueryWrapper<SysUserRole> roleQueryWrapper = new QueryWrapper<>();
            roleQueryWrapper.eq("user_id", userId);
            int deleteRoleResult = sysUserRoleMapper.delete(roleQueryWrapper);
            System.out.println("删除用户角色关联数量: " + deleteRoleResult);

            // 再删除用户
            boolean deleteResult = sysUserService.removeById(userId);
            System.out.println("删除用户结果: " + deleteResult);
            System.out.println("已删除用户ID: " + userId);
        } else {
            System.out.println("未找到要删除的用户");
        }
    }

    /**
     * 综合测试：完整的增删改查流程
     */
    @Test
    public void testUserCRUD() {
        System.out.println("========== 开始完整的用户增删改查测试 ==========\n");

        // 1. 增加用户
        System.out.println("【1. 添加用户】");
        SysUser newUser = new SysUser();
        newUser.setUsername("crudtest");
        newUser.setPassword(passwordEncoder.encode("password123"));
        newUser.setNickname("CRUD测试用户");
        newUser.setEmail("crudtest@test.com");
        newUser.setMobile("13700137000");
        newUser.setStatus(1);
        newUser.setCreateTime(new Date());

        sysUserService.save(newUser);
        System.out.println("✓ 用户添加成功，ID: " + newUser.getUserId() + "\n");

        // 关联角色
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(newUser.getUserId());
        userRole.setRoleId(1L);
        sysUserRoleMapper.insert(userRole);
        System.out.println("✓ 角色关联成功，roleId: 1\n");

        // 2. 查询用户
        System.out.println("【2. 查询用户】");
        SysUser queriedUser = sysUserService.getById(newUser.getUserId());
        System.out.println("✓ 查询成功: " + queriedUser.getUsername() + " (" + queriedUser.getNickname() + ")\n");

        // 3. 更新用户
        System.out.println("【3. 更新用户】");
        queriedUser.setNickname("已更新的CRUD测试用户");
        queriedUser.setUpdateTime(new Date());
        sysUserService.updateById(queriedUser);
        System.out.println("✓ 更新成功，新昵称: " + queriedUser.getNickname() + "\n");

        // 4. 删除用户
        System.out.println("【4. 删除用户】");
        QueryWrapper<SysUserRole> roleWrapper = new QueryWrapper<>();
        roleWrapper.eq("user_id", queriedUser.getUserId());
        sysUserRoleMapper.delete(roleWrapper);
        sysUserService.removeById(queriedUser.getUserId());
        System.out.println("✓ 删除成功\n");

        System.out.println("========== 完整的用户增删改查测试完成 ==========");
    }
}
