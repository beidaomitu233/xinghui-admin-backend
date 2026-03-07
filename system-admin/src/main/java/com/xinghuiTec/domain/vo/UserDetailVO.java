package com.xinghuiTec.domain.vo;

import com.xinghuiTec.domain.vo.UserInfoVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户详细信息响应VO
 * 包含用户基本信息、权限、路由和角色的完整信息
 * 
 * @author 长辉
 * @since 2025-12-31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailVO {

    /**
     * 用户基本信息(包含角色keys、角色names和权限)
     */
    private UserInfoVO userInfo;

    /**
     * 用户路由菜单(树形结构)
     */
    private List<SysMenuVO> routers;
}
