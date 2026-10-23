package com.xinghuiTec.businessconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.oss.config.OssRuntimeSettings;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.wechat.WechatConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessConfigServiceTest {
    JdbcTemplate jdbc;
    BusinessConfigService service;
    OssRuntimeSettings oss;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:cfg"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE sys_business_config(section VARCHAR(16) PRIMARY KEY,enabled BOOLEAN,parameters_json TEXT,secrets_ciphertext TEXT,revision INT,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE sys_business_config_history(section VARCHAR(16),revision INT,enabled BOOLEAN,parameters_json TEXT,secrets_ciphertext TEXT,PRIMARY KEY(section,revision))");
        oss=mock(OssRuntimeSettings.class);
        service=new BusinessConfigService(jdbc,json,new SecretCipher(()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),mock(WechatConfigService.class),oss,mock(RuntimeSmsService.class));
    }
    BusinessConfigService.Change change(int revision,Map<String,String> secrets,List<String> clear) {
        return new BusinessConfigService.Change(false,Map.of("provider","minio","endpoint","http://localhost:19000","bucket","cfg-test","basePath","first"),secrets,clear,revision);
    }
    @Test void encryptsKeepsClearsAndPreservesHistoricalCredentials() throws Exception {
        var first=service.save("oss",change(0,Map.of("accessKey","unit-access","secretKey","unit-secret"),List.of()));
        assertEquals(1,first.revision());
        assertTrue(first.secretsConfigured().get("secretKey"));
        String stored=jdbc.queryForObject("SELECT secrets_ciphertext FROM sys_business_config",String.class);
        assertTrue(stored.startsWith("v1."));
        assertFalse(stored.contains("unit-secret"));
        assertFalse(json.writeValueAsString(first).contains("unit-secret"));
        service.save("oss",change(1,Map.of("secretKey",""),List.of()));
        assertEquals("unit-secret",service.current("oss").orElseThrow().secrets().get("secretKey"));
        var third=service.save("oss",change(2,Map.of(),List.of("secretKey")));
        assertFalse(third.secretsConfigured().get("secretKey"));
        assertEquals("unit-secret",service.historical("oss",1).orElseThrow().secrets().get("secretKey"));
        assertEquals("business-oss-1",service.historical("oss",1).orElseThrow().platform());
    }
    @Test void rejectsStaleUnknownAndClearConflictsBeforeChangingDatabase() {
        service.save("oss",change(0,Map.of(),List.of()));
        assertEquals(409,assertThrows(BusinessConfigService.Failure.class,()->service.save("oss",change(0,Map.of(),List.of()))).status);
        assertThrows(BusinessConfigService.Failure.class,()->service.save("oss",change(1,Map.of("unknown","value"),List.of())));
        assertThrows(BusinessConfigService.Failure.class,()->service.save("oss",change(1,Map.of("secretKey","value"),List.of("secretKey"))));
        assertEquals(1,service.get("oss").revision());
    }
    @Test void failedStorageProbeKeepsPreviousRevisionAndHistory() {
        service.save("oss",change(0,Map.of("secretKey","previous"),List.of()));
        doThrow(new IllegalStateException("probe failed")).when(oss).validate(any());
        assertThrows(IllegalStateException.class,()->service.save("oss",change(1,Map.of("secretKey","replacement"),List.of())));
        assertEquals(1,service.get("oss").revision());
        assertEquals("previous",service.current("oss").orElseThrow().secrets().get("secretKey"));
        assertTrue(service.historical("oss",2).isEmpty());
    }
    @Test void inputSecretsAreWriteOnlyAndUnknownRootFieldsRejected() throws Exception {
        assertFalse(json.writeValueAsString(change(0,Map.of("secretKey","hidden"),List.of())).contains("hidden"));
        assertThrows(Exception.class,()->json.readValue("{\"enabled\":false,\"revision\":0,\"unknown\":true}",BusinessConfigService.Change.class));
    }
    @Test void changingSmsProviderDropsOldKeysAndRequiresNewCredentialsWhenEnabled() {
        service=new BusinessConfigService(jdbc,json,new SecretCipher(()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),mock(WechatConfigService.class),oss,new RuntimeSmsService(null));
        service.save("sms",new BusinessConfigService.Change(true,Map.of("provider","alibaba","signature","fixture","templateId","code","region","cn-hangzhou"),Map.of("accessKeyId","ali-fixture","accessKeySecret","ali-secret"),List.of(),0));
        assertThrows(com.xinghuiTec.exception.ServiceException.class,()->service.save("sms",new BusinessConfigService.Change(true,Map.of("provider","tencent","signature","fixture","templateId","code","sdkAppId","123"),Map.of(),List.of(),1)));
        assertEquals("alibaba",service.get("sms").parameters().get("provider"));
        service.save("sms",new BusinessConfigService.Change(false,Map.of("provider","tencent","sdkAppId","123"),Map.of(),List.of(),1));
        assertTrue(service.current("sms").orElseThrow().secrets().isEmpty());
        assertEquals("",service.get("sms").parameters().get("region"));
    }
    @Test void mailIsEncryptedLiveRevisionedAndProviderIsolated() throws Exception {
        var qq=Map.of("provider","qq","host","smtp.qq.com","port","465","security","SSL","user","fixture@qq.com","from","fixture@qq.com");
        var first=service.save("mail",new BusinessConfigService.Change(true,qq,Map.of("password","fixture-authorization-code"),List.of(),0));
        assertEquals("ADAPTED_SEND_UNVERIFIED",first.effectiveState());
        assertFalse(json.writeValueAsString(first).contains("fixture-authorization-code"));
        var snapshot=service.current("mail").orElseThrow();
        var account=com.xinghuiTec.mail.config.RuntimeMailService.configuration(snapshot);
        assertEquals("smtp.qq.com",account.getHost());assertEquals(465,account.getPort());
        assertTrue(account.isSslEnable());assertFalse(account.isStarttlsEnable());
        service.save("mail",new BusinessConfigService.Change(true,qq,Map.of("password",""),List.of(),1));
        assertEquals("fixture-authorization-code",service.current("mail").orElseThrow().secrets().get("password"));
        var other=Map.of("provider","163","host","smtp.163.com","port","465","security","SSL","user","fixture@163.com","from","fixture@163.com");
        assertThrows(com.xinghuiTec.exception.ServiceException.class,()->service.save("mail",new BusinessConfigService.Change(true,other,Map.of(),List.of(),2)));
        assertEquals(2,service.get("mail").revision());
        service.save("mail",new BusinessConfigService.Change(false,other,Map.of(),List.of(),2));
        assertFalse(service.get("mail").secretsConfigured().get("password"));
    }
    @Test void smtpHostAndAccountChangesNeverReusePriorCredentials() {
        var original=new java.util.LinkedHashMap<>(Map.of("provider","smtp","host","smtp.fixture.test","port","587","security","STARTTLS","user","first@fixture.test","from","first@fixture.test"));
        service.save("mail",new BusinessConfigService.Change(true,original,Map.of("password","first-fixture-secret"),List.of(),0));
        var changed=new java.util.LinkedHashMap<>(original);changed.put("host","new-smtp.fixture.test");
        assertThrows(com.xinghuiTec.exception.ServiceException.class,()->service.save("mail",new BusinessConfigService.Change(true,changed,Map.of(),List.of(),1)));
        assertEquals(1,service.get("mail").revision());
        changed.put("host",original.get("host"));changed.put("user","second@fixture.test");
        service.save("mail",new BusinessConfigService.Change(false,changed,Map.of(),List.of(),1));
        assertTrue(service.current("mail").orElseThrow().secrets().isEmpty());
        service.save("mail",new BusinessConfigService.Change(true,changed,Map.of("password","second-fixture-secret"),List.of(),2));
        assertEquals("second-fixture-secret",service.current("mail").orElseThrow().secrets().get("password"));
        var runtime=com.xinghuiTec.mail.config.RuntimeMailService.configuration(service.current("mail").orElseThrow());
        assertTrue(runtime.isStarttlsEnable());assertFalse(runtime.isSslEnable());
        assertEquals(true,runtime.getSmtpProps().get("mail.smtp.starttls.required"));
    }
}
