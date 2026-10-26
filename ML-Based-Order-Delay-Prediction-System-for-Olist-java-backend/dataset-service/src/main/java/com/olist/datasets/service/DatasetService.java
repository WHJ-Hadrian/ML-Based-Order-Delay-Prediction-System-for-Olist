package com.olist.datasets.service;

import com.olist.datasets.dto.DatasetCreateRequest;
import com.olist.datasets.dto.DatasetQuery;
import com.olist.datasets.dto.DatasetTableUpdateRequest;
import com.olist.datasets.dto.DatasetUpdateRequest;
import com.olist.datasets.dto.DatasetUploadRequest;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetReadinessVO;
import com.olist.datasets.vo.DatasetVO;
import com.olist.datasets.vo.DatasetVersionVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.TablePreviewVO;
import com.olist.datasets.vo.TableRowSearchVO;
import com.olist.datasets.vo.TableRowsVO;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface DatasetService {

    DatasetVO create(DatasetCreateRequest request);

    DatasetVO rename(UUID id, DatasetUpdateRequest request);

    void delete(UUID id);

    DatasetDetailVO renameTable(UUID datasetId, UUID tableId, DatasetTableUpdateRequest request);

    DatasetDetailVO deleteTable(UUID datasetId, UUID tableId);

    DatasetDetailVO addFile(UUID id, MultipartFile file);

    DatasetReadinessVO ready(UUID id);

    DatasetReadinessVO readiness(UUID id);

    DatasetVO upload(DatasetUploadRequest request);

    DatasetDetailVO get(UUID id);

    List<DatasetVersionVO> versions(UUID id);

    DatasetDetailVO currentVersion(UUID id);

    TablePreviewVO previewTable(UUID datasetId, UUID tableId, int limit, boolean all);

    TableRowsVO rows(UUID datasetId, UUID tableId, int offset, int limit);

    TableRowSearchVO searchRows(
            UUID datasetId, UUID tableId, String keyword, int columnIndex, long offset, int limit);

    PageVO<DatasetVO> list(DatasetQuery query);
}
