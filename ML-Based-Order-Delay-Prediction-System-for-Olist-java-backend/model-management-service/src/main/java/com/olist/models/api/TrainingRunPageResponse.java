package com.olist.models.api;

import java.util.List;

public record TrainingRunPageResponse(
        List<TrainingRunResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}