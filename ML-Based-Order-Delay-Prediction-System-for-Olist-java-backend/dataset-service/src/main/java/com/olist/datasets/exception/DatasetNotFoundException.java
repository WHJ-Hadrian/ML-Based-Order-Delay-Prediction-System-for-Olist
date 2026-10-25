package com.olist.datasets.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DatasetNotFoundException extends BusinessException {

    public DatasetNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "数据集不存在", "数据集 " + id + " 不存在");
    }
}
