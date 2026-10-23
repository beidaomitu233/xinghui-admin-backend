import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.*;

/** Opt-in native MySQL upgrade/empty-schema verification; credentials stay in environment. */
public class VerifyMenuCleanup {
    static final List<String> REMOVED = List.of("sys_site_config", "sys_site_navigation", "sys_site_banner",
        "sys_site_product", "sys_site_api_doc", "sys_download", "sys_page", "example_material");
    static final String PERMS = "^(example:material|system:(download|page|siteConfig|navigation|banner|ecosystem|apiDoc)):";
    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    static long number(Connection db, String sql) throws Exception {
        try (var s=db.createStatement(); var r=s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    static Map<String,Integer> checksums(Connection db) throws Exception {
        var map=new LinkedHashMap<String,Integer>();
        try(var s=db.createStatement(); var r=s.executeQuery("SELECT script,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND success=1")) {
            while(r.next()) map.put(r.getString(1),r.getInt(2));
        }
        return map;
    }
    static Flyway flyway(String url, String password, String root) {
        return Flyway.configure().dataSource(url,"qa_bulk",password)
            .locations("filesystem:"+root+"/migration","filesystem:"+root+"/optional-migration")
            .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
    }
    public static void main(String[] args) throws Exception {
        String schema=System.getenv("UI04_DB_NAME");
        require(Set.of("xinghui_bulk_qa_20261011","ui04_empty_20261012").contains(schema),"Unassigned schema");
        String mode=args[0], password=System.getenv("DB_PASSWORD");
        int port=schema.equals("ui04_empty_20261012") ? 13318 : 13317;
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try(var db=DriverManager.getConnection(url,"qa_bulk",password)) {
            require(number(db,"SELECT @@port")==port,"Wrong port");
            try(var s=db.createStatement(); var r=s.executeQuery("SELECT DATABASE(),CURRENT_USER(),@@datadir")) {
                r.next(); require(schema.equals(r.getString(1)) && "qa_bulk@127.0.0.1".equals(r.getString(2)),"Wrong identity");
                require(r.getString(3).replace('\\','/').toLowerCase(Locale.ROOT).endsWith(port==13317 ? "/.qa-local/20261011/mysql-data/" : "/.qa-local/20261012-empty/mysql-data/"),"Wrong datadir");
            }
            Map<String,Integer> before=Map.of();
            Map<String,Long> preserved=new LinkedHashMap<>();
            if(mode.equals("upgrade")) {
                var baseline=flyway(url,password,System.getenv("UI04_BEFORE_ROOT"));
                baseline.migrate(); baseline.validate();
                require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")==19,"Upgrade must start at 19 successful records");
                before=checksums(db);
                for(String table:REMOVED) require(number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"+table+"'")==1,"Missing baseline table "+table);
                for(String table:List.of("sys_user","sys_role","sys_config","sys_oss")) preserved.put(table,number(db,"SELECT COUNT(*) FROM "+table));
            } else {
                require(mode.equals("empty"),"Unknown mode");
                require(number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")==0,"Empty schema required");
            }
            var migration=flyway(url,password,System.getenv("UI04_AFTER_ROOT"));
            migration.migrate(); migration.validate();
            for(var old:before.entrySet()) require(Objects.equals(old.getValue(),checksums(db).get(old.getKey())),"Changed versioned checksum "+old.getKey());
            for(var old:preserved.entrySet()) require(number(db,"SELECT COUNT(*) FROM "+old.getKey())==old.getValue(),"Changed preserved data "+old.getKey());
            for(String table:REMOVED) require(number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"+table+"'")==0,"Recreated removed table "+table);
            require(number(db,"SELECT COUNT(*) FROM sys_menu WHERE perms REGEXP '"+PERMS+"'")==0,"Legacy permissions remain");
            require(number(db,"SELECT COUNT(*) FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.menu_id IS NULL")==0,"Dangling role/menu grant");
            require(number(db,"SELECT COUNT(*) FROM sys_menu WHERE menu_id=301 AND path='/infra/notice' AND parent_id=202610120140000001")==1,"Notice deep link changed");
            require(number(db,"SELECT COUNT(*) FROM sys_menu WHERE menu_id=105 AND path='/system/notification-template' AND parent_id=202610120140000001")==1,"Template deep link changed");
            require(number(db,"SELECT COUNT(*) FROM sys_menu WHERE component='system/news/index' AND path='/system/news' AND parent_id=202610120140000002")==1,"News removed or moved URL");
            require(number(db,"SELECT COUNT(*) FROM sys_role_menu parent WHERE parent.menu_id IN (202610120140000001,202610120140000002) AND NOT EXISTS (SELECT 1 FROM sys_role_menu child JOIN sys_menu m ON m.menu_id=child.menu_id WHERE child.role_id=parent.role_id AND m.parent_id=parent.menu_id)")==0,"Unowned directory granted");
            require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")==0,"Failed migration record");
            require(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261012.140' AND success=1")==1,"V140 not applied");
            // Re-run and validate both locations: the repeatable tombstone must never resurrect material.
            migration.migrate(); migration.validate();
            require(number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='example_material'")==0,"Repeatable recreated material");
            System.out.println("UI04 "+mode+" passed: 8 removed tables, legacy permissions and grants absent; surviving data/deep links preserved; versioned checksums unchanged; repeatable restart validated.");
        }
    }
}
