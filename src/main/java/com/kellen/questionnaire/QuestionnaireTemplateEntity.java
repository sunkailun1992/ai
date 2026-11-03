package com.kellen.questionnaire;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话式问卷模板实体，对应 ai_questionnaire_template 表。
 *
 * <p>模板定义驱动问卷状态机、题目文案、题目级附件归属和外部系统字段映射。
 * 小程序可以只有一个 AI 对话框，但后端必须通过 questionnaireCode/version 精确区分问卷。</p>
 */
@TableName("ai_questionnaire_template")
public class QuestionnaireTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id; // 模板记录 ID。

    private String questionnaireCode; // 小程序启动问卷时传入的 questionnaireCode。

    @TableField("template_version")
    private String version; // 同一问卷的配置版本。

    private String name; // 面向管理后台和测试响应展示的名称。

    private String scene; // 业务场景，例如 symptom_triage/report_supplement。

    private String schemaJson; // 数据库中的模板源数据，运行时以该字段为准。

    private boolean enabled; // 只有启用模板可被问卷会话加载。

    private LocalDateTime createdAt; // 模板首次入库时间。

    private LocalDateTime updatedAt; // 模板最近更新时间。

    /**
     * 创建启用状态的问卷模板。
     *
     * @param questionnaireCode 问卷编码
     * @param version           模板版本
     * @param name              模板展示名称
     * @param scene             业务场景
     * @param schemaJson        模板 JSON 原文
     * @return 待保存的模板实体
     */
    public static QuestionnaireTemplateEntity create(String questionnaireCode, String version, String name, String scene, String schemaJson) {
        QuestionnaireTemplateEntity entity = new QuestionnaireTemplateEntity();
        entity.questionnaireCode = questionnaireCode;
        entity.version = version;
        entity.name = name;
        entity.scene = scene;
        entity.schemaJson = schemaJson;
        entity.enabled = true;
        return entity;
    }

    /**
     * 更新模板可编辑字段。
     *
     * <p>questionnaireCode 和 version 不在这里修改，避免历史问卷会话和模板版本的归属被重写。</p>
     *
     * @param name       模板展示名称
     * @param scene      业务场景
     * @param schemaJson 模板 JSON 原文
     * @param enabled    是否允许新会话加载
     */
    public void update(String name, String scene, String schemaJson, boolean enabled) {
        this.name = name;
        this.scene = scene;
        this.schemaJson = schemaJson;
        this.enabled = enabled;
    }

    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getQuestionnaireCode() {
        return questionnaireCode;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getScene() {
        return scene;
    }

    public String getSchemaJson() {
        return schemaJson;
    }
}
