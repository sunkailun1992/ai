package com.kellen.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiModelCallObserverTest {
    @Test
    void boundsDynamicLabelsAndSeparatesCancellation() {
        var registry = new SimpleMeterRegistry();
        var observer = new AiModelCallObserver(registry);
        observer.success("nacos-ai-registry", "patient-analysis-agent", "chat_stream", observer.start());
        observer.failure("dynamic secret provider", "dynamic model id", "dynamic operation", observer.start());
        observer.cancelled("nacos-ai-registry", "patient-analysis-agent", "chat_stream", observer.start());
        assertThat(registry.get("ai.model.call.count").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.model.call.count").tag("outcome", "failure").tag("model", "other").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.model.call.count").tag("outcome", "cancelled").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain("secret", "dynamic"));
    }
}
