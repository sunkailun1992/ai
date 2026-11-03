package com.kellen.agent;

import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型默认选择管理接口。
 *
 * <p>管理端通过主 AI 服务切换默认 Provider，主服务再把 Provider 覆盖传给
 * {@code ai-agent}；执行层不直接承担管理端入口职责。</p>
 */
@RestController
@RequestMapping("/api/ai/model-selection")
public class AiModelSelectionController {

    private static final Logger log = LoggerFactory.getLogger(AiModelSelectionController.class);

    private final AiModelSelectionService modelSelectionService;
    private final AiAgentClient aiAgentClient;

    public AiModelSelectionController(AiModelSelectionService modelSelectionService,
                                      AiAgentClient aiAgentClient) {
        this.modelSelectionService = modelSelectionService;
        this.aiAgentClient = aiAgentClient;
    }

    /**
     * 查询当前默认模型。
     *
     * @return 当前默认 Provider 以及 Agent 执行层解析出的模型名。
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public AiModelSelectionResponse current() {
        return response(modelSelectionService.currentProvider());
    }

    /**
     * 切换当前默认 Provider。
     *
     * @param request 更新请求。
     * @return 更新后的默认模型信息。
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AiModelSelectionResponse update(@RequestBody AiModelSelectionUpdateRequest request) {
        String provider = modelSelectionService.updateDefaultProvider(request.provider());
        return response(provider);
    }

    private AiModelSelectionResponse response(String provider) {
        try {
            AiAgentClient.AgentModelResult agentModel = aiAgentClient.model(provider);
            return modelSelectionService.response(provider, agentModel.model(), agentModel.supportedProviders());
        } catch (RuntimeException exception) {
            log.warn("查询 AI Agent 模型信息失败，已回退为仅显示 Provider: provider={}", provider, exception);
            return modelSelectionService.response(provider, "", modelSelectionService.supportedProviders());
        }
    }
}
