package com.olist.models.service;

import com.olist.models.entity.TrainingRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TrainingTokenProvider {

    private final String configuredToken;

    public TrainingTokenProvider(
            @Value("${olist.model-training.worker-bearer-token:}") String configuredToken) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
    }

    public String resolve(TrainingRun run) {
        if (!configuredToken.isBlank()) {
            return configuredToken;
        }
        String ownerToken = run.getOwnerToken();
        if (ownerToken != null && !ownerToken.isBlank()) {
            return ownerToken;
        }
        throw new IllegalStateException("训练 Worker 未配置服务令牌，且运行记录中没有可用的调用方令牌");
    }
}