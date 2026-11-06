package com.kellen.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * AI 模型调用观测服务。
 *
 * <p>统一记录 provider、model、operation、outcome 维度的调用耗时和次数，
 * 供 Actuator / Prometheus 后续采集。</p>
 */
@Service
public class AiModelCallObserver {

    private final MeterRegistry meterRegistry; // Micrometer 指标注册表。

    public AiModelCallObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void success(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "success", startNanos);
    }

    public void failure(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "failure", startNanos);
    }

    public void cancelled(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "cancelled", startNanos);
    }

    private void record(String provider, String model, String operation, String outcome, long startNanos) {
        String safeProvider = "nacos-ai-registry".equals(provider) ? provider : "other";
        String safeModel = "patient-analysis-agent".equals(model) ? model : "other";
        String safeOperation = "chat_stream".equals(operation) ? operation : "other";
        Duration duration = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
        Timer.builder("ai.model.call.duration")
                .description("AI model call duration")
                .tag("provider", safeProvider)
                .tag("model", safeModel)
                .tag("operation", safeOperation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
        Counter.builder("ai.model.call.count")
                .description("AI model call count")
                .tag("provider", safeProvider)
                .tag("model", safeModel)
                .tag("operation", safeOperation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

}
