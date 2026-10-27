package com.olist.datasets.service;

import com.olist.datasets.dto.FieldDefinitionQuery;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.vo.FieldCatalogItemVO;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.PageVO;
import java.util.List;
import java.util.UUID;

public interface FieldLibraryService {

    PageVO<FieldDefinitionVO> list(FieldDefinitionQuery query);

    FieldDefinitionVO get(UUID id);

    FieldDefinitionVO create(FieldDefinitionRequest request);

    FieldDefinitionVO update(UUID id, FieldDefinitionRequest request);

    void delete(UUID id);

    List<FieldCatalogItemVO> catalog(UUID datasetVersionId);

    FieldImportResultVO importFromDataset(FieldImportRequest request);
}
