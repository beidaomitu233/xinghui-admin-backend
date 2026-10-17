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
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene WHERE app_id=?",Integer.class,applicationId);
        return count!=null && count>0;
    }
    public boolean ownsTemplate(String templateId) {
        decimal(templateId);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version WHERE legacy_template_id=?",Integer.class,templateId);
        return count!=null && count>0;
    }
    public void requireApplicationWritable(String applicationId) {if(ownsApplication(applicationId))throw new SceneFailure(409,"SCENE_MANAGED");}
    public void requireTemplateWritable(String templateId) {if(ownsTemplate(templateId))throw new SceneFailure(409,"SCENE_MANAGED");}
    private static void decimal(String value) {
        SceneFailure.require(value!=null && value.matches("[1-9][0-9]{0,18}"),400,"INVALID_INPUT");
        try{Long.parseLong(value);}catch(NumberFormatException invalid){throw new SceneFailure(400,"INVALID_INPUT");}
    }
}
