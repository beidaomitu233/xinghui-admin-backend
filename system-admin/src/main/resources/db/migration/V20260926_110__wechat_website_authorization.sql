-- WX-01 website application metadata only; no secret value, identity or login state.
CREATE TABLE sys_wechat_website_config (
  id tinyint NOT NULL PRIMARY KEY,
  enabled tinyint(1) NOT NULL DEFAULT 0,
  client_id varchar(64) NOT NULL DEFAULT '',
  secret_env_name varchar(128) NOT NULL DEFAULT '',
  callback_uri varchar(2048) NOT NULL DEFAULT '',
  revision int NOT NULL DEFAULT 0,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_wechat_website_config (id) VALUES (1);
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted) VALUES
 (120,'微信网站授权',1,12,'wechat','system/wechat/index',1,'C','0','0','system:wechat:query','carbon:qr-code',0),
 (1201,'微信授权配置保存',120,1,'','',1,'F','0','0','system:wechat:edit','#',0);
INSERT INTO sys_role_menu (role_id,menu_id) VALUES
 (2016901862971731969,120),(2016901862971731969,1201),
 (2016902236600291329,120),(2016902236600291329,1201);
