CREATE TABLE IF NOT EXISTS ai_storage_configuration (
    id VARCHAR(64) PRIMARY KEY, scope VARCHAR(16) NOT NULL CHECK (scope='PLATFORM'),
    platform VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(80) NOT NULL, provider VARCHAR(32) NOT NULL,
    endpoint VARCHAR(512) NOT NULL, bucket VARCHAR(128) NOT NULL, region VARCHAR(64),
    upload_enabled BOOLEAN NOT NULL, key_id VARCHAR(64) NOT NULL, nonce VARCHAR(64) NOT NULL,
    ciphertext TEXT NOT NULL, credential_revision BIGINT NOT NULL, config_revision BIGINT NOT NULL,
    credential_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    code VARCHAR(255), description VARCHAR(255),
    create_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_name VARCHAR(255), modify_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_name VARCHAR(255), is_delete BOOLEAN NOT NULL DEFAULT false,
    type INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 1,
    label VARCHAR(255), sorting INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1, tenant_id VARCHAR(64) NOT NULL DEFAULT 'PLATFORM'
);
CREATE TABLE IF NOT EXISTS ai_storage_setting (
    id VARCHAR(16) PRIMARY KEY CHECK (id='PLATFORM'), scope VARCHAR(16) NOT NULL CHECK (scope='PLATFORM'),
    default_platform VARCHAR(128), legacy_null_platform VARCHAR(128), base_dir VARCHAR(200) NOT NULL,
    upload_ttl_seconds BIGINT NOT NULL CHECK (upload_ttl_seconds BETWEEN 60 AND 3600),
    view_ttl_seconds BIGINT NOT NULL CHECK (view_ttl_seconds BETWEEN 60 AND 3600),
    max_upload_bytes BIGINT NOT NULL CHECK (max_upload_bytes BETWEEN 1 AND 52428800),
    code VARCHAR(255), description VARCHAR(255),
    create_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_name VARCHAR(255), modify_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_name VARCHAR(255), is_delete BOOLEAN NOT NULL DEFAULT false,
    type INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 1,
    label VARCHAR(255), sorting INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1, tenant_id VARCHAR(64) NOT NULL DEFAULT 'PLATFORM'
);
INSERT INTO ai_storage_setting(id,scope,base_dir,upload_ttl_seconds,view_ttl_seconds,max_upload_bytes,version)
SELECT 'PLATFORM','PLATFORM','ai-files',60,1800,52428800,0
WHERE NOT EXISTS(SELECT 1 FROM ai_storage_setting WHERE id='PLATFORM');
CREATE TABLE IF NOT EXISTS ai_storage_operation (
    id VARCHAR(64) PRIMARY KEY, scope VARCHAR(16) NOT NULL CHECK (scope='PLATFORM'),
    actor_id VARCHAR(128) NOT NULL, request_id VARCHAR(36) NOT NULL, action VARCHAR(32) NOT NULL,
    configuration_id VARCHAR(64), fingerprint VARCHAR(64) NOT NULL, operation_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL, cleanup_status VARCHAR(32) NOT NULL DEFAULT 'NOT_NEEDED', started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    code VARCHAR(255), description VARCHAR(255),
    create_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_name VARCHAR(255), modify_date_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_name VARCHAR(255), is_delete BOOLEAN NOT NULL DEFAULT false,
    type INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 1,
    label VARCHAR(255), sorting INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1, tenant_id VARCHAR(64) NOT NULL DEFAULT 'PLATFORM'
, UNIQUE(actor_id,request_id)
);
CREATE INDEX IF NOT EXISTS idx_ai_storage_probe ON ai_storage_operation(configuration_id,action,started_at);
