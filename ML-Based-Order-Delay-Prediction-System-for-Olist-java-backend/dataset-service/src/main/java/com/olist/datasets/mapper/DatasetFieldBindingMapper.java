package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetFieldBinding;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetFieldBindingMapper {

    int insertBatch(@Param("bindings") List<DatasetFieldBinding> bindings);

    List<DatasetFieldBinding> findByDatasetVersionId(
            @Param("datasetVersionId") UUID datasetVersionId);
}