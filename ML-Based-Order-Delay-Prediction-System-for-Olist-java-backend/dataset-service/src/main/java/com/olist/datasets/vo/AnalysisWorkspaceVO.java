package com.olist.datasets.vo;

import com.olist.datasets.dto.AnalysisWorkspaceRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisWorkspaceVO(
        UUID id,
        String name,
        long revision,
        List<AnalysisWorkspaceRequest.CardRef> cards,
        Instant createdAt,
        Instant updatedAt) {
}
