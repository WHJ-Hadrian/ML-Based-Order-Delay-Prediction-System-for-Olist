package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class DatasetVersionImmutableException extends BusinessException {

    public DatasetVersionImmutableException(String message) {
        super(HttpStatus.CONFLICT, "数据集版本不可修改", message);
    }
}
