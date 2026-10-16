package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.vo.UserImportRowResult;
import com.xinghuiTec.domain.vo.UserImportSummary;
import com.xinghuiTec.service.SysUserService;
import com.xinghuiTec.utils.TemporaryPasswordGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller 契约测试使用 mock 服务，绝不连接或写入默认业务库。 */
@ExtendWith(MockitoExtension.class)
class SysUserControllerTest {
    private static final long USER_ID = 9007199254740993L;
    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private SysUserService userService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SysUserController controller = new SysUserController();
        ReflectionTestUtils.setField(controller, "sysUserService", userService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listUsesPageRecordsAndTotal() throws Exception {
        SysUser user = new SysUser();
        user.setUserId(USER_ID);
        user.setUsername("operator");
        Page<SysUser> page = new Page<>(2, 10, 23);
        page.setRecords(List.of(user));
        when(userService.getUserList(any(SysUserQueryDTO.class))).thenReturn(page);

        mvc.perform(get("/system/user/list").param("pageNum", "2").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(23))
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(USER_ID)))
                .andExpect(jsonPath("$.data.records[0].username").value("operator"));
    }

    @Test
    void addReturnsExactStringIdWithoutWritingDatabase() throws Exception {
        when(userService.addUser(any(SysUserAddDTO.class))).thenReturn(USER_ID);
        SysUserAddDTO dto = new SysUserAddDTO();
        dto.setUsername("operator");
        dto.setPassword(TemporaryPasswordGenerator.generate());
        dto.setMobile("13800138000");
        dto.setStatus(1);

        mvc.perform(post("/system/user/add").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(Long.toString(USER_ID)));
        verify(userService).addUser(any(SysUserAddDTO.class));
    }

    @Test
    void resetReturnsTheGeneratedTemporaryPasswordOnce() throws Exception {
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        when(userService.resetPassword(USER_ID)).thenReturn(temporaryPassword);

        mvc.perform(post("/system/user/resetPwd").param("userId", Long.toString(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(temporaryPassword));
        verify(userService).resetPassword(USER_ID);
    }

    @Test
    void importReturnsStructuredRows() throws Exception {
        UserImportSummary summary = new UserImportSummary(1, 0, 1, 0,
                List.of(new UserImportRowResult(2, "failed", "手机号码", "手机号格式不正确")));
        when(userService.importUsers(any())).thenReturn(summary);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/system/user/import")
                        .file(new MockMultipartFile("file", "users.xlsx", "application/octet-stream", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.rows[0].status").value("failed"));
    }
}
