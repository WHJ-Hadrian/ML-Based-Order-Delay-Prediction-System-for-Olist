package com.olist.datasets.controller;

import com.olist.datasets.dto.DatasetCreateRequest;
import com.olist.datasets.dto.DatasetQuery;
import com.olist.datasets.dto.DatasetTableUpdateRequest;
import com.olist.datasets.dto.DatasetUpdateRequest;
import com.olist.datasets.dto.DatasetUploadRequest;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.DatasetColumnVO;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetReadinessVO;
import com.olist.datasets.vo.DatasetVO;
import com.olist.datasets.vo.DatasetVersionVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.TablePreviewVO;
import com.olist.datasets.vo.TableRowSearchVO;
import com.olist.datasets.vo.TableRowsVO;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DatasetVO> create(@Valid @RequestBody DatasetCreateRequest request) {
        DatasetVO dataset = datasetService.create(request);
        return ResponseEntity.created(locationOf(dataset.id())).body(dataset);
    }

    @PatchMapping("/{id}")
    public DatasetVO rename(@PathVariable UUID id, @Valid @RequestBody DatasetUpdateRequest request) {
        return datasetService.rename(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        datasetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{datasetId}/tables/{tableId}")
    public DatasetDetailVO renameTable(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @Valid @RequestBody DatasetTableUpdateRequest request) {
        return datasetService.renameTable(datasetId, tableId, request);
    }

    @DeleteMapping("/{datasetId}/tables/{tableId}")
    public DatasetDetailVO deleteTable(@PathVariable UUID datasetId, @PathVariable UUID tableId) {
        return datasetService.deleteTable(datasetId, tableId);
    }

    @PostMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatasetDetailVO addFile(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return datasetService.addFile(id, file);
    }

    @PostMapping("/{id}/ready")
    public DatasetReadinessVO ready(@PathVariable UUID id) {
        return datasetService.ready(id);
    }

    @GetMapping("/{id}/readiness")
    public DatasetReadinessVO readiness(@PathVariable UUID id) {
        return datasetService.readiness(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatasetVO> upload(@Valid @ModelAttribute DatasetUploadRequest request) {
        DatasetVO dataset = datasetService.upload(request);
        return ResponseEntity.created(locationOf(dataset.id())).body(dataset);
    }

    @GetMapping("/{id}/columns")
    public List<DatasetColumnVO> columns(@PathVariable UUID id) {
        return datasetService.columns(id);
    }

    @GetMapping("/{datasetId}/tables/{tableId}/preview")
    public TablePreviewVO previewTable(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "mode", defaultValue = "preview") String mode) {
        return datasetService.previewTable(datasetId, tableId, limit, "all".equalsIgnoreCase(mode));
    }

    @GetMapping("/{datasetId}/tables/{tableId}/rows")
    public TableRowsVO rows(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "200") int limit) {
        return datasetService.rows(datasetId, tableId, offset, limit);
    }

    @GetMapping("/{datasetId}/tables/{tableId}/search")
    public TableRowSearchVO searchRows(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam String keyword,
            @RequestParam(name = "column", defaultValue = "-1") int columnIndex,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "50") int limit) {
        return datasetService.searchRows(datasetId, tableId, keyword, columnIndex, offset, limit);
    }

    @GetMapping("/{id}")
    public DatasetDetailVO get(@PathVariable UUID id) {
        return datasetService.get(id);
    }

    @GetMapping("/{id}/versions")
    public java.util.List<DatasetVersionVO> versions(@PathVariable UUID id) {
        return datasetService.versions(id);
    }

    @GetMapping("/{id}/versions/current")
    public DatasetDetailVO currentVersion(@PathVariable UUID id) {
        return datasetService.currentVersion(id);
    }

    @GetMapping("/{id}/catalog")
    public DatasetCatalogVO catalog(@PathVariable UUID id) {
        return datasetService.catalog(id);
    }

    @GetMapping
    public PageVO<DatasetVO> list(@ModelAttribute DatasetQuery query) {
        return datasetService.list(query);
    }

    private URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
