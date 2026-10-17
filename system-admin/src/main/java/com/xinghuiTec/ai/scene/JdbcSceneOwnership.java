package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiSceneOwnership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Mapping lookups only: no SceneService, C service, provider, renderer or secret dependency. */
@Service
public class JdbcSceneOwnership implements AiSceneOwnership {
    private final JdbcTemplate jdbc;
    public JdbcSceneOwnership(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean ownsApplication(String applicationId) {
        decimal(applicationId);
        // Locking reads observe mappings committed while an outer RR transaction waited for the reference guard.
        return !jdbc.query("SELECT scene_key FROM sys_ai_scene WHERE app_id=? FOR UPDATE",(rs,n)->rs.getString(1),applicationId).isEmpty();
    }
    public boolean ownsTemplate(String templateId) {
        decimal(templateId);
        return !jdbc.query("SELECT scene_version_id FROM sys_ai_scene_version WHERE legacy_template_id=? ORDER BY scene_version_id FOR UPDATE",(rs,n)->rs.getLong(1),templateId).isEmpty();
    }
    public void requireApplicationWritable(String applicationId) {if(ownsApplication(applicationId))throw new SceneFailure(409,"SCENE_MANAGED");}
    public void requireTemplateWritable(String templateId) {if(ownsTemplate(templateId))throw new SceneFailure(409,"SCENE_MANAGED");}
    private static void decimal(String value) {
        SceneFailure.require(value!=null && value.matches("[1-9][0-9]{0,18}"),400,"INVALID_INPUT");
        try{Long.parseLong(value);}catch(NumberFormatException invalid){throw new SceneFailure(400,"INVALID_INPUT");}
    }
}
