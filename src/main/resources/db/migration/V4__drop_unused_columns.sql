-- 删除没人读写的列：保留的 10 张表的 code、label、sorting，以及除 ai_role_prompt（description 映射角色用途说明）外的 description；
-- 服务代码、手写 SQL、接口、rpc-api 与 admin-web 都不用它们。
-- 删之前逐列核对：整列只有 NULL 或列默认值才删；只要有一行别的值就报错，整版回滚，服务停止启动，留给人工判断。
DO $$
DECLARE
    target record;
    column_default text;
    remaining bigint;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('ai_chat_memory', 'code'),
            ('ai_chat_memory', 'description'),
            ('ai_chat_memory', 'label'),
            ('ai_chat_memory', 'sorting'),
            ('ai_chat_memory_summary', 'code'),
            ('ai_chat_memory_summary', 'description'),
            ('ai_chat_memory_summary', 'label'),
            ('ai_chat_memory_summary', 'sorting'),
            ('ai_chat_message', 'code'),
            ('ai_chat_message', 'description'),
            ('ai_chat_message', 'label'),
            ('ai_chat_message', 'sorting'),
            ('ai_file_object', 'code'),
            ('ai_file_object', 'description'),
            ('ai_file_object', 'label'),
            ('ai_file_object', 'sorting'),
            ('ai_model_selection', 'code'),
            ('ai_model_selection', 'description'),
            ('ai_model_selection', 'label'),
            ('ai_model_selection', 'sorting'),
            ('ai_patient_memory', 'code'),
            ('ai_patient_memory', 'description'),
            ('ai_patient_memory', 'label'),
            ('ai_patient_memory', 'sorting'),
            ('ai_role_prompt', 'code'),
            ('ai_role_prompt', 'label'),
            ('ai_role_prompt', 'sorting'),
            ('ai_storage_configuration', 'code'),
            ('ai_storage_configuration', 'description'),
            ('ai_storage_configuration', 'label'),
            ('ai_storage_configuration', 'sorting'),
            ('ai_storage_operation', 'code'),
            ('ai_storage_operation', 'description'),
            ('ai_storage_operation', 'label'),
            ('ai_storage_operation', 'sorting'),
            ('ai_storage_setting', 'code'),
            ('ai_storage_setting', 'description'),
            ('ai_storage_setting', 'label'),
            ('ai_storage_setting', 'sorting')
        ) AS drops(table_name, column_name)
    LOOP
        SELECT c.column_default INTO column_default
        FROM information_schema.columns c
        WHERE c.table_schema = current_schema() AND c.table_name = target.table_name AND c.column_name = target.column_name;
        IF NOT FOUND THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE %I IS NOT NULL%s', target.table_name, target.column_name,
                CASE WHEN column_default IS NULL THEN ''
                     ELSE format(' AND %I IS DISTINCT FROM (%s)', target.column_name, column_default) END)
            INTO remaining;
        IF remaining > 0 THEN
            RAISE EXCEPTION '%.% 还有 % 行数据，不能删除', target.table_name, target.column_name, remaining;
        END IF;
        EXECUTE format('ALTER TABLE %I DROP COLUMN %I', target.table_name, target.column_name);
    END LOOP;
END
$$;
