package com.olist.datasets.controller;

import com.olist.datasets.dto.DatasetCreateRequest;
import com.olist.datasets.dto.DatasetQuery;
import com.olist.datasets.dto.DatasetTableUpdateRequest;
import com.olist.datasets.dto.DatasetUpdateRequest;
import com.olist.datasets.dto.DatasetUploadRequest;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.TablePreviewVO;
import com.olist.datasets.vo.TableRowSearchVO;
import com.olist.datasets.vo.TableRowsVO;
import jakarta.validation.Valid;
import java.net.URI;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatasetVO> upload(@Valid @ModelAttribute DatasetUploadRequest request) {
        DatasetVO dataset = datasetService.upload(request);
        return ResponseEntity.created(locationOf(dataset.id())).body(dataset);
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
