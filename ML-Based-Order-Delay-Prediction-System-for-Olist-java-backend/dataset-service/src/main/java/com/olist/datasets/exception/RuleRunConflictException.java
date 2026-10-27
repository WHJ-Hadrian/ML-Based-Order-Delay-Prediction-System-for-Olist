package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class RuleRunConflictException extends BusinessException {

    public RuleRunConflictException(String message) {
        super(HttpStatus.CONFLICT, "规则运行冲突", message);
    }
}