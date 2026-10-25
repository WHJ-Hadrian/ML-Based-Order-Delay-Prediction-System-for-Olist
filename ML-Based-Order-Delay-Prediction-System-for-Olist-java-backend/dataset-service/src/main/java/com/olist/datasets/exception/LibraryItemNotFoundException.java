package com.olist.datasets.exception;

import org.springframework.http.HttpStatus;

public class LibraryItemNotFoundException extends BusinessException {

    public LibraryItemNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "数据资产不存在", message);
    }
}
