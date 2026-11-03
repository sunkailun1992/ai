package com.kellen.questionnaire;

import com.kellen.auth.AiWechatUserEntity;
import com.kellen.auth.WechatAuthService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话式问卷 REST 接口控制器。
 *
 * <p>小程序仍然使用一个 AI 对话框，但问卷链路单独进入本控制器，
 * 避免问卷状态、结构化答案和题目级附件污染普通聊天记忆。</p>
 */
@RestController // 声明问卷 REST Controller，返回值由 Spring 自动序列化。
@RequestMapping("/api/ai/questionnaire") // 对话式问卷接口统一路径前缀。
public class AiQuestionnaireController {

    private final ConversationalQuestionnaireService questionnaireService; // 对话式问卷流程服务。
    private final QuestionnaireTemplateService templateService; // 问卷模板配置服务。
    private final QuestionnaireRecordService recordService; // 当前用户问卷记录查询服务。
    private final WechatAuthService authService; // 微信认证服务，用于校验“我的问卷”归属。

    public AiQuestionnaireController(ConversationalQuestionnaireService questionnaireService,
                                     QuestionnaireTemplateService templateService,
                                     QuestionnaireRecordService recordService,
                                     WechatAuthService authService) {
        this.questionnaireService = questionnaireService; // 保存问卷流程服务实例。
        this.templateService = templateService; // 保存模板配置服务实例。
        this.recordService = recordService; // 保存问卷记录查询服务实例。
        this.authService = authService; // 保存认证服务实例。
    }

    /**
     * 保存或更新问卷模板。
     *
     * @param request 完整模板 JSON。
     * @return 保存后的模板信息。
     */
    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 保存或更新模板接口。
    public QuestionnaireTemplateResponse saveTemplate(@RequestBody QuestionnaireTemplateSaveRequest request) {
        return templateService.save(request); // 委托模板服务校验并落库。
    }

    /**
     * 直接导入完整问卷模板 JSON，方便 Postman 或脚本用 docs/examples/questionnaires 下的样例文件测试。
     *
     * @param schemaJson 完整模板 JSON。
     * @param enabled    是否启用。
     * @return 保存后的模板信息。
     */
    @PostMapping(value = "/templates/import", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 原始 JSON 模板导入接口。
    public QuestionnaireTemplateResponse importTemplate(@RequestBody String schemaJson,
                                                        @RequestParam(defaultValue = "true") boolean enabled) {
        return templateService.save(new QuestionnaireTemplateSaveRequest(schemaJson, enabled)); // 包装成统一保存请求。
    }

    /**
     * 查询启用中的问卷模板列表，供小程序抽屉展示“问卷列表”。
     *
     * @return 启用问卷模板摘要列表。
     */
    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE) // 查询小程序问卷列表接口。
    public List<QuestionnaireTemplateSummaryResponse> templates() {
        return templateService.enabledSummaries(); // 列表只返回摘要，不下发完整 schemaJson。
    }

    /**
     * 查询当前启用问卷模板。
     *
     * @param questionnaireCode 问卷编码。
     * @return 当前启用模板。
     */
    @GetMapping(value = "/templates/{questionnaireCode}", produces = MediaType.APPLICATION_JSON_VALUE) // 查询当前启用模板接口。
    public QuestionnaireTemplateResponse template(@PathVariable String questionnaireCode) {
        return templateService.current(questionnaireCode); // 委托模板服务读取数据库中的启用模板。
    }

    /**
     * 对话式问卷流式入口。
     *
     * @param request 小程序对话请求。
     * @return 当前问卷状态和下一句回复。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE) // 对话式问卷 SSE 接口。
    public Flux<QuestionnaireChatResponse> chat(@RequestBody QuestionnaireChatRequest request) {
        return Flux.just(questionnaireService.chat(request)); // 当前返回单个状态事件，前端按 SSE 方式消费。
    }

    /**
     * 查询最近一次问卷状态，便于刷新页面后继续测试。
     *
     * @param conversationId 小程序 AI 对话会话 ID。
     * @return 最近一次问卷状态；普通会话没有问卷时返回 204，避免刷新普通对话时报错。
     */
    @GetMapping(value = "/sessions/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE) // 查询问卷会话快照接口。
    public ResponseEntity<QuestionnaireChatResponse> snapshot(@PathVariable String conversationId) {
        return questionnaireService.snapshot(conversationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build()); // 普通对话没有问卷状态，不应返回 400。
    }

    /**
     * 查询当前登录用户填写过的问卷列表。
     *
     * @param authorization Authorization Header。
     * @return 当前用户问卷会话摘要列表。
     */
    @GetMapping(value = "/my/sessions", produces = MediaType.APPLICATION_JSON_VALUE) // 当前用户问卷记录列表。
    public List<QuestionnaireSessionSummaryResponse> mySessions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AiWechatUserEntity user = currentUser(authorization); // “我的问卷”必须登录。
        return recordService.list(currentQuestionnaireUserId(user)); // 只返回当前用户自己的问卷。
    }

    /**
     * 查询当前登录用户指定问卷详情。
     *
     * @param authorization Authorization Header。
     * @param sessionId     问卷会话 ID。
     * @return 问卷详情。
     */
    @GetMapping(value = "/my/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE) // 当前用户问卷详情。
    public QuestionnaireSessionDetailResponse mySessionDetail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                              @PathVariable Long sessionId) {
        AiWechatUserEntity user = currentUser(authorization); // 详情也必须校验当前登录用户。
        return recordService.detail(currentQuestionnaireUserId(user), sessionId);
    }

    private AiWechatUserEntity currentUser(String authorization) {
        try {
            return authService.currentUser(authorization);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }

    private String currentQuestionnaireUserId(AiWechatUserEntity user) {
        return "wx-" + user.getId();
    }
}
