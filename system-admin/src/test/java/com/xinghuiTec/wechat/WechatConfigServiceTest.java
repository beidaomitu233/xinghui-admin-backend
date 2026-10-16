package com.xinghuiTec.wechat;

import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.social.utils.WechatWebsiteAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WechatConfigServiceTest {
    private WechatConfigService service(boolean local) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_wechat_website_config(id INT PRIMARY KEY,enabled BOOLEAN,client_id VARCHAR(64),secret_env_name VARCHAR(128),callback_uri VARCHAR(2048),revision INT,credential_mode VARCHAR(16) DEFAULT 'ENV',secret_ciphertext TEXT)");
        jdbc.update("INSERT INTO sys_wechat_website_config(id,enabled,client_id,secret_env_name,callback_uri,revision) VALUES(1,false,'','','',0)");
        return new WechatConfigService(jdbc, new AiModelConfigRules(), local);
    }
    @Test void savesAndRevisionsRejectLostUpdatesAndIncompleteEnablement() {
        var service = service(false);
        assertFalse(service.get().enabled());
        assertThrows(WechatFailure.class, () -> service.save(new WechatConfigService.Change(true,"","","",0)));
        var updated = service.save(new WechatConfigService.Change(true,"wx123","WECHAT_OPEN_SECRET","https://login.example.com/api/auth/wechat/callback",0));
        assertEquals(1, updated.revision());
        assertEquals("wx123", service.get().clientId());
        assertEquals(409, assertThrows(WechatFailure.class, () -> service.save(new WechatConfigService.Change(false,"","","",0))).status);
        assertEquals(2, service.save(new WechatConfigService.Change(false,"","","",1)).revision());
    }
    @Test void callbackValidationCannotAcceptArbitraryUrlAndLocalRequiresServerOptIn() {
        var production = service(false);
        for (String uri : new String[]{"http://login.example.com/auth/wechat/callback","https://127.0.0.1/auth/wechat/callback","https://login.example.com/other","https://user@login.example.com/auth/wechat/callback","https://login.example.com/auth/wechat/callback?returnUrl=x","http://localhost:5174/api/auth/wechat/callback"})
            assertThrows(WechatFailure.class, () -> production.validateCallback(uri));
        assertDoesNotThrow(() -> service(true).validateCallback("http://localhost:5174/api/auth/wechat/callback"));
        assertThrows(WechatFailure.class, () -> production.save(new WechatConfigService.Change(false,"wx123","plaintext-secret","",0)));
    }
    @Test void actualJustAuthWebsiteUrlUsesFixedEndpointAndNeverSecret() {
        String state = WechatStateStore.random();
        String url = WechatWebsiteAuthorization.authorize("wx123","test-only-secret","https://login.example.com/auth/wechat/callback",state);
        URI uri = URI.create(url);
        assertEquals("https",uri.getScheme()); assertEquals("open.weixin.qq.com",uri.getHost());
        assertEquals("/connect/qrconnect",uri.getPath());
        String query = URLDecoder.decode(uri.getRawQuery(),StandardCharsets.UTF_8);
        assertTrue(query.contains("appid=wx123")); assertTrue(query.contains("scope=snsapi_login"));
        assertTrue(query.contains("state="+state)); assertTrue(query.contains("redirect_uri=https://login.example.com/auth/wechat/callback"));
        assertFalse(url.contains("secret"));
    }
}
