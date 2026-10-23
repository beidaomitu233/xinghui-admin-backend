package com.xinghuiTec.wechat;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatLoginFlowServiceTest {
    JdbcTemplate jdbc;
    WechatConfigService configs;
    WechatStateStore states;
    WechatIdentityClient identity;
    WechatAccountService accounts;
    BusinessConfigService business;
    WechatLoginFlowService service;
    String browser;
    final String origin="https://app.example.com";
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:wxflow"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE sys_wechat_website_config(id INT PRIMARY KEY,revision INT)");
        jdbc.update("INSERT INTO sys_wechat_website_config VALUES(1,1)");
        var migration=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V20261023_310__business_wechat_login.sql"));
        for(String statement:migration.replaceAll("(?m)^--.*$","").split(";")) if(!statement.isBlank()) jdbc.execute(statement.replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",""));
        configs=mock(WechatConfigService.class);states=mock(WechatStateStore.class);identity=mock(WechatIdentityClient.class);accounts=mock(WechatAccountService.class);
        when(configs.get()).thenReturn(new WechatConfigService.Config(true,"website-fixture","",origin+"/auth/wechat/callback",1,true));
        when(configs.currentSecret()).thenReturn("server-fixture-secret");when(states.available()).thenReturn(true);when(states.consume(anyString(),anyString(),eq(1))).thenReturn(true);
        when(identity.exchange(anyString(),anyString(),anyString(),eq("snsapi_login"))).thenReturn("fixture-openid");
        business=mock(BusinessConfigService.class);
        service=new WechatLoginFlowService(jdbc,configs,business,states,identity,accounts);browser=WechatStateStore.random();
    }
    WechatLoginFlowService.Started start() { return service.start("website","/ai/conversation?draft=fixture",browser,origin,"127.0.0.1"); }
    String state(WechatLoginFlowService.Started flow) { return Arrays.stream(URI.create(flow.authorizationUrl()).getQuery().split("&")).filter(v->v.startsWith("state=")).findFirst().orElseThrow().substring(6); }
    @Test void verifiedUnboundIdentityRequiresExistingAccountAndRetainsContext() {
        var flow=start();assertEquals("WAITING",service.status(flow.flow(),browser).status());
        assertEquals("verified",service.callback("website",state(flow),"fixture-code",browser));
        assertEquals("READY_BIND",service.status(flow.flow(),browser).status());
        assertThrows(WechatFailure.class,()->service.complete(flow.flow(),browser,origin));
        var user=new SysUser();user.setUserId(42L);user.setTenantId("tenant-fixture");var login=new LoginUser(user);login.setTenantId("tenant-fixture");
        service.bind(flow.flow(),browser,origin,login);
        assertEquals("COMPLETED",service.status(flow.flow(),browser).status());
        assertEquals("tenant-fixture",jdbc.queryForObject("SELECT tenant_id FROM sys_wechat_identity_binding",String.class));
        assertThrows(WechatFailure.class,()->service.bind(flow.flow(),browser,origin,login));
        verify(accounts).enabled(42L,"tenant-fixture");verify(accounts,never()).login(anyLong(),anyString());
    }
    @Test void boundAccountIsRevalidatedAndTokenCanOnlyBeCompletedOnce() {
        jdbc.update("INSERT INTO sys_wechat_identity_binding(identity_hash,channel,client_id,user_id,tenant_id) VALUES(?,'website','website-fixture',42,'tenant-fixture')",WechatStateStore.hash("website:website-fixture:fixture-openid"));
        var flow=start();service.callback("website",state(flow),"fixture-code",browser);
        assertEquals("READY_LOGIN",service.status(flow.flow(),browser).status());
        doThrow(new WechatFailure(403,"disabled")).when(accounts).login(42L,"tenant-fixture");
        assertThrows(WechatFailure.class,()->service.complete(flow.flow(),browser,origin));
        assertEquals("READY_LOGIN",service.status(flow.flow(),browser).status());
        doReturn("fixture-token").when(accounts).login(42L,"tenant-fixture");
        var result=service.complete(flow.flow(),browser,origin);assertEquals("fixture-token",result.accessToken());
        assertEquals("/ai/conversation?draft=fixture",result.returnPath());
        assertFalse(result.toString().contains("fixture-token"));
        assertThrows(WechatFailure.class,()->service.complete(flow.flow(),browser,origin));
    }
    @Test void wrongBrowserChannelAndReplayCannotExchangeAnIdentity() {
        var flow=start();String state=state(flow);
        assertEquals("invalid",service.callback("mp",state,"fixture-code",browser));
        assertEquals("invalid",service.callback("website",state,"fixture-code",WechatStateStore.random()));
        verifyNoInteractions(identity);
        assertThrows(WechatFailure.class,()->service.status(flow.flow(),WechatStateStore.random()));
        assertEquals("verified",service.callback("website",state,"fixture-code",browser));
        assertEquals("invalid",service.callback("website",state,"fixture-code",browser));
        verify(identity,times(1)).exchange(anyString(),anyString(),anyString(),anyString());
    }
    @Test void expiryDisableCancellationAndProviderFailureCannotBecomeLogin() {
        var flow=start();service.cancel(flow.flow(),browser,origin);
        assertEquals("invalid",service.callback("website",state(flow),"fixture-code",browser));
        var expired=start();jdbc.update("UPDATE sys_wechat_login_flow SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE flow_hash=?",WechatStateStore.hash(expired.flow()));
        assertEquals("expired",service.callback("website",state(expired),"fixture-code",browser));
        var failed=start();when(identity.exchange(anyString(),anyString(),anyString(),anyString())).thenThrow(new WechatFailure(503,"upstream"));
        assertEquals("failed",service.callback("website",state(failed),"fixture-code",browser));
        assertEquals("FAILED",service.status(failed.flow(),browser).status());
        assertEquals("invalid",service.callback("website",state(failed),"fixture-code",browser));
        var disabled=start();when(configs.get()).thenReturn(new WechatConfigService.Config(false,"website-fixture","",origin+"/auth/wechat/callback",2,true));
        assertEquals("EXPIRED",service.status(disabled.flow(),browser).status());
        assertThrows(WechatFailure.class,()->service.complete(disabled.flow(),browser,origin));
    }
    @Test void returnAndOriginAllowlistRejectsExternalAndEncodedPaths() {
        for(String path:List.of("/workspace","/analytics?period=week","/lowcode/records?formId=42","/lowcode/fill/42?draft=hello%20world")) assertEquals(path,WechatLoginFlowService.safeReturn(path));
        for(String path:List.of("//evil.example","https://evil.example","/auth/login","/ai/%2f%2fevil","/ai/../auth","/ai/x#//evil","/ai\\evil")) assertThrows(WechatFailure.class,()->WechatLoginFlowService.safeReturn(path),path);
        assertThrows(WechatFailure.class,()->service.start("website","/",browser,"https://evil.example","127.0.0.1"));
        verifyNoInteractions(states);
    }
    @Test void mpUsesOnlyServiceAccountCredentialsAndScopeAndAlphanumericState() {
        jdbc.execute("CREATE TABLE sys_business_config(section VARCHAR(32) PRIMARY KEY,revision INT)");jdbc.update("INSERT INTO sys_business_config VALUES('wechatMp',2)");
        when(business.current("wechatMp")).thenReturn(Optional.of(new com.xinghuiTec.config.BusinessConfigSource.Snapshot("wechatMp",true,2,
                Map.of("clientId","mp-fixture","callbackUri",origin+"/api/auth/wechat/mp-callback","scope","snsapi_base"),Map.of("appSecret","mp-secret"))));
        when(states.consume(anyString(),anyString(),eq(2))).thenReturn(true);
        when(identity.exchange("mp-fixture","mp-secret","fixture-code","snsapi_base")).thenReturn("mp-openid");
        var flow=service.start("mp","/analytics?range=month",browser,origin,"127.0.0.1");
        assertEquals("/connect/oauth2/authorize",URI.create(flow.authorizationUrl()).getPath());
        assertTrue(flow.authorizationUrl().contains("scope=snsapi_base"));assertTrue(state(flow).matches("[A-Za-z0-9]{43}"));
        assertEquals("verified",service.callback("mp",state(flow),"fixture-code",browser));
        verify(identity).exchange("mp-fixture","mp-secret","fixture-code","snsapi_base");verifyNoInteractions(configs);
    }
}
