package com.xinghuiTec.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.XinghuiStartApplication;
import com.xinghuiTec.utils.RedisCacheUtils;
import io.minio.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.xinghuiTec.config.SecretCipher;

import static org.junit.jupiter.api.Assertions.*;

/** Required CI service test. No opt-in skip, mocks, production credentials, or existing schemas. */
class ReleaseServicesIT {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String base;
    String prefix;
    static final long ADMIN_ID=20261012040401L, USER_ID=20261012040402L;
    static String required(String name) { String value=System.getenv(name); assertNotNull(value,"Missing isolated service setting: "+name); return value; }
    static int port(String name) { int port=Integer.parseInt(required(name)); assertTrue(port>0 && port<65536); return port; }
    HttpResponse<byte[]> call(String method,String path,String body,String token,String contentType) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(token!=null) request.header("Authorization",token);
        if(contentType!=null) request.header("Content-Type",contentType);
        request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode success(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200,response.statusCode(),"HTTP operation failed");
        var result=json.readTree(response.body()); assertEquals(200,result.path("code").asInt(),"Business operation failed"); return result.path("data");
    }
    String login(String phone) throws Exception {
        return success(call("POST","/user/login","{\"phone\":\""+phone+"\",\"password\":\"admin123\"}",null,"application/json")).asText();
    }
    @Test void freshMigrationRealAuthenticationPermissionsSqlAndObjectRoundTrip() throws Exception {
        int dbPort=port("OSS04_DB_PORT"),redisPort=port("OSS04_REDIS_PORT"),minioPort=port("OSS04_MINIO_PORT");
        String dbPassword=required("OSS04_DB_PASSWORD"),redisPassword=required("OSS04_REDIS_PASSWORD"),storagePassword=required("OSS04_MINIO_PASSWORD");
        String schema=System.getenv().getOrDefault("OSS04_SCHEMA","xinghui_oss04_ci"),dbUser=System.getenv().getOrDefault("OSS04_DB_USER","oss04_ci");
        boolean local=Set.of("oss04_current_20261012", "oss04_current_20261013_v2").contains(schema);
        assertFalse(local && "true".equalsIgnoreCase(System.getenv("CI")), "CI must use its declared containers");
        assertTrue(local || schema.equals("xinghui_oss04_ci"),"Only assigned isolated schema allowed");
        assertEquals(local ? "qa_ci" : "oss04_ci",dbUser);
        assertEquals(local ? 13318 : 3306,dbPort);
        assertEquals(local ? 16379 : 6379,redisPort); assertEquals(local ? 19000 : 9000,minioPort);
        prefix="oss04-current/"+UUID.randomUUID()+"/";
        String storageUser=local ? "xinghuiqa" : "oss04_ci";
        String url="jdbc:mysql://127.0.0.1:"+dbPort+"/"+schema+"?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        var source=new DriverManagerDataSource(url,dbUser,dbPassword); var jdbc=new JdbcTemplate(source);
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()",String.class).startsWith(dbUser+"@"));
        assertEquals(dbPort,jdbc.queryForObject("SELECT @@port",Integer.class));
        if(local) assertEquals("d:/changhuiengineer/devproject/xinghui-admin2/.qa-local/20261012-empty/mysql-data/",
                jdbc.queryForObject("SELECT @@datadir",String.class).replace('\\','/').toLowerCase(java.util.Locale.ROOT));
        String version=jdbc.queryForObject("SELECT @@version",String.class);
        assertTrue(version.startsWith(required("OSS04_MYSQL_SERIES")+"."),"Wrong MySQL series");
        int migrationCount = 0;
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",Integer.class,schema),"CI requires a fresh schema; never clean an existing one");
        String endpoint="http://127.0.0.1:"+minioPort;
        var minio=MinioClient.builder().endpoint(endpoint).credentials(storageUser,storagePassword).build();
        if(!minio.bucketExists(BucketExistsArgs.builder().bucket("xinghui-admin").build())) minio.makeBucket(MakeBucketArgs.builder().bucket("xinghui-admin").build());
        assertEquals(0,objectCount(minio),"CI bucket must start empty");
        Map<String,Object> props=new HashMap<>(Map.of("server.address","127.0.0.1","server.port","0","db.url",url,"db.username",dbUser,"db.password",dbPassword,
                "spring.flyway.baseline-on-migrate","false","spring.flyway.locations","classpath:db/migration,classpath:db/optional-migration","spring.flyway.clean-disabled","true","mail.enabled","false","logging.level.root","WARN"));
        props.putAll(Map.of("spring.data.redis.host","127.0.0.1","spring.data.redis.port",Integer.toString(redisPort),"spring.data.redis.password",redisPassword,"spring.data.redis.database","15",
                "dromara.x-file-storage.minio[0].end-point",endpoint,"dromara.x-file-storage.minio[0].access-key",storageUser,"dromara.x-file-storage.minio[0].secret-key",storagePassword,
                "logging.level.com.xinghuiTec.mapper","WARN","captcha.enabled","false","redisson.threads","2"));
        props.put("redisson.nettyThreads","2");
        props.put("dromara.x-file-storage.enable",true);
        props.put("dromara.x-file-storage.minio[0].base-path",prefix);
        props.put("dromara.x-file-storage.minio[0].platform","minio-1");
        props.put("dromara.x-file-storage.minio[0].enable-storage",true);
        props.put("dromara.x-file-storage.minio[0].bucket-name","xinghui-admin");
        var environment=new StandardEnvironment(); environment.getPropertySources().addFirst(new MapPropertySource("isolated-quality",props));
        var app=new SpringApplication(XinghuiStartApplication.class); app.setEnvironment(environment);
        ServletWebServerApplicationContext context=null; String admin=null,user=null; Long fileId=null,modelId=null,formId=null,recordId=null; Integer configId=null;
        boolean businessOwned=false,usersOwned=false; int formRevision=0,recordRevision=0;
        try {
            context=(ServletWebServerApplicationContext)app.run(); base="http://127.0.0.1:"+context.getWebServer().getPort();
            migrationCount = verifyMigrations(context.getBean(Flyway.class), jdbc);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0",Integer.class));
            RedisCacheUtils loginCache=context.getBean(RedisCacheUtils.class);
            assertNull(loginCache.getCacheObject("admin:login:"+ADMIN_ID),"Owned login fixture must start absent");
            assertNull(loginCache.getCacheObject("admin:login:"+USER_ID),"Owned login fixture must start absent");
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id IN (?,?)",Integer.class,ADMIN_ID,USER_ID));
            usersOwned=true;
            for(long[] pair:List.of(new long[]{ADMIN_ID,1},new long[]{USER_ID,2})) {
                String phone=pair[0]==ADMIN_ID?"13900000441":"13900000442";
                jdbc.update("INSERT INTO sys_user(user_id,tenant_id,username,password,nickname,mobile,status,is_deleted) SELECT ?,tenant_id,?,password,?, ?,status,0 FROM sys_user WHERE user_id=?",pair[0],"oss04_ci_"+pair[0],"OSS04 neutral fixture",phone,pair[1]);
                jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,role_id FROM sys_user_role WHERE user_id=?",pair[0],pair[1]);
            }
            assertEquals(401,call("GET","/user/info",null,null,null).statusCode());
            admin=login("13900000441"); user=login("13900000442"); assertFalse(admin.isBlank()); assertFalse(user.isBlank());
            success(call("GET","/user/info",null,admin,null)); success(call("GET","/user/router",null,admin,null));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms IN ('system:wechat:query','system:wechat:edit') AND is_deleted=0",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms REGEXP '^(example:material|system:(download|page|siteConfig|navigation|banner|ecosystem|apiDoc)):'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms REGEXP '^(example:material|system:(download|page|siteConfig|navigation|banner|ecosystem|apiDoc)):'",Integer.class));
            for(String retiredTable:List.of("sys_site_config","sys_site_navigation","sys_site_banner","sys_site_product","sys_site_api_doc","sys_download","sys_page","example_material")) {
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",Integer.class,schema,retiredTable),"Retired table must be absent: "+retiredTable);
            }
            assertEquals(403,call("GET","/system/businessConfig",null,user,null).statusCode());
            assertEquals(403,call("GET","/lowcode/forms",null,user,null).statusCode());
            assertEquals(3,success(call("GET","/system/businessConfig",null,admin,null)).size());
            var sms=context.getBean(com.xinghuiTec.sms.core.RuntimeSmsService.class);
            assertThrows(com.xinghuiTec.exception.ServiceException.class,()->sms.send("13900000441",new java.util.LinkedHashMap<>()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_business_config WHERE section='oss'",Integer.class));
            businessOwned=true;
            var parameters=new HashMap<String,String>(Map.of("provider","minio","endpoint",endpoint,"bucket","xinghui-admin","basePath",prefix));
            var saved=success(call("PUT","/system/businessConfig/oss",json.writeValueAsString(Map.of("enabled",true,"revision",0,"parameters",parameters,"secrets",Map.of("accessKey",storageUser,"secretKey",storagePassword))),admin,"application/json"));
            assertTrue(saved.path("secretsConfigured").path("secretKey").asBoolean());
            assertFalse(saved.toString().contains(storagePassword),"Storage secret must never be returned");
            String stored=jdbc.queryForObject("SELECT secrets_ciphertext FROM sys_business_config WHERE section='oss'",String.class);
            assertTrue(stored.startsWith("v1."));assertFalse(stored.contains(storagePassword));
            modelId=success(call("POST","/system/ai/models",json.writeValueAsString(Map.of("providerCode","openrouter","modelCode","oss04-ci/neutral:free","endpointUrl","https://openrouter.ai/api/v1","timeoutSeconds",10,"enabled",true)),admin,"application/json")).asLong();
            assertTrue(modelId>0);
            String neutralKey="oss04-ci-invalid-neutral-key-no-provider-call";
            var credential=success(call("PUT","/system/ai/models/"+modelId+"/credential",json.writeValueAsString(Map.of("mode","DB","apiKey",neutralKey,"revision",0,"clearKey",false)),admin,"application/json"));
            assertTrue(credential.path("credentialConfigured").asBoolean());assertFalse(credential.toString().contains(neutralKey));
            assertEquals(403,call("POST","/system/ai/models/"+modelId+"/test",null,user,null).statusCode());
            var field=Map.of("fieldKey","text","label","Neutral text","type","text","required",true,"maxLength",200,"listVisible",true,"searchable",true);
            formId=success(call("POST","/lowcode/forms",json.writeValueAsString(Map.of("name","OSS04 neutral form","formKey","oss04_ci_neutral","status","draft","revision",0,"schema",Map.of("fields",List.of(field)))),admin,"application/json")).asLong();
            assertTrue(formId>0);
            var published=success(call("POST","/lowcode/forms/"+formId+"/publish","{\"revision\":0}",admin,"application/json"));formRevision=published.path("revision").asInt();
            assertEquals(1,success(call("GET","/lowcode/forms/"+formId+"/runtime",null,admin,null)).path("schemaVersion").asInt());
            recordId=success(call("POST","/lowcode/forms/"+formId+"/records","{\"schemaVersion\":1,\"revision\":0,\"data\":{\"text\":\"neutral\"}}",admin,"application/json")).path("id").asLong();
            assertTrue(recordId>0,"Created record must return its ID");
            var updated=success(call("PUT","/lowcode/forms/"+formId+"/records/"+recordId,"{\"schemaVersion\":1,\"revision\":0,\"data\":{\"text\":\"updated\"}}",admin,"application/json"));recordRevision=updated.path("revision").asInt();
            assertEquals("updated",success(call("GET","/lowcode/forms/"+formId+"/records/"+recordId,null,admin,null)).path("data").path("text").asText());
            var denied=call("GET","/sysConfig/list",null,user,null);
            assertTrue(denied.statusCode()==403 || json.readTree(denied.body()).path("code").asInt()==309,"Unprivileged user must be refused");
            String config="{\"configKey\":\"sys.index.footerText\",\"configValue\":\"OSS04 neutral fixture\"}";
            configId=success(call("POST","/sysConfig",config,admin,"application/json")).asInt();
            assertEquals("OSS04 neutral fixture",success(call("GET","/sysConfig/"+configId,null,admin,null)).path("configValue").asText());
            assertEquals("OSS04 neutral fixture",jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_id=?",String.class,configId));
            String boundary="oss04-neutral-boundary",content="OSS04 neutral object";
            String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"oss04-fixture.txt\"\r\nContent-Type: text/plain\r\n\r\n"+content+"\r\n--"+boundary+"--\r\n";
            fileId=success(call("POST","/system/oss/upload",multipart,admin,"multipart/form-data; boundary="+boundary)).path("ossId").asLong(); assertTrue(fileId>0);
            assertEquals("business-oss-1",jdbc.queryForObject("SELECT platform FROM sys_oss WHERE oss_id=?",String.class,fileId));
            assertEquals(1,objectCount(minio)); assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_oss WHERE oss_id=?",Integer.class,fileId));
            success(call("GET","/system/oss/info/"+fileId,null,admin,null));
            var download=call("GET","/system/oss/download/"+fileId,null,admin,null); assertEquals(200,download.statusCode()); assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),download.body());
            parameters.put("basePath",prefix+"new/");
            success(call("PUT","/system/businessConfig/oss",json.writeValueAsString(Map.of("enabled",true,"revision",1,"parameters",parameters,"secrets",Map.of("accessKey","","secretKey",""))),admin,"application/json"));
            assertEquals(409,call("PUT","/system/businessConfig/oss",json.writeValueAsString(Map.of("enabled",true,"revision",1,"parameters",parameters)),admin,"application/json").statusCode());
            context.close();context=null;
            var restarted=new SpringApplication(XinghuiStartApplication.class);restarted.setEnvironment(environment);
            context=(ServletWebServerApplicationContext)restarted.run();base="http://127.0.0.1:"+context.getWebServer().getPort();
            assertEquals(migrationCount,verifyMigrations(context.getBean(Flyway.class),jdbc));
            assertTrue(success(call("GET","/system/businessConfig/oss",null,admin,null)).path("secretsConfigured").path("secretKey").asBoolean());
            String encryptedKey=jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_model_config WHERE model_id=?",String.class,modelId);
            assertTrue(neutralKey.equals(context.getBean(SecretCipher.class).decrypt("ai:model:"+modelId,encryptedKey)),"Reopened model key decryption failed");
            var oldDownload=call("GET","/system/oss/download/"+fileId,null,admin,null);assertEquals(200,oldDownload.statusCode());assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),oldDownload.body());
            if("true".equals(System.getenv("OSS04_FORCE_FAILURE"))) fail("Controlled service assertion failure");
        } finally {
            try {
            if(context!=null) {
                try {
                    if(fileId!=null && admin!=null) {
                        success(call("POST","/system/oss/remove","["+fileId+"]",admin,"application/json"));
                        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_oss WHERE oss_id=? AND is_deleted=0",Integer.class,fileId));
                        // Product deletion is soft. Physically remove only this isolated test's tombstone.
                        jdbc.update("DELETE FROM sys_oss WHERE oss_id=? AND is_deleted=1 AND original_name='oss04-fixture.txt' AND create_by=?",fileId,Long.toString(ADMIN_ID));
                        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_oss WHERE oss_id=?",Integer.class,fileId));
                    }
                    if(configId!=null && admin!=null) { success(call("DELETE","/sysConfig/"+configId,null,admin,null)); assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_id=?",Integer.class,configId)); }
                    if(recordId!=null && admin!=null) {
                        success(call("DELETE","/lowcode/forms/"+formId+"/records/"+recordId+"?revision="+recordRevision,null,admin,null));
                        assertEquals(1,jdbc.queryForObject("SELECT is_deleted FROM sys_lowcode_record WHERE id=? AND form_id=?",Integer.class,recordId,formId));
                        jdbc.update("DELETE FROM sys_lowcode_record WHERE id=? AND form_id=? AND is_deleted=1 AND creator=?",recordId,formId,Long.toString(ADMIN_ID));
                    }
                    if(formId!=null && admin!=null) success(call("DELETE","/lowcode/forms/"+formId+"?revision="+formRevision,null,admin,null));
                    if(admin!=null) success(call("POST","/user/logout",null,admin,null));
                    if(user!=null) success(call("POST","/user/logout",null,user,null));
                    RedisCacheUtils cache=context.getBean(RedisCacheUtils.class); assertNull(cache.getCacheObject("admin:login:"+ADMIN_ID)); assertNull(cache.getCacheObject("admin:login:"+USER_ID));
                    for(long id:List.of(ADMIN_ID,USER_ID)) {
                        for(String head:List.of("admin:userinfo:","admin:user:router:")) { cache.deleteObject(head+id);assertNull(cache.getCacheObject(head+id)); }
                    }
                    assertEquals(0,objectCount(minio));
                } finally {
                    try {
                        if(usersOwned) {
                            RedisCacheUtils cache=context.getBean(RedisCacheUtils.class);
                            for(long id:List.of(ADMIN_ID,USER_ID)) {
                                for(String head:List.of("admin:login:","admin:userinfo:","admin:user:router:")) {
                                    cache.deleteObject(head+id);
                                    assertNull(cache.getCacheObject(head+id),"Owned cache fixture cleanup failed");
                                }
                            }
                        }
                    } finally { context.close(); }
                }
            }
            } finally {
        if(modelId!=null) jdbc.update("DELETE FROM sys_ai_model_config WHERE model_id=? AND model_code='oss04-ci/neutral:free'",modelId);
        if(businessOwned) { jdbc.update("DELETE FROM sys_business_config_history WHERE section='oss' AND revision IN (1,2)");jdbc.update("DELETE FROM sys_business_config WHERE section='oss' AND revision IN (1,2)"); }
        if(formId!=null) { jdbc.update("DELETE FROM sys_lowcode_record WHERE form_id=?",formId);jdbc.update("DELETE FROM sys_lowcode_form_version WHERE form_id=?",formId);jdbc.update("DELETE FROM sys_lowcode_form WHERE id=? AND form_key='oss04_ci_neutral'",formId); }
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (?,?)",ADMIN_ID,USER_ID);
        jdbc.update("DELETE FROM sys_user WHERE user_id IN (?,?) AND username LIKE 'oss04_ci_%'",ADMIN_ID,USER_ID);
            }
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id IN (?,?)",Integer.class,ADMIN_ID,USER_ID));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (?,?)",Integer.class,ADMIN_ID,USER_ID));
        if(modelId!=null) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_model_config WHERE model_id=?",Integer.class,modelId));
        if(businessOwned) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_business_config WHERE section='oss'",Integer.class));
        if(formId!=null) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_form WHERE id=?",Integer.class,formId));
        System.out.println("OSS04_SERVICES_OK mysql="+version+" history="+migrationCount+" migration=fresh auth=real configEncrypted=true lowcodeCrud=true restartedOldObjectRead=true minioRoundTrip=true ownedFixtures=0 providerRequests=0");
    }
    int verifyMigrations(Flyway flyway, JdbcTemplate jdbc) {
        flyway.validate();
        var migrations = flyway.info().all();
        assertTrue(migrations.length > 0, "Migration discovery must not be empty");
        for (var migration : migrations) {
            assertEquals(MigrationState.SUCCESS, migration.getState(),
                    "Every candidate migration must have applied successfully: " + migration.getScript());
        }
        assertEquals(migrations.length, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        return migrations.length;
    }
    int objectCount(MinioClient minio) throws Exception {
        int count=0; for(var object:minio.listObjects(ListObjectsArgs.builder().bucket("xinghui-admin").prefix(prefix).recursive(true).build())) { object.get(); count++; } return count;
    }
}
