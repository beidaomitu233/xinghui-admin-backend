CREATE TABLE sys_ai_call_feedback (
    call_id VARCHAR(36) NOT NULL,
    user_scope CHAR(64) NOT NULL,
    score TINYINT NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (call_id,user_scope),
    KEY idx_ai_feedback_time (updated_at),
    CONSTRAINT fk_ai_feedback_call FOREIGN KEY (call_id) REFERENCES sys_ai_call_log(call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE sys_menu SET menu_name='AI统计' WHERE menu_type='C' AND path='/ai/requests' AND is_deleted=0;
UPDATE sys_menu SET menu_name='AI统计' WHERE menu_id=202610160180000003 AND is_deleted=0;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,create_by,create_time,is_deleted)
SELECT 202611010600000001,'导入导出',menu_id,50,'','',1,'G','1','0','','',1,NOW(),0 FROM sys_menu
WHERE perms='system:user:list' AND menu_type='C' AND is_deleted=0 LIMIT 1;
UPDATE sys_menu a JOIN sys_menu g ON g.menu_id=202611010600000001 SET a.parent_id=g.menu_id
WHERE a.perms IN ('system:user:import','system:user:export') AND a.menu_type='F' AND a.is_deleted=0;
