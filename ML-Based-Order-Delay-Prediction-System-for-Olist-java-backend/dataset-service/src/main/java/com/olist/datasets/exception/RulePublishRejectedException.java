package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class RulePublishRejectedException extends BusinessException {

    public RulePublishRejectedException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "规则校验未通过", message);
    }
}