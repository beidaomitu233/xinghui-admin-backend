package com.xinghuiTec.domain.vo;


import com.xinghuiTec.domain.entity.SysMenu;
import lombok.Data;

import java.util.List;

@Data
public class SysMenuVO extends SysMenu {
    public List<SysMenuVO> children;
}
