package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class DatasetNotReadyException extends BusinessException {

    public DatasetNotReadyException(String message) {
        super(HttpStatus.CONFLICT, "数据集未就绪", message);
    }
}