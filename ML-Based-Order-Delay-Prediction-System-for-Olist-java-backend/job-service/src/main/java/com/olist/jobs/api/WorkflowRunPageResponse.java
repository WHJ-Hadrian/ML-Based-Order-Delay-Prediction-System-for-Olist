package com.olist.jobs.api;

import java.util.List;

public record WorkflowRunPageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> WorkflowRunPageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new WorkflowRunPageResponse<>(items, page, size, totalElements, totalPages);
    }
}
