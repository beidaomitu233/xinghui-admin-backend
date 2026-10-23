package com.xinghuiTec.wechat;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL transactions/unique keys; provider exchange is deliberately controlled, not a real WeChat test. */
@EnabledIfEnvironmentVariable(named="USE03_NATIVE_ENABLED",matches="true")
class WechatFlowMysqlQaTest {
    @Test void concurrentCompleteAndBindingConflictAndDisabledAccount() throws Exception {
        String url=System.getenv("DB_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13323/use03_20261023?"));
        var ds=new DriverManagerDataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);assertEquals("use03_20261023",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(13323,jdbc.queryForObject("SELECT @@port",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_wechat_login_flow",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_wechat_identity_binding",Integer.class));
        var config=mock(WechatConfigService.class);var states=mock(WechatStateStore.class);var provider=mock(WechatIdentityClient.class);var accounts=mock(WechatAccountService.class);
        when(config.get()).thenReturn(new WechatConfigService.Config(true,"mysql-qa-fixture","","https://app.example.com/auth/wechat/callback",1,true));
        when(config.currentSecret()).thenReturn("local-fixture-only");when(states.consume(anyString(),anyString(),anyInt())).thenReturn(true);
        when(provider.exchange(anyString(),anyString(),anyString(),anyString())).thenReturn("mysql-openid-fixture");
        var service=new WechatLoginFlowService(jdbc,config,mock(BusinessConfigService.class),states,provider,accounts);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var browser=WechatStateStore.random();String origin="https://app.example.com";
        var user=new SysUser();user.setUserId(202610233100001L);var principal=new LoginUser(user);principal.setTenantId("000000");
        java.util.function.Supplier<WechatLoginFlowService.Started> start=()->tx.execute(s->service.start("website","/lowcode/records?formId=42",browser,origin,"127.0.0.1"));
        java.util.function.Consumer<WechatLoginFlowService.Started> callback=f->{String state=Arrays.stream(URI.create(f.authorizationUrl()).getQuery().split("&")).filter(v->v.startsWith("state=")).findFirst().orElseThrow().substring(6);assertEquals("verified",tx.execute(s->service.callback("website",state,"fixture-code",browser)));};
        try {
            var first=start.get();var second=start.get();callback.accept(first);callback.accept(second);
            tx.execute(s->service.bind(first.flow(),browser,origin,principal));
            assertThrows(WechatFailure.class,()->tx.execute(s->service.bind(second.flow(),browser,origin,principal)));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_wechat_identity_binding",Integer.class));
            var bound=start.get();callback.accept(bound);
            doThrow(new WechatFailure(403,"fixture disabled")).when(accounts).login(user.getUserId(),"000000");
            assertThrows(WechatFailure.class,()->tx.execute(s->service.complete(bound.flow(),browser,origin)));
            assertEquals("READY_LOGIN",service.status(bound.flow(),browser).status());
            doReturn("local-fixture-token").when(accounts).login(user.getUserId(),"000000");
            var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
            try {
                Callable<Boolean> complete=()->{go.await();try {tx.execute(s->service.complete(bound.flow(),browser,origin));return true;} catch(WechatFailure rejected){return false;}};
                var a=pool.submit(complete);var b=pool.submit(complete);go.countDown();
                assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
                verify(accounts,times(2)).login(user.getUserId(),"000000"); // one disabled attempt + one successful completion
            } finally {pool.shutdownNow();}
            System.out.println("USE03_MYSQL_FLOW: concurrentCompleteExactlyOnce=PASS; identityBindingConflict=PASS; disabledCompletionRollback=PASS; providerIsControlled=true; realWechatVerified=false");
        } finally {
            jdbc.update("DELETE FROM sys_wechat_login_flow WHERE client_id='mysql-qa-fixture'");
            jdbc.update("DELETE FROM sys_wechat_identity_binding WHERE client_id='mysql-qa-fixture'");
        }
    }
}
