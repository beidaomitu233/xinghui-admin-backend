package com.xinghuiTec.service;

import com.xinghuiTec.domain.dto.loginDTO;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.domain.vo.UserInfoVO;

import java.util.List;

public interface LoginService {
    /**
     * 用户登录
     * 
     * @param user 登录信息
     * @return JWT token
     */
    String login(loginDTO user);

    /**
     * 用户登出
     * 
     * @return 成功消息
     */
    String logout();

    /**
     * 获取当前登录用户信息
     * 
     * @return 用户信息VO
     */
    UserInfoVO getUserInfo();

    /**
     * 获取指定用户信息
     * 
     * @param userId 用户ID，如果为空则获取当前登录用户信息
     * @return 用户信息VO
     */
    UserInfoVO getUserInfo(Long userId);

    /**
     * 获取当前登录用户路由菜单
     * 
     * @return 树形结构的菜单列表
     */
    List<SysMenuVO> getUserRouter();

    /**
     * 获取指定用户路由菜单
     * 
     * @param userId 用户ID，如果为空则获取当前登录用户路由
     * @return 树形结构的菜单列表
     */
    List<SysMenuVO> getUserRouter(Long userId);
}