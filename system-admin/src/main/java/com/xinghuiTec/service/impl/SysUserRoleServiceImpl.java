package com.xinghuiTec.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.service.SysUserRoleService;
import org.springframework.stereotype.Service;

/**
 * 用户和角色关联表(SysUserRole)表服务实现类
 */
@Service
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {

}
