package com.xinghuiTec.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRoleIdContractTest {
    private static final long ID = 9007199254740993L;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snowflakeIdsAreSerializedAsStringsAndAcceptedOnUpdate() throws Exception {
        SysUser user = new SysUser();
        user.setUserId(ID);
        SysRole role = new SysRole();
        role.setRoleId(ID);
        SysMenu menu = new SysMenu();
        menu.setMenuId(ID);

        assertEquals(Long.toString(ID), jsonId(user, "userId"));
        assertEquals(Long.toString(ID), jsonId(role, "roleId"));
        assertEquals(Long.toString(ID), jsonId(menu, "menuId"));

        SysUserAddDTO update = mapper.readValue("{\"userId\":\"" + ID
                + "\",\"username\":\"operator\",\"mobile\":\"13800138000\",\"status\":1,"
                + "\"roleIds\":[\"" + ID + "\"]}", SysUserAddDTO.class);
        assertEquals(ID, update.getUserId());
        assertEquals(ID, update.getRoleIds().get(0));
    }

    private String jsonId(Object value, String field) throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(value)).get(field);
        assertEquals(true, node.isTextual());
        return node.asText();
    }
}
