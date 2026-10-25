package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class InvalidLibraryRequestException extends BusinessException {

    public InvalidLibraryRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "数据资产配置无效", message);
    }
}
