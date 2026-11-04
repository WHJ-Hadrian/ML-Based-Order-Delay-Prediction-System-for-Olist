package com.olist.datasets.security;

import com.olist.datasets.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RuleWorkerAuthenticator {

    private final byte[] expectedToken;

    public RuleWorkerAuthenticator(
            @Value("${olist.rule-worker.internal-token:}") String internalToken) {
        this.expectedToken = normalize(internalToken);
    }

    public void requireValid(String suppliedToken) {
        byte[] supplied = normalize(suppliedToken);
        if (expectedToken.length == 0) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "规则 Worker 未配置",
                    "rule worker internal API is disabled");
        }
        if (supplied.length == 0 || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "规则 Worker 鉴权失败",
                    "invalid rule worker token");
        }
    }

    private static byte[] normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.getBytes(StandardCharsets.UTF_8);
    }
}
