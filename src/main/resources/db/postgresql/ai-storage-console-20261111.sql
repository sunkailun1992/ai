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

COMMENT ON TABLE ai_storage_configuration IS 'AI私有存储控制面持久配置与审计';
COMMENT ON COLUMN ai_storage_configuration.id IS '内部固定标识';
COMMENT ON COLUMN ai_storage_configuration.scope IS '固定PLATFORM控制面作用域';
COMMENT ON COLUMN ai_storage_configuration.platform IS '旧文件持久化引用的平台，创建后固定';
COMMENT ON COLUMN ai_storage_configuration.name IS '控制台显示名';
COMMENT ON COLUMN ai_storage_configuration.provider IS '原生存储协议类型';
COMMENT ON COLUMN ai_storage_configuration.endpoint IS '不可变连接origin，无签名参数';
COMMENT ON COLUMN ai_storage_configuration.bucket IS '不可变私有桶名称';
COMMENT ON COLUMN ai_storage_configuration.region IS '不可变原生区域';
COMMENT ON COLUMN ai_storage_configuration.upload_enabled IS '仅控制新写入，不阻断历史读取';
COMMENT ON COLUMN ai_storage_configuration.key_id IS '独立加密主密钥标识';
COMMENT ON COLUMN ai_storage_configuration.nonce IS 'AES-GCM随机nonce，Base64';
COMMENT ON COLUMN ai_storage_configuration.ciphertext IS '二元凭据AES-GCM密文，禁止响应返回';
COMMENT ON COLUMN ai_storage_configuration.credential_revision IS '凭据业务版本及AAD绑定';
COMMENT ON COLUMN ai_storage_configuration.config_revision IS '运行时配置业务版本';
COMMENT ON COLUMN ai_storage_configuration.credential_updated_at IS '凭据最后轮换时间';
COMMENT ON COLUMN ai_storage_configuration.code IS '公共业务编码';
COMMENT ON COLUMN ai_storage_configuration.description IS '公共说明';
COMMENT ON COLUMN ai_storage_configuration.create_date_time IS '创建时间';
COMMENT ON COLUMN ai_storage_configuration.create_name IS '创建人';
COMMENT ON COLUMN ai_storage_configuration.modify_date_time IS '修改时间';
COMMENT ON COLUMN ai_storage_configuration.modify_name IS '修改人';
COMMENT ON COLUMN ai_storage_configuration.is_delete IS '逻辑删除，不自动清理旧文件';
COMMENT ON COLUMN ai_storage_configuration.type IS '公共类型';
COMMENT ON COLUMN ai_storage_configuration.state IS '公共状态';
COMMENT ON COLUMN ai_storage_configuration.label IS '公共标签';
COMMENT ON COLUMN ai_storage_configuration.sorting IS '公共排序';
COMMENT ON COLUMN ai_storage_configuration.version IS '数据库乐观锁版本';
COMMENT ON COLUMN ai_storage_configuration.tenant_id IS '固定PLATFORM全局记录，非文件租户授权';
COMMENT ON TABLE ai_storage_setting IS 'AI私有存储控制面持久配置与审计';
COMMENT ON COLUMN ai_storage_setting.id IS '内部固定标识';
COMMENT ON COLUMN ai_storage_setting.scope IS '固定PLATFORM控制面作用域';
COMMENT ON COLUMN ai_storage_setting.default_platform IS '新上传默认平台';
COMMENT ON COLUMN ai_storage_setting.legacy_null_platform IS '历史空平台固定映射，初始化后不可变';
COMMENT ON COLUMN ai_storage_setting.base_dir IS '业务对象前缀，初始化后不可变';
COMMENT ON COLUMN ai_storage_setting.upload_ttl_seconds IS '直传签名TTL秒，60至3600';
COMMENT ON COLUMN ai_storage_setting.view_ttl_seconds IS '私有展示签名TTL秒，60至3600';
COMMENT ON COLUMN ai_storage_setting.max_upload_bytes IS '新上传最大字节数，不超过50MiB';
COMMENT ON COLUMN ai_storage_setting.code IS '公共业务编码';
COMMENT ON COLUMN ai_storage_setting.description IS '公共说明';
COMMENT ON COLUMN ai_storage_setting.create_date_time IS '创建时间';
COMMENT ON COLUMN ai_storage_setting.create_name IS '创建人';
COMMENT ON COLUMN ai_storage_setting.modify_date_time IS '修改时间';
COMMENT ON COLUMN ai_storage_setting.modify_name IS '修改人';
COMMENT ON COLUMN ai_storage_setting.is_delete IS '逻辑删除，不自动清理旧文件';
COMMENT ON COLUMN ai_storage_setting.type IS '公共类型';
COMMENT ON COLUMN ai_storage_setting.state IS '公共状态';
COMMENT ON COLUMN ai_storage_setting.label IS '公共标签';
COMMENT ON COLUMN ai_storage_setting.sorting IS '公共排序';
COMMENT ON COLUMN ai_storage_setting.version IS '数据库乐观锁版本';
COMMENT ON COLUMN ai_storage_setting.tenant_id IS '固定PLATFORM全局记录，非文件租户授权';
COMMENT ON TABLE ai_storage_operation IS 'AI私有存储控制面持久配置与审计';
COMMENT ON COLUMN ai_storage_operation.id IS '内部固定标识';
COMMENT ON COLUMN ai_storage_operation.scope IS '固定PLATFORM控制面作用域';
COMMENT ON COLUMN ai_storage_operation.actor_id IS '已验证平台管理员用户ID';
COMMENT ON COLUMN ai_storage_operation.request_id IS '客户端UUID幂等标识';
COMMENT ON COLUMN ai_storage_operation.action IS '固定操作类型';
COMMENT ON COLUMN ai_storage_operation.configuration_id IS '操作目标配置ID';
COMMENT ON COLUMN ai_storage_operation.fingerprint IS '独立域HMAC-SHA256请求指纹，不含明文请求';
COMMENT ON COLUMN ai_storage_operation.operation_json IS '白名单安全操作结果，不含凭据或对象URL';
COMMENT ON COLUMN ai_storage_operation.status IS '持久化操作状态，未知结果不得重放';
COMMENT ON COLUMN ai_storage_operation.started_at IS '真实操作开始时间';
COMMENT ON COLUMN ai_storage_operation.code IS '公共业务编码';
COMMENT ON COLUMN ai_storage_operation.description IS '公共说明';
COMMENT ON COLUMN ai_storage_operation.create_date_time IS '创建时间';
COMMENT ON COLUMN ai_storage_operation.create_name IS '创建人';
COMMENT ON COLUMN ai_storage_operation.modify_date_time IS '修改时间';
COMMENT ON COLUMN ai_storage_operation.modify_name IS '修改人';
COMMENT ON COLUMN ai_storage_operation.is_delete IS '逻辑删除，不自动清理旧文件';
COMMENT ON COLUMN ai_storage_operation.type IS '公共类型';
COMMENT ON COLUMN ai_storage_operation.state IS '公共状态';
COMMENT ON COLUMN ai_storage_operation.label IS '公共标签';
COMMENT ON COLUMN ai_storage_operation.sorting IS '公共排序';
COMMENT ON COLUMN ai_storage_operation.version IS '数据库乐观锁版本';
COMMENT ON COLUMN ai_storage_operation.tenant_id IS '固定PLATFORM全局记录，非文件租户授权';
COMMENT ON COLUMN ai_storage_operation.cleanup_status IS 'owned探针对象清理状态，确认缺失后才允许后续变更';
