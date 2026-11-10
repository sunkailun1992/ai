package com.ai.agent;

import com.kellen.agent.AiModelSelectionEntity;
import com.kellen.auth.AiRequestIdentityResolver;
import com.kellen.agent.AiModelSelectionRepository;
import com.kellen.agent.AiModelSelectionService;
import com.kellen.config.AiAgentClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelSelectionServiceTests {

    @Test
    void shouldPersistInitialConfiguredProvider() {
        AiModelSelectionRepository repository = mock(AiModelSelectionRepository.class);
        when(repository.findByConfigKey("default-agent-provider")).thenReturn(Optional.empty());
        when(repository.save(any(AiModelSelectionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiModelSelectionService service = new AiModelSelectionService(
                new AiAgentClientProperties(Duration.ofSeconds(8), "qwen"), repository, mock(AiRequestIdentityResolver.class));
        service.initialize();

        assertEquals("qwen", service.currentProvider());
    }

    @Test
    void shouldFallbackWhenSelectionTableIsMissingOnStartup() {
        AiModelSelectionRepository repository = mock(AiModelSelectionRepository.class);
        when(repository.findByConfigKey("default-agent-provider")).thenThrow(new BadSqlGrammarException(
                "select model selection",
                "SELECT id, config_key, provider, created_at, updated_at FROM ai_model_selection",
                new SQLSyntaxErrorException("Table 'ai.ai_model_selection' doesn't exist")
        ));
        AiModelSelectionService service = new AiModelSelectionService(
                new AiAgentClientProperties(Duration.ofSeconds(8), "deepseek"), repository, mock(AiRequestIdentityResolver.class));

        service.initialize();

        assertEquals("deepseek", service.currentProvider());
    }
}
