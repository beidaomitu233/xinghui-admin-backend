CREATE TABLE sys_ai_business_model (
  model_id BIGINT NOT NULL AUTO_INCREMENT,
  slug VARCHAR(80) NOT NULL,
  revision INT NOT NULL DEFAULT 0,
  definition_json LONGTEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  next_index BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (model_id), UNIQUE KEY uk_ai_business_slug (slug)
);
CREATE TABLE sys_ai_business_model_mutation (
  owner_scope VARCHAR(160) NOT NULL,
  mutation_id VARCHAR(36) NOT NULL,
  input_hash VARCHAR(128) NOT NULL,
  model_id BIGINT NOT NULL,
  result_json LONGTEXT NOT NULL,
  PRIMARY KEY (owner_scope,mutation_id)
);
CREATE TABLE sys_ai_business_model_request (
  request_id BIGINT NOT NULL,
  model_id BIGINT NOT NULL,
  model_revision INT NOT NULL,
  owner_scope VARCHAR(160) NOT NULL,
  route_json LONGTEXT NOT NULL,
  test_request BOOLEAN NOT NULL DEFAULT FALSE,
  test_mutation VARCHAR(36) NULL,
  PRIMARY KEY (request_id), KEY idx_ai_business_model_request (model_id,request_id),
  UNIQUE KEY uk_ai_business_test_mutation (owner_scope,test_mutation)
);
