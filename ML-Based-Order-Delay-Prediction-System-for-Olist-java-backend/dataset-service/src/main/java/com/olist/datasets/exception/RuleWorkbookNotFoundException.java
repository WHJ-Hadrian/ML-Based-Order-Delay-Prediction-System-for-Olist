package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class RuleWorkbookNotFoundException extends BusinessException {

    public RuleWorkbookNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "规则工作簿不存在", message);
    }
}