package com.olist.datasets.service;

import com.olist.datasets.vo.DatasetColumnAnalysisVO;
import com.olist.datasets.vo.DatasetColumnRelationVO;
import java.util.UUID;

public interface DatasetAnalysisService {

    DatasetColumnAnalysisVO analyzeColumn(UUID datasetId, UUID tableId, UUID columnId);

    DatasetColumnRelationVO analyzeRelation(UUID datasetId, UUID tableId, UUID xColumnId, UUID yColumnId);
}
