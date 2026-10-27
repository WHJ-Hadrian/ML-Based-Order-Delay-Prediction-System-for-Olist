package com.olist.datasets.controller;

import com.olist.datasets.dto.FieldDefinitionQuery;
import com.olist.datasets.dto.FieldDefinitionRequest;
import com.olist.datasets.dto.FieldImportRequest;
import com.olist.datasets.service.FieldLibraryService;
import com.olist.datasets.vo.FieldCatalogItemVO;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.FieldImportResultVO;
import com.olist.datasets.vo.PageVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/fields")
public class FieldLibraryController {

    private final FieldLibraryService fieldLibraryService;

    public FieldLibraryController(FieldLibraryService fieldLibraryService) {
        this.fieldLibraryService = fieldLibraryService;
    }

    @GetMapping
    public PageVO<FieldDefinitionVO> list(@ModelAttribute FieldDefinitionQuery query) {
        return fieldLibraryService.list(query);
    }

    @GetMapping("/{id}")
    public FieldDefinitionVO get(@PathVariable UUID id) {
        return fieldLibraryService.get(id);
    }

    @GetMapping("/catalog")
    public List<FieldCatalogItemVO> catalog(@RequestParam UUID datasetVersionId) {
        return fieldLibraryService.catalog(datasetVersionId);
    }

    @PostMapping("/import")
    public FieldImportResultVO importFields(@Valid @RequestBody FieldImportRequest request) {
        return fieldLibraryService.importFromDataset(request);
    }

    @PostMapping
    public ResponseEntity<FieldDefinitionVO> create(
            @Valid @RequestBody FieldDefinitionRequest request) {
        FieldDefinitionVO created = fieldLibraryService.create(request);
        return ResponseEntity.created(locationOf(created.id())).body(created);
    }

    @PutMapping("/{id}")
    public FieldDefinitionVO update(
            @PathVariable UUID id, @Valid @RequestBody FieldDefinitionRequest request) {
        return fieldLibraryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        fieldLibraryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
