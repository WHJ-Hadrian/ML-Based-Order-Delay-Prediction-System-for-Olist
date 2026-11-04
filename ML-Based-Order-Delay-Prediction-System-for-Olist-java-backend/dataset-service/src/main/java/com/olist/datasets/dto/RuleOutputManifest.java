package com.olist.datasets.dto;

import java.util.List;

public record RuleOutputManifest(List<Output> outputs) {

    public record Output(
            String nodeId,
            String name,
            String filePart,
            String sha256,
            long sizeBytes,
            long rowCount,
            int columnCount) {}
}
