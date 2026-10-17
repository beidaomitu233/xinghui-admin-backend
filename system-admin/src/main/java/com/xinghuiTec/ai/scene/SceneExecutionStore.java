package com.xinghuiTec.ai.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.application.AiApplicationPolicy;
import com.xinghuiTec.ai.application.AiApplicationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.xinghuiTec.ai.scene.SceneFailure.require;

/** B-owned execution-ledger compatibility storage. There is no user-facing application publication. */
@Repository
public class SceneExecutionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SceneExecutionStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper.copy();}
    String createApplication(SceneStore.Head scene,AiApplicationPolicy policy,String actor) {
        var keys=new GeneratedKeyHolder();
        String entry=scene.kind()==SceneDtos.Kind.RECORD_SUMMARY?"lowcode.record.summary/"+scene.formId():"scene/"+scene.key();
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO sys_ai_application(name,purpose,owner_id,entry_key,draft_json,enabled) VALUES(?,?,?,?,?,?)",new String[]{"app_id"});
            s.setString(1,scene.name());s.setString(2,scene.purpose());s.setLong(3,Long.parseLong(actor));s.setString(4,entry);s.setString(5,json(policy));s.setBoolean(6,scene.enabled());return s;},keys);
        require(keys.getKey()!=null,503,"DEPENDENCY_UNAVAILABLE");return keys.getKey().toString();
    }
    String append(SceneStore.Head scene,AiApplicationPolicy policy,SceneDtos.Definition definition,String actor) {
        require(scene.appId()!=null,503,"STATE_UNAVAILABLE");
        var versions=jdbc.query("SELECT version_number FROM sys_ai_application_version WHERE app_id=? ORDER BY version_number DESC LIMIT 1 FOR UPDATE",(rs,n)->rs.getInt(1),scene.appId());
        int number=versions.isEmpty()?1:Math.addExact(versions.get(0),1);
        var keys=new GeneratedKeyHolder();
        var legacy=scene.legacyBaseVersion()==null?null:snapshot(scene.legacyBaseVersion());
        var snapshot=new AiApplicationService.Snapshot(policy,legacy==null?List.of():legacy.resources(),List.of());
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO sys_ai_application_version(app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by,snapshot_source) VALUES(?,?,?,?,NULL,?,'SCENE_SAVE')",new String[]{"app_version_id"});
            s.setLong(1,Long.parseLong(scene.appId()));s.setInt(2,number);s.setInt(3,scene.revision()+1);s.setString(4,json(snapshot));s.setLong(5,Long.parseLong(actor));return s;},keys);
        require(keys.getKey()!=null,503,"DEPENDENCY_UNAVAILABLE");String id=keys.getKey().toString();
        jdbc.update("INSERT INTO sys_ai_application_cursor(app_version_id,next_index) VALUES(?,0)",id);
        for(var binding:policy.models())jdbc.update("INSERT INTO sys_ai_application_reference(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,'MODEL',?,?)",id,binding.model().modelRef(),binding.model().configVersion());
        if(legacy!=null){
            if(legacy.policy().template()!=null)jdbc.update("INSERT INTO sys_ai_application_reference(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,'TEMPLATE',?,?)",id,legacy.policy().template().templateId(),legacy.policy().template().templateVersionId());
            for(var resource:new java.util.HashSet<>(legacy.resources()))jdbc.update("INSERT INTO sys_ai_application_reference(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,'RESOURCE',?,?)",id,resource.resourceId(),resource.resourceVersionId());
        }
        require(jdbc.update("UPDATE sys_ai_application SET name=?,purpose=?,draft_json=?,draft_revision=draft_revision+1,revision=revision+1,"
                +"published_version_id=?,enabled=?,update_time=CURRENT_TIMESTAMP(3) WHERE app_id=?",definition.name(),definition.purpose(),json(policy),id,scene.enabled(),scene.appId())==1,503,"STATE_UNAVAILABLE");
        return id;
    }
    public AiApplicationPolicy policy(String appVersion) {
        return snapshot(appVersion).policy();
    }
    public AiApplicationService.Snapshot snapshot(String appVersion) {
        var rows=jdbc.query("SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=?",(rs,n)->rs.getString(1),appVersion);
        require(rows.size()==1,404,"NOT_FOUND");
        try{return mapper.readValue(rows.get(0),AiApplicationService.Snapshot.class);}
        catch(Exception corrupt){throw new SceneFailure(503,"STATE_UNAVAILABLE");}
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception invalid){throw new SceneFailure(400,"INVALID_INPUT");}}
}
