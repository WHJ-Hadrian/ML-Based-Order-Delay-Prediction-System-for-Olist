package com.olist.datasets.service;

import com.olist.datasets.dto.FieldBulkCreateRequest;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.dto.FieldLibraryQuery;
import com.olist.datasets.dto.FieldLibraryRequest;
import com.olist.datasets.vo.FieldBulkResultVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.FieldLibraryFieldVO;
import com.olist.datasets.vo.FieldLibraryVO;
import java.util.List;
import java.util.UUID;

public interface FieldLibraryGroupService {

    List<FieldLibraryVO> list(FieldLibraryQuery query);

    FieldLibraryVO get(UUID id);

    FieldLibraryVO create(FieldLibraryRequest request);

    FieldLibraryVO update(UUID id, FieldLibraryRequest request);

    void delete(UUID id);

    List<FieldLibraryFieldVO> listFields(UUID libraryId);

    FieldLibraryFieldVO createField(UUID libraryId, FieldDefinitionRequest request);

    FieldLibraryFieldVO updateField(
            UUID libraryId, UUID fieldId, FieldDefinitionRequest request);

    void deleteField(UUID libraryId, UUID fieldId);

    FieldBulkResultVO bulkCreateFields(UUID libraryId, FieldBulkCreateRequest request);

    FieldImportResultVO importFromDataset(UUID libraryId, FieldImportRequest request);
}