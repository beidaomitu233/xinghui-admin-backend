package com.xinghuiTec.content;

import com.xinghuiTec.content.ContentService.*;
import com.xinghuiTec.content.ContentService.Module;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContentServiceTest {
    JdbcTemplate jdbc;
    ContentService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:content_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_news(news_id BIGINT AUTO_INCREMENT PRIMARY KEY,title VARCHAR(255),summary VARCHAR(500),"
                + "content CLOB,cover_image VARCHAR(500),category VARCHAR(50),tags VARCHAR(500),status CHAR(1),view_count INT DEFAULT 0,"
                + "create_by VARCHAR(64),publish_time TIMESTAMP,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("ALTER TABLE sys_news ALTER COLUMN news_id RESTART WITH 2052731472993370115");
        service = new ContentService(jdbc);
        SysUser user = new SysUser();
        user.setUserId(7L);
        user.setTenantId("000000");
        var login = new LoginUser(user, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(login, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    NewsChange news(String title, String status) {
        return new NewsChange(title, "summary", "Markdown **body**", "/cover.png", "general", "[\"news\"]", status);
    }

    @Test
    void newsRoundTripKeepsLargeIdsAsStrings() {
        String id = service.createNews(news("Title", "1"));
        assertEquals("2052731472993370115", id);
        assertEquals(id, service.get(Module.NEWS, id).get("id"));
        assertEquals(1, service.list(Module.NEWS, 1, 10, null, null).total());
        service.remove(Module.NEWS, id);
        assertThrows(ServiceException.class, () -> service.get(Module.NEWS, id));
    }
    @Test
    void publicationTimeTracksStatusAndCountersStayReadOnly() {
        String id = service.createNews(news("Draft", "1"));
        assertNull(service.get(Module.NEWS, id).get("publishTime"));
        jdbc.update("UPDATE sys_news SET view_count=7 WHERE news_id=?", id);
        service.updateNews(id, news("Published", "0"));
        Object firstTime = service.get(Module.NEWS, id).get("publishTime");
        assertNotNull(firstTime);
        service.updateNews(id, news("Edited", "0"));
        assertEquals(firstTime, service.get(Module.NEWS, id).get("publishTime"));
        assertEquals(7, service.get(Module.NEWS, id).get("viewCount"));
        service.updateNews(id, news("Offline", "2"));
        assertNull(service.get(Module.NEWS, id).get("publishTime"));
    }

    @Test
    void deletedRecordsCannotReportSuccessfulUpdates() {
        String id = service.createNews(news("Deleted", "1"));
        service.remove(Module.NEWS, id);
        assertThrows(ServiceException.class, () -> service.updateNews(id, news("Deleted", "1")));
        assertThrows(ServiceException.class, () -> service.updateNews("999", news("Missing", "1")));
    }

    @Test
    void unsafeCoverUrlsAreRejectedWithoutWriting() {
        for (String url : List.of("javascript:alert(1)", "data:text/plain,a", "//evil.test/x", "/\\evil.test/x", "https://user:pass@example.com/x")) {
            assertThrows(ServiceException.class, () -> service.createNews(new NewsChange("title", "", "body", url, "", "[]", "1")));
        }
        assertEquals(0, service.list(Module.NEWS, 1, 10, null, null).total());
    }

    @Test
    void blankPublicationAndInvalidStatesAreRejectedButDraftCanBeEmpty() {
        assertThrows(ServiceException.class, () -> service.createNews(new NewsChange("title", null, null, null, null, null, "0")));
        assertNotNull(service.createNews(new NewsChange("draft", null, null, null, null, null, "1")));
        assertThrows(ServiceException.class, () -> service.createNews(news("title", "9")));
    }

    @Test
    void literalLikeSearchCannotBroadenQueryAndPaginationIsBounded() {
        service.createNews(news("100%_literal", "1"));
        service.createNews(news("Unrelated", "1"));
        assertEquals(1, service.list(Module.NEWS, 1, 10, "%_", "1").total());
        assertEquals(0, service.list(Module.NEWS, 1, 10, "' OR 1=1 --", null).total());
        assertThrows(ServiceException.class, () -> service.list(Module.NEWS, 1, 101, null, null));
        assertThrows(ServiceException.class, () -> service.list(Module.NEWS, 0, 10, null, null));
    }

    @Test
    void malformedOrOverflowIdsNeverReachARecord() {
        for (String id : List.of("0", "-1", "01", "abc", "9223372036854775808")) {
            assertThrows(ServiceException.class, () -> service.get(Module.NEWS, id));
            assertThrows(ServiceException.class, () -> service.remove(Module.NEWS, id));
        }
    }
}
