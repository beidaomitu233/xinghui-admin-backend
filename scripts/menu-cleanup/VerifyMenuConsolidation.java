import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.*;

/** Opt-in UI05 migration verification. Assigned QA schema only; credentials never logged. */
public class VerifyMenuConsolidation {
    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    static long number(Connection db, String sql) throws Exception {
        try (var s=db.createStatement();var r=s.executeQuery(sql)) { r.next();return r.getLong(1); }
    }
    static List<String> rows(Connection db, String sql) throws Exception {
        var values=new ArrayList<String>();
        try(var s=db.createStatement();var r=s.executeQuery(sql)) {
            while(r.next()) {
                var row=new ArrayList<String>();
                for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.add(String.valueOf(r.getString(i)));
                values.add(String.join("|",row));
            }
        }
        return values;
    }
    static Flyway flyway(String url,String password,String root,boolean baseline) {
        var config=Flyway.configure().dataSource(url,"qa_bulk",password)
            .locations("filesystem:"+root+"/migration","filesystem:"+root+"/optional-migration")
            .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false);
        if(baseline) config.target("20261012.160");
        return config.load();
    }
    static List<String> pages(Connection db) throws Exception {
        return rows(db,"SELECT m.menu_id,m.component,m.perms,m.is_frame,m.visible,m.status,m.is_deleted,"
            + "CASE WHEN m.path LIKE '/%' THEN m.path WHEN m.parent_id=0 THEN CONCAT('/',m.path) "
            + "ELSE CONCAT('/',TRIM(LEADING '/' FROM p.path),'/',m.path) END "
            + "FROM sys_menu m LEFT JOIN sys_menu p ON p.menu_id=m.parent_id WHERE m.menu_type='C' ORDER BY m.menu_id");
    }
    static void verifyFinal(Connection db,Flyway migration) throws Exception {
        require(rows(db,"SELECT menu_name FROM sys_menu WHERE parent_id=0 AND menu_type='M' AND is_deleted=0 ORDER BY order_num").equals(List.of("\u7cfb\u7edf\u7ba1\u7406","\u901a\u7528\u80fd\u529b","\u8fd0\u884c\u7ef4\u62a4")),"Wrong root groups");
        require(number(db,"SELECT COUNT(*) FROM sys_menu WHERE menu_type='C' AND parent_id NOT IN (1,3,2)")==0,"Leaves not flattened");
        require(number(db,"SELECT COUNT(*) FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_id IS NULL")==0,"Dangling menu grant");
        migration.migrate();migration.validate();
        require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")==0,"Failed migration record");
    }
    public static void main(String[] args) throws Exception {
        String mode=args[0],schema=System.getenv("UI05_DB_NAME"),root=System.getenv("UI05_MIGRATION_ROOT");
        require(Set.of("xinghui_bulk_qa_20261011","ui05_empty_20261013").contains(schema),"Unassigned schema");
        require(Set.of("upgrade","empty","verify").contains(mode),"Unknown verification mode");
        int port="ui05_empty_20261013".equals(schema) ? 13318 : 13317;
        require(!mode.equals("empty") || port==13318,"Empty mode requires assigned fresh schema");
        require(!mode.equals("upgrade") || port==13317,"Upgrade mode requires assigned existing schema");
        String password=System.getenv("DB_PASSWORD");
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try(var db=DriverManager.getConnection(url,"qa_bulk",password)) {
            require(number(db,"SELECT @@port")==port,"Wrong port");
            try(var s=db.createStatement();var r=s.executeQuery("SELECT CURRENT_USER(),@@datadir")) {
                r.next();require("qa_bulk@127.0.0.1".equals(r.getString(1)),"Wrong account");
                require(r.getString(2).replace('\\','/').toLowerCase(Locale.ROOT).endsWith(port==13317 ? "/.qa-local/20261011/mysql-data/" : "/.qa-local/20261012-empty/mysql-data/"),"Wrong datadir");
            }
            if(mode.equals("empty")) require(number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")==0,"Assigned schema must start empty");
            if(mode.equals("verify")) {
                require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261013.170' AND success=1")==1,"V170 missing");
                verifyFinal(db,flyway(url,password,root,false));
                require(number(db,"SELECT COUNT(*) FROM sys_role WHERE role_id IN (991050201,991050202)")==0,"Fixture remains");
                System.out.println("UI05 final state verified: three groups; direct leaves; no dangling grants; strict validate/re-run; fixtures absent.");
                return;
            }
            var baseline=flyway(url,password,root,true);baseline.migrate();baseline.validate();
            require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261013.170'")==0,"V170 already applied; use a fresh assigned upgrade fixture");
            var historical=rows(db,"SELECT script,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND success=1 ORDER BY installed_rank");
            var beforePages=pages(db);
            var beforeButtons=rows(db,"SELECT menu_id,parent_id,path,component,perms FROM sys_menu WHERE menu_type='F' ORDER BY menu_id");
            require(number(db,"SELECT COUNT(*) FROM sys_role WHERE role_id IN (991050201,991050202)")==0,"Fixture IDs occupied");
            try(var s=db.createStatement()) {
                s.executeUpdate("INSERT INTO sys_role(role_id,role_name,role_key,create_by) VALUES (991050201,'UI05 child owner','ui05_child','ui05_20261013'),(991050202,'UI05 directory only','ui05_directory','ui05_20261013')");
                s.executeUpdate("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (991050201,106),(991050202,4)");
            }
            try {
                var grants=rows(db,"SELECT rm.role_id,rm.menu_id FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_type IN ('C','F') ORDER BY rm.role_id,rm.menu_id");
                var counts=new LinkedHashMap<String,Long>();
                for(String table:List.of("sys_user","sys_role","sys_config","sys_oss","sys_business_config","sys_lowcode_form","sys_lowcode_record")) counts.put(table,number(db,"SELECT COUNT(*) FROM "+table));
                var migration=flyway(url,password,root,false);migration.migrate();migration.validate();
                require(beforePages.equals(pages(db)),"Page URL/component/permission changed");
                require(beforeButtons.equals(rows(db,"SELECT menu_id,parent_id,path,component,perms FROM sys_menu WHERE menu_type='F' ORDER BY menu_id")),"Button metadata changed");
                require(grants.equals(rows(db,"SELECT rm.role_id,rm.menu_id FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_type IN ('C','F') ORDER BY rm.role_id,rm.menu_id")),"Page/button grants expanded");
                for(var item:counts.entrySet()) require(item.getValue()==number(db,"SELECT COUNT(*) FROM "+item.getKey()),"Business data changed: "+item.getKey());
                require(historical.equals(rows(db,"SELECT script,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND success=1 AND version<>'20261013.170' ORDER BY installed_rank")),"Historical checksum changed");
                verifyFinal(db,migration);
                require(number(db,"SELECT COUNT(*) FROM sys_role_menu WHERE role_id=991050201 AND menu_id=3")==1,"Child owner missing new directory");
                require(number(db,"SELECT COUNT(*) FROM sys_role_menu WHERE role_id=991050202")==0,"Directory-only role acquired permission");
                require(number(db,"SELECT COUNT(*) FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_id IS NULL")==0,"Dangling menu grant");
                migration.migrate();migration.validate();
                require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")==0,"Failed migration record");
                System.out.println("UI05 "+mode+" passed: three groups; stable URLs/components/permissions/buttons/grants/data/checksums; child-only and directory-only roles; strict validate and re-run.");
            } finally {
                try(var s=db.createStatement()) {
                    s.executeUpdate("DELETE FROM sys_role_menu WHERE role_id IN (991050201,991050202)");
                    s.executeUpdate("DELETE FROM sys_role WHERE role_id IN (991050201,991050202) AND create_by='ui05_20261013'");
                }
                require(number(db,"SELECT COUNT(*) FROM sys_role WHERE role_id IN (991050201,991050202)")==0,"Fixture cleanup failed");
            }
        }
    }
}
