-- 删除 8 张没有任何代码读写的表（对话会话与消息、集成发件箱、问卷三表、微信会话与用户）。
-- 删之前逐表核对为空：只要有一行就报错，整版回滚，服务停止启动，留给人工判断。
DO $$
DECLARE
    target text;
    remaining bigint;
BEGIN
    FOREACH target IN ARRAY ARRAY['ai_chat_conversation', 'ai_chat_conversation_message', 'ai_integration_outbox', 'ai_questionnaire_answer', 'ai_questionnaire_session', 'ai_questionnaire_template', 'ai_wechat_session', 'ai_wechat_user'] LOOP
        IF to_regclass(format('%I.%I', current_schema(), target)) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I', target) INTO remaining;
        IF remaining > 0 THEN
            RAISE EXCEPTION '% 还有 % 行数据，不能删除', target, remaining;
        END IF;
        EXECUTE format('DROP TABLE %I', target);
    END LOOP;
END
$$;

-- 存储三表的审计时间改为与其余表一致的 timestamp(0)（不带时区）：按 Asia/Shanghai 换算，读出的本地时间不变，秒以下取整；
-- 真实时刻列 started_at、credential_updated_at 仍为 timestamptz。
-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_configuration
    ALTER COLUMN create_time TYPE timestamp(0) USING create_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN create_time SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN update_time TYPE timestamp(0) USING update_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN update_time SET DEFAULT CURRENT_TIMESTAMP;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_setting
    ALTER COLUMN create_time TYPE timestamp(0) USING create_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN create_time SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN update_time TYPE timestamp(0) USING update_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN update_time SET DEFAULT CURRENT_TIMESTAMP;

-- AI私有存储控制面持久配置与审计。
ALTER TABLE ai_storage_operation
    ALTER COLUMN create_time TYPE timestamp(0) USING create_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN create_time SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN update_time TYPE timestamp(0) USING update_time AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN update_time SET DEFAULT CURRENT_TIMESTAMP;
