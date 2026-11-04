package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class AnalysisWorkspaceConflictException extends BusinessException {

    public AnalysisWorkspaceConflictException(String message) {
        super(HttpStatus.CONFLICT, "分析方案冲突", message);
    }
}
