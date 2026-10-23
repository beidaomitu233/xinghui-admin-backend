package com.xinghuiTec.businessconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.mail.config.RuntimeMailService;
import com.xinghuiTec.oss.config.OssRuntimeSettings;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.wechat.WechatConfigService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit USE-03 local schema only. No provider requests and no production account changes. */
@EnabledIfEnvironmentVariable(named="USE03_NATIVE_ENABLED",matches="true")
class Use03NativeQaTest {
    @Test void migrateAndVerifyEncryptedRuntimeAgainstAssignedMysql() {
        String url=System.getenv("DB_URL");
        assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13323/use03_20261023?"));
        var ds=new DriverManagerDataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        assertEquals("use03_20261023",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(13323,jdbc.queryForObject("SELECT @@port",Integer.class));
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load();
        flyway.migrate();flyway.validate();
        assertEquals("20261023.310",flyway.info().current().getVersion().toString());
        var cipher=new SecretCipher();assertTrue(cipher.ready());
        var service=new BusinessConfigService(jdbc,new ObjectMapper(),cipher,new WechatConfigService(jdbc,new AiModelConfigRules(),true,cipher),mock(OssRuntimeSettings.class),new RuntimeSmsService(null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_business_config WHERE section='mail'",Integer.class));
        try {
            var p=Map.of("provider","qq","host","smtp.qq.com","port","465","security","SSL","user","fixture@qq.com","from","fixture@qq.com");
            service.save("mail",new BusinessConfigService.Change(true,p,Map.of("password","use03-local-fixture-only"),List.of(),0));
            String encrypted=jdbc.queryForObject("SELECT secrets_ciphertext FROM sys_business_config WHERE section='mail'",String.class);
            assertTrue(encrypted.startsWith("v1."));assertFalse(encrypted.contains("use03-local-fixture-only"));
            var reopened=new BusinessConfigService(new JdbcTemplate(ds),new ObjectMapper(),new SecretCipher(),new WechatConfigService(jdbc,new AiModelConfigRules(),true,cipher),mock(OssRuntimeSettings.class),new RuntimeSmsService(null));
            var snapshot=reopened.current("mail").orElseThrow();
            assertEquals("smtp.qq.com",RuntimeMailService.configuration(snapshot).getHost());
            assertEquals("use03-local-fixture-only",RuntimeMailService.configuration(snapshot).getPass());
            service.save("mail",new BusinessConfigService.Change(false,p,Map.of(),List.of("password"),1));
            assertFalse(service.get("mail").secretsConfigured().get("password"));
            assertThrows(BusinessConfigService.Failure.class,()->service.save("mail",new BusinessConfigService.Change(false,p,Map.of(),List.of(),1)));
        } finally {
            jdbc.update("DELETE FROM sys_business_config_history WHERE section='mail'");
            jdbc.update("DELETE FROM sys_business_config WHERE section='mail'");
        }
        String password=System.getenv("USE03_FIXTURE_PASSWORD");assertNotNull(password);assertTrue(password.length()>=24);
        long id=202610233100001L;
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? OR mobile='18800000310'",Integer.class,id));
        jdbc.update("INSERT INTO sys_user(user_id,tenant_id,username,password,nickname,mobile,status,is_deleted) VALUES(?,'000000','use03_local_fixture',?,'USE03 本地验收','18800000310',1,0)",id,new BCryptPasswordEncoder().encode(password));
        var roles=jdbc.queryForList("SELECT role_id FROM sys_role WHERE tenant_id='000000' AND role_key IN ('admin','super_admin') AND status=1 AND is_deleted=0",Long.class);
        assertFalse(roles.isEmpty());
        for(long role:roles) jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
        System.out.println("USE03_NATIVE: exactSchema=PASS; migrations=20261023.310; encryptedRuntime=PASS; conflict=PASS; secretClear=PASS; providerRequests=0; localFixtureCreated=1");
    }
}
