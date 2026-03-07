package com.xinghuiTec;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysFileQueryDTO;
import com.xinghuiTec.domain.dto.SysMenuQueryDTO;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.entity.SysFile;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.xinghuiTec.service.*;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
public class initTest {


    @Resource
    public  SysRoleService sysRoleService;

    @Test
    public void initRoleTest(){


        SysRoleAddDTO roleAddDTO = new SysRoleAddDTO();
        roleAddDTO.setRoleName("超级管理员" );
        roleAddDTO.setRoleKey("super_admin");
        roleAddDTO.setStatus(1); // 1正常 0停用
        roleAddDTO.setStatus(1);


//        SysRoleAddDTO roleAddDTO2 = new SysRoleAddDTO();
//        roleAddDTO.setRoleName("管理员1" );
//        roleAddDTO.setRoleKey("admin");
//        roleAddDTO.setStatus(1); // 1正常 0停用
//        roleAddDTO.setStatus(1);


        sysRoleService.addRole(roleAddDTO);



    }


    @Resource
    public SysUserService sysUserService;

    @Test
    public void initUserTest(){
        SysUserAddDTO sysUserAddDTO = new SysUserAddDTO();
        sysUserAddDTO.setUsername("beidaomitu");
        sysUserAddDTO.setNickname("北岛");
        sysUserAddDTO.setEmail("beidaomitu233@gmail.com");
        sysUserAddDTO.setPassword("123456");
        sysUserAddDTO.setRoleIds(List.of(2016902236600291329L,2016901862971731969L));

        sysUserService.addUser(sysUserAddDTO);

    }



    @Resource
    public SysMenuService sysMenuService;

    @Resource
    public SysRoleMenuService sysRoleMenuService;

    @Test
    public void initPermissionTest(){

//      给超级管理员添加全部权限
        SysMenuQueryDTO sysMenuQueryDTO = new SysMenuQueryDTO();
        List<SysMenu> menuList = sysMenuService.getMenuList(sysMenuQueryDTO);
        List<Long> collect = menuList.stream().map(SysMenu::getMenuId).toList();
        sysRoleMenuService.assignMenusToRole(2016901862971731969L, collect);
    }


    @Resource
    public SysFileService sysFileService;

    @Test
    public void initgetfile(){
//        List<SysFile> list = sysFileService.list();
//        System.out.println(list);

        Page<SysFile> fileList = sysFileService.getFileList(new SysFileQueryDTO());
        System.out.println(fileList);

    }

}
