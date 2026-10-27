package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class RuleRunNotFoundException extends BusinessException {

    public RuleRunNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "规则运行不存在", message);
    }
}