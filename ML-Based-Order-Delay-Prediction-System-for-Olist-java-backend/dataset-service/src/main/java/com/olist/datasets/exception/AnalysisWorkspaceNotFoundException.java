package com.olist.datasets.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AnalysisWorkspaceNotFoundException extends BusinessException {

    public AnalysisWorkspaceNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "分析方案不存在", "找不到分析方案「" + id + "」");
    }
}
