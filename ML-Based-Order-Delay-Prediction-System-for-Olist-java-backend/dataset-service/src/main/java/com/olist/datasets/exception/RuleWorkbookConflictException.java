package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class RuleWorkbookConflictException extends BusinessException {

    public RuleWorkbookConflictException(String message) {
        super(HttpStatus.CONFLICT, "规则工作簿冲突", message);
    }
}