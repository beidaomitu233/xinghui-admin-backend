package com.xinghuiTec.notification;

import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationTemplateServiceTest {
    @Test
    void invalidAdminTemplateReturnsClearBusinessErrorBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationTemplateService service = new NotificationTemplateService(jdbc, "");

        ServiceException error = assertThrows(ServiceException.class, () -> service.create(
                new NotificationTemplateService.Create("sample.created", "示例", "记录 {name}",
                        "正文", null, List.of(), true)));

        assertEquals("模板包含未声明变量", error.getMessage());
        verifyNoInteractions(jdbc);
    }
}
