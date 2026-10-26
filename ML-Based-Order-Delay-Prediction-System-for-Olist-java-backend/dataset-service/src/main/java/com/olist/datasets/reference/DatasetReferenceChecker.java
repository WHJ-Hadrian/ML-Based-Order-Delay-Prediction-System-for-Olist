package com.olist.datasets.reference;

import com.olist.datasets.mapper.DatasetReferenceMapper;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DatasetReferenceChecker {

    private final DatasetReferenceMapper datasetReferenceMapper;

    public DatasetReferenceChecker(DatasetReferenceMapper datasetReferenceMapper) {
        this.datasetReferenceMapper = datasetReferenceMapper;
    }

    public boolean isReferenced(UUID datasetVersionId) {
        return datasetReferenceMapper.countByDatasetVersionId(datasetVersionId) > 0;
    }

    public boolean isReferenced(Collection<UUID> datasetVersionIds) {
        return datasetVersionIds.stream().anyMatch(this::isReferenced);
    }
}