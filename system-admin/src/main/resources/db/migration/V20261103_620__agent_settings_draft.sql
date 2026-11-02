CREATE TABLE sys_ai_agent_settings_draft (
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 definition_json LONGTEXT NOT NULL,
 FOREIGN KEY (scene_key) REFERENCES sys_ai_scene(scene_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
