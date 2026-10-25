package com.olist.datasets.vo;

import java.util.List;

public record PageVO<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageVO<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageVO<>(items, page, size, totalElements, totalPages);
    }
}
