package com.xinghuiTec.ai.query;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.LowcodeSchema;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated CI MySQL with stat+wall. Missing/wrong environment fails; never migrates, baselines or skips. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiSceneSqlDruidMySqlIT {
    final String nonce=UUID.randomUUID().toString().replace("-","").substring(0,10);
    final String orders="qa_sql_order_"+nonce,customers="qa_sql_customer_"+nonce,heavy="qa_sql_heavy_"+nonce;
    final String tenantA="qa"+nonce+"a",tenantB="qa"+nonce+"b";
    final long base=1_800_000_000_000_000_000L+Math.floorMod(System.nanoTime(),100_000_000L)*100;
    final Set<String> grants=Set.of("ai:scene:query","ai:data:database:read","lowcode:record:list","lowcode:record:query","qa:sql:read");
    DruidDataSource source;JdbcTemplate jdbc;SceneQueryService runtime;SqlReadOnlyExecutor executor;long form;
    final List<String> ownedTables=new ArrayList<>();final List<Long> users=new ArrayList<>(),menus=new ArrayList<>(),roles=new ArrayList<>(),tenants=new ArrayList<>();
    TrustedActor platform,a,b;
    InvocationControl control(){return control(10_000);}
    InvocationControl control(long millis){var until=Instant.now().plusMillis(millis);return new InvocationControl(){public Instant deadline(){return until;}public boolean cancelled(){return Thread.currentThread().isInterrupted();}};}
    SqlQuery sql(String value){return new SqlQuery(value,Map.of(),20);}
    QueryContext context(String...keys){return new QueryContext(List.of(keys),null);}
    QueryResult read(String text,TrustedActor actor,String...keys){return runtime.preview(sql(text),actor,context(keys),control());}
    @BeforeAll void fixture() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"),"Missing DB_URL");
        String user=Objects.requireNonNull(System.getenv("DB_USERNAME"),"Missing DB_USERNAME");
        String password=Objects.requireNonNull(System.getenv("DB_PASSWORD"),"Missing DB_PASSWORD");
        boolean ci="true".equals(System.getenv("CI"));
        boolean localC="true".equals(System.getenv("AI_SQL_C_NATIVE"));
        boolean localPm="true".equals(System.getenv("AI_SQL_PM_NATIVE"));
        String expectedSchema;
        if(ci){
            assertFalse(localC || localPm,"CI must use its declared service container");
            assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3306/xinghui_oss04_ci?"));assertEquals("oss04_ci",user);expectedSchema="xinghui_oss04_ci";
        } else {
            assertTrue(localC ^ localPm,"Select exactly one explicitly assigned local QA mode");
            expectedSchema=localPm?"aiprompt_pm_20261018":"aiux_c_20261017";
            assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13319/"+expectedSchema+"?"));
            assertEquals(localPm?"aipr_pm":"aiux_c",user);
        }
        source=new DruidDataSource();source.setUrl(url);source.setUsername(user);source.setPassword(password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");source.setDbType(DbType.mysql);source.setInitialSize(1);source.setMinIdle(0);source.setMaxActive(2);source.setMaxWait(3000);
        var wall=new WallFilter();wall.setDbType(DbType.mysql);wall.setConfig(new WallConfig());source.setProxyFilters(List.of(new StatFilter(),wall));
        jdbc=new JdbcTemplate(source);
        assertEquals(expectedSchema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(ci?3306:13319,jdbc.queryForObject("SELECT @@port",Integer.class));
        String series=Objects.requireNonNull(System.getenv("OSS04_MYSQL_SERIES"),"Missing OSS04_MYSQL_SERIES");
        assertTrue(Set.of("5.7","8.0").contains(series));assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith(series+"."));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)>0);
        // Existing migrated structures are required. Do not create substitutes for missing application tables.
        for(String table:List.of("sys_user","sys_role","sys_menu","sys_tenant","sys_lowcode_form","sys_lowcode_form_version","sys_lowcode_record"))
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table));
        for(int i=0;i<2;i++){long id=base+80+i;String tenant=i==0?tenantA:tenantB;jdbc.update("INSERT INTO sys_tenant(id,tenant_id,company_name,status,expire_time,is_deleted) VALUES(?,?,?,'0','2099-01-01',0)",id,tenant,nonce);tenants.add(id);}
        int m=0;for(String permission:new TreeSet<>(grants)){long id=base+30+m++;jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,menu_type,perms,status,is_deleted) VALUES(?,?,'F',?,'0',0)",id,"SQL QA",permission);menus.add(id);}
        String[] tenantIds={"000000",tenantA,tenantB};
        for(int i=0;i<3;i++){
            long id=base+i,role=base+10+i;
            jdbc.update("INSERT INTO sys_user(user_id,tenant_id,username,password,status,is_deleted) VALUES(?,?,?,'disabled-native-fixture',1,0)",id,tenantIds[i],"sql_"+nonce+i);users.add(id);
            jdbc.update("INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,status,is_deleted) VALUES(?,?,?, ?,1,0)",role,tenantIds[i],"SQL QA",nonce+i);roles.add(role);
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
            for(long menu:menus)jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",role,menu);
        }
        platform=new TrustedActor(Long.toString(base),"000000",grants,null);a=new TrustedActor(Long.toString(base+1),tenantA,grants,null);b=new TrustedActor(Long.toString(base+2),tenantB,grants,null);
        create(orders,"order_no BIGINT PRIMARY KEY,id BIGINT,customer_id BIGINT,owner_id BIGINT,tenant_id VARCHAR(20),is_deleted INT,name VARCHAR(80) COMMENT '订单名称',amount DECIMAL(30,8),api_key VARCHAR(80)");
        create(customers,"customer_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),is_deleted INT,name VARCHAR(80) COMMENT '客户名称'");
        for(int i=0;i<3;i++){
            jdbc.update("INSERT INTO `"+customers+"` VALUES(?,?,0,?)",base+100+i,tenantIds[i],"customer"+i);
            jdbc.update("INSERT INTO `"+orders+"` VALUES(?,?,?,?,?,0,?,?,'NEVER_EXPOSE_SQL_SECRET')",base+200+i,900+i,base+100+i,base+i,tenantIds[i],"order"+i,new BigDecimal("1234567890123456789.12345678"));
        }
        jdbc.update("INSERT INTO `"+orders+"` VALUES(?,?,?,?,?,0,'other-owner',2,'NEVER_EXPOSE_SQL_SECRET')",base+210,910,base+101,base+2,tenantA);
        jdbc.update("INSERT INTO `"+orders+"` VALUES(?,?,?,?,?,1,'deleted',3,'NEVER_EXPOSE_SQL_SECRET')",base+211,911,base+101,base+1,tenantA);
        create(heavy,"n INT PRIMARY KEY");jdbc.batchUpdate("INSERT INTO `"+heavy+"` VALUES(?)",java.util.stream.IntStream.range(0,1000).mapToObj(i->new Object[]{i}).toList());
        String schema="{\"fields\":[{\"fieldKey\":\"subject\",\"label\":\"主题\",\"type\":\"text\",\"required\":false,\"maxLength\":100,\"options\":[],\"listVisible\":true,\"searchable\":true}]}";
        form=base+50;jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(?,?,?,'enabled',?,1,?)",form,"sql_"+nonce,"SQL QA",schema,platform.subjectId());
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(?,1,?,?)",form,schema,platform.subjectId());
        for(int i=0;i<3;i++)jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(?,?,1,?,?,?)",base+60+i,form,tenantIds[i],platform.subjectId(),"{\"subject\":\"lowcode"+i+"\"}");
        var actors=new SqlActorVerifier(jdbc,true);var fields=new LinkedHashMap<String,String>();for(String field:List.of("order_no","id","customer_id","name","amount"))fields.put(field,field);
        var policies=List.of(new SqlDatasetRegistry.TablePolicy("qa.tenant","scoped_orders","租户订单",orders,ScopeKind.TENANT,Set.of("qa:sql:read"),fields,"tenant_id",null,"is_deleted","QA_TENANT_V1"),
                new SqlDatasetRegistry.TablePolicy("qa.personal","personal_orders","个人订单",orders,ScopeKind.PERSONAL,Set.of("qa:sql:read"),fields,"tenant_id","owner_id","is_deleted","QA_PERSONAL_V1"));
        var registry=new SqlDatasetRegistry(jdbc,new LowcodeSchema(new ObjectMapper()),actors,policies);executor=new SqlReadOnlyExecutor(jdbc);
        runtime=new SceneQueryService(actors,registry,new SqlAstCompiler(),executor);
    }
    void create(String table,String ddl){jdbc.execute("CREATE TABLE `"+table+"` ("+ddl+") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");ownedTables.add(table);}
    @AfterAll void cleanup(){
        if(runtime!=null)runtime.close();if(jdbc!=null){
            if(form!=0){jdbc.update("DELETE FROM sys_lowcode_record WHERE form_id=?",form);jdbc.update("DELETE FROM sys_lowcode_form_version WHERE form_id=?",form);jdbc.update("DELETE FROM sys_lowcode_form WHERE id=? AND form_key=?",form,"sql_"+nonce);}
            for(long role:roles){jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?",role);jdbc.update("DELETE FROM sys_user_role WHERE role_id=?",role);jdbc.update("DELETE FROM sys_role WHERE role_id=?",role);}
            for(long menu:menus)jdbc.update("DELETE FROM sys_menu WHERE menu_id=?",menu);for(long user:users)jdbc.update("DELETE FROM sys_user WHERE user_id=?",user);for(long tenant:tenants)jdbc.update("DELETE FROM sys_tenant WHERE id=?",tenant);
            for(String table:ownedTables)jdbc.execute("DROP TABLE `"+table+"`");
        }if(source!=null)source.close();
    }
    @Test void platformDiscoversAndJoinsTwoOrdinaryNonRegisteredBusinessTables(){
        var catalog=runtime.catalog(new OptionQuery(nonce,1,100),platform,control());assertTrue(catalog.records().stream().anyMatch(row->row.datasetKey().equals("db."+orders)));
        var result=read("select o.order_no,c.name from "+orders+" o join "+customers+" c on o.customer_id=c.customer_id where o.amount>1",platform,"db."+orders,"db."+customers);
        assertEquals(1,result.rowCount());assertEquals("customer0",result.rows().get(0).get("name"));
    }
    @Test void platformGenericTableIsNotDelegatedToTenant(){assertThrows(RuntimeException.class,()->read("select order_no from "+orders,a,"db."+orders));}
    @Test void tenantScopeSurvivesOrAndSoftDelete(){var rows=read("select name from scoped_orders where amount>0 or name='deleted'",a,"qa.tenant");assertEquals(Set.of("order1","other-owner"),new HashSet<>(rows.rows().stream().map(r->r.get("name")).toList()));}
    @Test void explicitPersonalScopeExcludesOtherOwner(){var rows=read("select name from personal_orders where amount>0 or name='other-owner'",a,"qa.personal");assertEquals(List.of("order1"),rows.rows().stream().map(r->r.get("name")).toList());}
    @Test void joinSourcesEachKeepTheirOwnershipBoundary(){var rows=read("select t.order_no,p.name from scoped_orders t left join personal_orders p on t.order_no=p.order_no order by t.order_no",a,"qa.tenant","qa.personal");assertEquals(2,rows.rowCount());assertEquals(1,rows.rows().stream().filter(r->r.get("name")==null).count());}
    @Test void platformCompiledLowcodeSnapshotScopesToEachInvocationTenant() throws Exception {
        var ctx=context("lowcode."+form);var fixed=runtime.compile(sql("select subject from form_"+form),platform,ctx,control());
        fixed=new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(fixed),CompiledQuery.class);
        assertEquals("lowcode1",runtime.read(fixed,a,ctx,control()).rows().get(0).get("subject"));assertEquals("lowcode2",runtime.read(fixed,b,ctx,control()).rows().get(0).get("subject"));
    }
    @Test void normalizedLabelsAliasesAggregatesStarAndJoinRoundTrip() throws Exception {
        for(String statement:List.of("select name from "+orders,"select name as name from "+orders,"select name as renamed from "+orders,"select count(*) as total from "+orders,"select * from "+orders,
                "select o.name,c.name as customer_name from "+orders+" o join "+customers+" c on o.customer_id=c.customer_id")){
            var ctx=context("db."+orders,"db."+customers);var fixed=runtime.compile(sql(statement),platform,ctx,control());
            fixed=new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(fixed),CompiledQuery.class);assertEquals(1,runtime.read(fixed,platform,ctx,control()).rowCount());
        }
    }
    @Test void addedUnrelatedColumnDoesNotExpandPinnedStar(){var ctx=context("db."+orders);var fixed=runtime.compile(sql("select * from "+orders),platform,ctx,control());
        jdbc.execute("ALTER TABLE `"+orders+"` ADD extra_column VARCHAR(20)");try{var result=runtime.read(fixed,platform,ctx,control());assertFalse(result.columns().stream().anyMatch(c->c.name().equals("extra_column")));}finally{jdbc.execute("ALTER TABLE `"+orders+"` DROP extra_column");}}
    @Test void queryOnlyDependencyDriftFailsClosed(){var ctx=context("db."+orders);var fixed=runtime.compile(sql("select name from "+orders+" where id>0"),platform,ctx,control());
        jdbc.execute("ALTER TABLE `"+orders+"` MODIFY id VARCHAR(30)");try{assertThrows(SqlQueryException.class,()->runtime.read(fixed,platform,ctx,control()));}finally{jdbc.execute("ALTER TABLE `"+orders+"` MODIFY id BIGINT");}}
    @Test void scopeColumnDriftFailsClosed(){var ctx=context("qa.tenant");var fixed=runtime.compile(sql("select name from scoped_orders"),a,ctx,control());
        jdbc.execute("ALTER TABLE `"+orders+"` MODIFY tenant_id VARCHAR(20) COMMENT 'changed guard'");try{assertThrows(SqlQueryException.class,()->runtime.read(fixed,a,ctx,control()));}finally{jdbc.execute("ALTER TABLE `"+orders+"` MODIFY tenant_id VARCHAR(20) COMMENT ''");}}
    @Test void realNonIdPrimaryKeySupportsNineteenDigitCurrentRecord() throws Exception {
        var ctx=context("db."+orders);var fixed=runtime.compile(sql("select name from "+orders+" where order_no=:current_record_id"),platform,ctx,control());
        String ref="db1."+Base64.getUrlEncoder().withoutPadding().encodeToString(new ObjectMapper().writeValueAsBytes(Map.of("sourceKey","db."+orders,"key",Map.of("order_no",Long.toString(base+200)))));
        assertEquals("order0",runtime.read(fixed,platform,new QueryContext(ctx.datasetKeys(),new BusinessRef("database.record",ref)),control()).rows().get(0).get("name"));
        assertThrows(SqlQueryException.class,()->runtime.compile(sql("select name from "+orders+" where id=:current_record_id"),platform,ctx,control()));
        assertThrows(SqlQueryException.class,()->runtime.read(fixed,platform,ctx,control()));
    }
    @Test void liveRoleRevocationRejectsAlreadyCompiledSnapshot(){var ctx=context("qa.tenant");var fixed=runtime.compile(sql("select name from scoped_orders"),a,ctx,control());long role=base+11;
        jdbc.update("UPDATE sys_role SET status=0 WHERE role_id=?",role);try{assertThrows(AiRuntimeException.class,()->runtime.read(fixed,a,ctx,control()));}finally{jdbc.update("UPDATE sys_role SET status=1 WHERE role_id=?",role);}}
    @Test void liveTenantDisableRejectsBackgroundRead(){var ctx=context("qa.tenant");var fixed=runtime.compile(sql("select name from scoped_orders"),a,ctx,control());
        jdbc.update("UPDATE sys_tenant SET status='1' WHERE tenant_id=?",tenantA);try{assertThrows(AiRuntimeException.class,()->runtime.read(fixed,a,ctx,control()));}finally{jdbc.update("UPDATE sys_tenant SET status='0' WHERE tenant_id=?",tenantA);}}
    @Test void unionSubquerySecretsSystemAndWritesNeverPassStaticReview(){for(String query:List.of("select order_no from "+orders+" union select customer_id from "+customers,"select name from (select name from "+orders+") x",
            "select api_key from "+orders,"select name from "+orders+" where api_key='x'","select password from sys_user","delete from "+orders,"select name from mysql.user")){
        var result=runtime.validateGeneratedSql(sql(query),platform,context("db."+orders,"db."+customers),control());assertFalse(result.valid());assertNull(result.normalizedSql());}}
    @Test void realReadOnlyTransactionRejectsDmlAtDatabaseThenSameConnectionRecovers(){
        source.setMaxActive(1);try{
            long[] connection={0};executor.transaction(control(),()->{connection[0]=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO `"+heavy+"` VALUES(1001)"));
                var cause=failure.getMostSpecificCause();assertInstanceOf(SQLException.class,cause);var sql=(SQLException)cause;
                assertTrue(sql.getErrorCode()==1792||"25006".equals(sql.getSQLState()),"Database must enforce RO independently of AST");return true;});
            assertEquals(connection[0],jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertEquals(1,jdbc.update("INSERT INTO `"+heavy+"` VALUES(1001)"));jdbc.update("DELETE FROM `"+heavy+"` WHERE n=1001");
        }finally{source.setMaxActive(2);}
    }
    @Test void innerReadOnlyNeverJoinsOuterWritableTransaction(){var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
        tx.execute(status->{long outer=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
            executor.transaction(control(),()->{assertNotEquals(outer,jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));return true;});
            assertEquals(1,jdbc.update("INSERT INTO `"+heavy+"` VALUES(1002)"));status.setRollbackOnly();return true;});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM `"+heavy+"` WHERE n=1002",Integer.class));}
    @Test void timeoutCancelsActualComputationAndPoolStillReads(){
        String query="select count(*) as total from "+heavy+" a join "+heavy+" b on a.n>=b.n join "+heavy+" c on b.n>=c.n join "+heavy+" d on c.n>=d.n";
        var failure=assertThrows(RuntimeException.class,()->runtime.preview(sql(query),platform,context("db."+heavy),control(1500)));
        assertTrue(failure instanceof SqlQueryException q && q.issue()==QueryIssue.QUERY_TIMEOUT || failure instanceof AiRuntimeException a && (a.code()==ErrorCode.DEADLINE_EXCEEDED||a.code()==ErrorCode.CANCELLED));
        assertEquals(1,read("select name from "+orders,platform,"db."+orders).rowCount());
    }
    @Test void exactDecimalsNullParametersAndLikeEscapeExecuteThroughWall(){
        var params=new HashMap<String,Object>();params.put("fallback",null);
        var query=new SqlQuery("select amount,coalesce(name,:fallback) as label from "+orders+" where name like 'order!_%' escape '!' or name not like 'none%'",params,20);
        var result=runtime.preview(query,platform,context("db."+orders),control());assertEquals(1,result.rowCount());assertEquals(new BigDecimal("1234567890123456789.12345678"),result.rows().get(0).get("amount"));
    }
}
