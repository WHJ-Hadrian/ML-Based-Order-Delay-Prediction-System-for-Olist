package com.olist.datasets.security;

import com.olist.datasets.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ModelTrainingDatasetAuthenticator {

    private final byte[] expectedToken;

    public ModelTrainingDatasetAuthenticator(
            @Value("${olist.model-training.internal-token:}") String internalToken) {
        this.expectedToken = normalize(internalToken);
    }

    public void requireValid(String suppliedToken) {
        byte[] supplied = normalize(suppliedToken);
        if (expectedToken.length == 0) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "模型训练服务未配置",
                    "model training dataset export is disabled");
        }
        if (supplied.length == 0 || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "模型训练服务鉴权失败",
                    "invalid model training token");
        }
    }

    private static byte[] normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.getBytes(StandardCharsets.UTF_8);
    }
}
