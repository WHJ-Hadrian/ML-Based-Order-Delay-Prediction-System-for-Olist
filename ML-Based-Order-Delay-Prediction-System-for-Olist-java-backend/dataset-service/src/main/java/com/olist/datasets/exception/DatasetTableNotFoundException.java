package com.olist.datasets.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DatasetTableNotFoundException extends BusinessException {

    public DatasetTableNotFoundException(UUID datasetId, UUID tableId) {
        super(
                HttpStatus.NOT_FOUND,
                "数据表不存在",
                "数据集 " + datasetId + " 中不存在数据表 " + tableId);
    }
}