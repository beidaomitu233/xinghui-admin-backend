package com.xinghuiTec.ai.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.CompiledScene;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.xinghuiTec.ai.scene.SceneFailure.require;

/** Stable scenes and immutable compiled versions; no template/resource/business-table reads. */
@Repository
public class SceneStore {
    public record Head(String key,String name,String purpose,SceneDtos.Kind kind,String formId,SceneDtos.Source source,
                       String compatibilityStatus,String appId,String legacyBaseVersion,String currentVersion,boolean enabled,int revision,
                       String createdBy,Instant updatedAt) {}
    public record Version(String id,String sceneKey,int number,int savedRevision,String appVersion,
                          SceneDtos.Definition definition,CompiledScene compiled,SceneDtos.Source source,Instant createdAt) {
        @Override public String toString(){return "SceneVersion[REDACTED]";}
    }
    public record Mutation(String fingerprint,SceneDtos.SaveResult result) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SceneStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper.copy();}
    Head head(String key,boolean lock) {
        key(key);var rows=jdbc.query("SELECT * FROM sys_ai_scene WHERE scene_key=?"+(lock?" FOR UPDATE":""),(rs,n)->head(rs),key);
        require(rows.size()==1,404,"NOT_FOUND");return rows.get(0);
    }
    List<Head> page(int page,int size,String search) {
        paging(page,size);require(search==null || search.length()<=128,400,"INVALID_INPUT");
        String where=search==null || search.isBlank()?"":" WHERE LOCATE(?,name)>0";
        var args=new ArrayList<Object>();if(!where.isEmpty())args.add(search);args.add(size);args.add((page-1L)*size);
        return List.copyOf(jdbc.query("SELECT * FROM sys_ai_scene"+where+" ORDER BY update_time DESC,scene_key LIMIT ? OFFSET ?",(rs,n)->head(rs),args.toArray()));
    }
    long count(String search) {
        String where=search==null || search.isBlank()?"":" WHERE LOCATE(?,name)>0";
        Long count=where.isEmpty()?jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene",Long.class)
                :jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene"+where,Long.class,search);
        return count==null?0:count;
    }
    List<Head> candidates(SceneDtos.Kind kind,String formId) {
        var args=new ArrayList<Object>();String sql="SELECT * FROM sys_ai_scene WHERE enabled=TRUE";
        if(kind!=null){sql+=" AND scene_kind=?";args.add(kind.name());}
        if(formId!=null){decimal(formId);sql+=" AND form_id=?";args.add(formId);}
        return List.copyOf(jdbc.query(sql+" ORDER BY scene_key LIMIT 100",(rs,n)->head(rs),args.toArray()));
    }
    void create(String key,SceneDtos.Create input,String actor) {
        if(input.kind()==SceneDtos.Kind.RECORD_SUMMARY){
            Integer existing=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene WHERE scene_kind='RECORD_SUMMARY' AND form_id=?",Integer.class,input.formId());
            require(existing!=null && existing==0,409,"VERSION_CONFLICT");
        }
        jdbc.update("INSERT INTO sys_ai_scene(scene_key,name,purpose,scene_kind,form_id,source,created_by) VALUES(?,?,?,?,?,'NATIVE',?)",
                key,input.name(),input.purpose(),input.kind().name(),input.formId(),actor);
    }
    void attachApplication(Head head,String appId) {
        require(jdbc.update("UPDATE sys_ai_scene SET app_id=? WHERE scene_key=? AND app_id IS NULL AND revision=?",appId,head.key,head.revision)==1,409,"VERSION_CONFLICT");
    }
    Version version(String id) {
        decimal(id);var rows=jdbc.query("SELECT * FROM sys_ai_scene_version WHERE scene_version_id=?",(rs,n)->version(rs),id);
        require(rows.size()==1,404,"NOT_FOUND");return rows.get(0);
    }
    Version applicationVersion(String id) {
        decimal(id);var rows=jdbc.query("SELECT * FROM sys_ai_scene_version WHERE app_version_id=?",(rs,n)->version(rs),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    Version insertVersion(Head head,String appVersion,SceneDtos.Definition definition,CompiledScene compiled,String actor) {
        Integer number=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM sys_ai_scene_version WHERE scene_key=?",Integer.class,head.key);
        var keys=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO sys_ai_scene_version(scene_key,version_number,saved_revision,app_version_id,definition_json,compiled_json,source,created_by) VALUES(?,?,?,?,?,?,'NATIVE',?)",new String[]{"scene_version_id"});
            s.setString(1,head.key);s.setInt(2,number);s.setInt(3,head.revision+1);s.setLong(4,Long.parseLong(appVersion));
            s.setString(5,json(definition));s.setString(6,json(compiled));s.setLong(7,Long.parseLong(actor));return s;},keys);
        require(keys.getKey()!=null,503,"DEPENDENCY_UNAVAILABLE");return version(keys.getKey().toString());
    }
    Version insertRoutingVersion(Head head,Version previous,String appVersion,SceneDtos.Definition definition,String actor) {
        Integer number=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM sys_ai_scene_version WHERE scene_key=?",Integer.class,head.key);
        var keys=new GeneratedKeyHolder();
        jdbc.update(c->{var statement=c.prepareStatement("INSERT INTO sys_ai_scene_version(scene_key,version_number,saved_revision,app_version_id,definition_json,compiled_json,source,legacy_template_id,legacy_template_version_id,created_by) SELECT scene_key,?,?,?,?,compiled_json,source,legacy_template_id,legacy_template_version_id,? FROM sys_ai_scene_version WHERE scene_version_id=?",new String[]{"scene_version_id"});
            statement.setInt(1,number);statement.setInt(2,head.revision+1);statement.setLong(3,Long.parseLong(appVersion));statement.setString(4,json(definition));statement.setLong(5,Long.parseLong(actor));statement.setLong(6,Long.parseLong(previous.id()));return statement;},keys);
        require(keys.getKey()!=null,503,"STATE_UNAVAILABLE");return version(keys.getKey().toString());
    }
    Head activate(Head head,Version version,SceneDtos.Definition definition) {
        require(jdbc.update("UPDATE sys_ai_scene SET name=?,purpose=?,current_version_id=?,revision=revision+1,"
                +"compatibility_status='MAPPED',update_time=CURRENT_TIMESTAMP(3) WHERE scene_key=? AND revision=?",definition.name(),definition.purpose(),version.id,head.key,head.revision)==1,409,"VERSION_CONFLICT");
        return head(head.key,false);
    }
    Head enabled(Head head,boolean enabled) {
        require(jdbc.update("UPDATE sys_ai_scene SET enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP(3) WHERE scene_key=? AND revision=?",enabled,head.key,head.revision)==1,409,"VERSION_CONFLICT");
        if(head.appId!=null)jdbc.update("UPDATE sys_ai_application SET enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP(3) WHERE app_id=?",enabled,head.appId);
        return head(head.key,false);
    }
    Mutation mutation(String key,String id) {
        var rows=jdbc.query("SELECT input_fingerprint,result_json FROM sys_ai_scene_mutation WHERE scene_key=? AND client_mutation_id=?",
                (rs,n)->new Mutation(rs.getString(1),read(rs.getString(2),SceneDtos.SaveResult.class)),key,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    void mutation(String key,String id,String fingerprint,SceneDtos.SaveResult result) {
        jdbc.update("INSERT INTO sys_ai_scene_mutation(scene_key,client_mutation_id,input_fingerprint,result_json) VALUES(?,?,?,?)",key,id,fingerprint,json(result));
    }
    void bindRequest(String requestId,String key,String version) {
        require(jdbc.update("UPDATE sys_ai_request SET scene_key=?,scene_version_id=? WHERE request_id=? AND (scene_key IS NULL OR (scene_key=? AND scene_version_id=?))",
                key,version,requestId,key,version)==1,409,"VERSION_CONFLICT");
    }
    Version requestVersion(String requestId) {
        var rows=jdbc.query("SELECT v.* FROM sys_ai_scene_version v JOIN sys_ai_request r ON r.scene_version_id=v.scene_version_id WHERE r.request_id=?",(rs,n)->version(rs),requestId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Head head(ResultSet rs)throws SQLException {
        return new Head(rs.getString("scene_key"),rs.getString("name"),rs.getString("purpose"),SceneDtos.Kind.valueOf(rs.getString("scene_kind")),
                rs.getString("form_id"),SceneDtos.Source.valueOf(rs.getString("source")),rs.getString("compatibility_status"),rs.getString("app_id"),rs.getString("legacy_base_app_version_id"),rs.getString("current_version_id"),
                rs.getBoolean("enabled"),rs.getInt("revision"),rs.getString("created_by"),instant(rs,"update_time"));
    }
    private Version version(ResultSet rs)throws SQLException {
        String definition=rs.getString("definition_json"),compiled=rs.getString("compiled_json");
        return new Version(rs.getString("scene_version_id"),rs.getString("scene_key"),rs.getInt("version_number"),rs.getInt("saved_revision"),
                rs.getString("app_version_id"),definition==null?null:read(definition,SceneDtos.Definition.class),compiled==null?null:read(compiled,CompiledScene.class),
                SceneDtos.Source.valueOf(rs.getString("source")),instant(rs,"create_time"));
    }
    String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception invalid){throw new SceneFailure(400,"INVALID_INPUT");}}
    <T>T read(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception corrupt){throw new SceneFailure(503,"STATE_UNAVAILABLE");}}
    private static Instant instant(ResultSet rs,String column)throws SQLException{Timestamp t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    static void key(String key){require(key!=null && key.matches("[a-z][a-z0-9_.:-]{0,127}"),400,"INVALID_INPUT");}
    static void decimal(String id){require(id!=null && id.matches("[1-9][0-9]{0,18}"),400,"INVALID_INPUT");try{Long.parseLong(id);}catch(NumberFormatException invalid){throw new SceneFailure(400,"INVALID_INPUT");}}
    static void mutationId(String id){require(id!=null && id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"),400,"INVALID_INPUT");}
    static void paging(int page,int size){require(page>0 && page<=100000 && size>0 && size<=100,400,"INVALID_INPUT");}
}
