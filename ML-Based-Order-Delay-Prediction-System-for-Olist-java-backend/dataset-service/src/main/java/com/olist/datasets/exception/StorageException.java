package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class StorageException extends BusinessException {

    public StorageException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "数据集存储失败", message);
    }

    public StorageException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "数据集存储失败", message, cause);
    }
}
