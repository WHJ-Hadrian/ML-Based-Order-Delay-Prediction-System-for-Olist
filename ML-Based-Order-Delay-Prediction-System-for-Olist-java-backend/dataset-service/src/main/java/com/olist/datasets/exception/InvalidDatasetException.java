package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class InvalidDatasetException extends BusinessException {

    public InvalidDatasetException(String message) {
        super(HttpStatus.BAD_REQUEST, "上传请求无效", message);
    }

    public InvalidDatasetException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, "上传请求无效", message, cause);
    }
}
