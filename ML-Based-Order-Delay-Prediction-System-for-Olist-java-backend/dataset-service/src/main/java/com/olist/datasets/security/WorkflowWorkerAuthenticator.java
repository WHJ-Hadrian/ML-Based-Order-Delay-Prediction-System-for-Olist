package com.olist.datasets.security;

import com.olist.datasets.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkflowWorkerAuthenticator {
    private final byte[] expectedToken;

    public WorkflowWorkerAuthenticator(@Value("${olist.workflow-worker.internal-token:}") String token) {
        expectedToken = normalize(token);
    }

    public void requireValid(String token) {
        byte[] supplied = normalize(token);
        if (expectedToken.length == 0) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "工作流 Worker 未配置", "workflow worker internal API is disabled");
        }
        if (supplied.length == 0 || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "工作流 Worker 鉴权失败", "invalid workflow worker token");
        }
    }

    private static byte[] normalize(String value) {
        return (value == null ? "" : value.trim()).getBytes(StandardCharsets.UTF_8);
    }
}
