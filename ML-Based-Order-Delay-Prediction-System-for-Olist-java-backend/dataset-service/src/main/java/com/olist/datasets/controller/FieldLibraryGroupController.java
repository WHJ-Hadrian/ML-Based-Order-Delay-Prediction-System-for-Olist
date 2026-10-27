package com.olist.datasets.controller;

import com.olist.datasets.dto.FieldBulkCreateRequest;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.dto.FieldLibraryQuery;
import com.olist.datasets.dto.FieldLibraryRequest;
import com.olist.datasets.service.FieldLibraryGroupService;
import com.olist.datasets.vo.FieldBulkResultVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.FieldLibraryFieldVO;
import com.olist.datasets.vo.FieldLibraryVO;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/field-libraries")
public class FieldLibraryGroupController {

    private final FieldLibraryGroupService fieldLibraryGroupService;

    public FieldLibraryGroupController(FieldLibraryGroupService fieldLibraryGroupService) {
        this.fieldLibraryGroupService = fieldLibraryGroupService;
    }

    @GetMapping
    public List<FieldLibraryVO> list(@ModelAttribute FieldLibraryQuery query) {
        return fieldLibraryGroupService.list(query);
    }

    @GetMapping("/{id}")
    public FieldLibraryVO get(@PathVariable UUID id) {
        return fieldLibraryGroupService.get(id);
    }

    @PostMapping
    public ResponseEntity<FieldLibraryVO> create(
            @Valid @RequestBody FieldLibraryRequest request) {
        FieldLibraryVO created = fieldLibraryGroupService.create(request);
        return ResponseEntity.created(locationOf(created.id())).body(created);
    }

    @PutMapping("/{id}")
    public FieldLibraryVO update(
            @PathVariable UUID id, @Valid @RequestBody FieldLibraryRequest request) {
        return fieldLibraryGroupService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        fieldLibraryGroupService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/fields")
    public List<FieldLibraryFieldVO> listFields(@PathVariable UUID id) {
        return fieldLibraryGroupService.listFields(id);
    }

    @PostMapping("/{id}/fields")
    public ResponseEntity<FieldLibraryFieldVO> createField(
            @PathVariable UUID id, @Valid @RequestBody FieldDefinitionRequest request) {
        FieldLibraryFieldVO created = fieldLibraryGroupService.createField(id, request);
        return ResponseEntity.created(locationOf(created.id())).body(created);
    }

    @PutMapping("/{id}/fields/{fieldId}")
    public FieldLibraryFieldVO updateField(
            @PathVariable UUID id,
            @PathVariable UUID fieldId,
            @Valid @RequestBody FieldDefinitionRequest request) {
        return fieldLibraryGroupService.updateField(id, fieldId, request);
    }

    @DeleteMapping("/{id}/fields/{fieldId}")
    public ResponseEntity<Void> deleteField(
            @PathVariable UUID id, @PathVariable UUID fieldId) {
        fieldLibraryGroupService.deleteField(id, fieldId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fields/bulk")
    public FieldBulkResultVO bulkCreateFields(
            @PathVariable UUID id, @Valid @RequestBody FieldBulkCreateRequest request) {
        return fieldLibraryGroupService.bulkCreateFields(id, request);
    }

    @PostMapping("/{id}/import")
    public FieldImportResultVO importFields(
            @PathVariable UUID id, @Valid @RequestBody FieldImportRequest request) {
        return fieldLibraryGroupService.importFromDataset(id, request);
    }

    private URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}