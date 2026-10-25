package com.olist.datasets.controller;

import com.olist.datasets.dto.CleaningRuleEnabledRequest;
import com.olist.datasets.dto.CleaningRuleQuery;
import com.olist.datasets.dto.CleaningRuleRequest;
import com.olist.datasets.service.RuleLibraryService;
import com.olist.datasets.vo.CleaningRuleVO;
import com.olist.datasets.vo.PageVO;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleLibraryController {

    private final RuleLibraryService ruleLibraryService;

    public RuleLibraryController(RuleLibraryService ruleLibraryService) {
        this.ruleLibraryService = ruleLibraryService;
    }

    @GetMapping
    public PageVO<CleaningRuleVO> list(@ModelAttribute CleaningRuleQuery query) {
        return ruleLibraryService.list(query);
    }

    @GetMapping("/{id}")
    public CleaningRuleVO get(@PathVariable UUID id) {
        return ruleLibraryService.get(id);
    }

    @PostMapping
    public ResponseEntity<CleaningRuleVO> create(@Valid @RequestBody CleaningRuleRequest request) {
        CleaningRuleVO created = ruleLibraryService.create(request);
        return ResponseEntity.created(locationOf(created.id())).body(created);
    }

    @PutMapping("/{id}")
    public CleaningRuleVO update(
            @PathVariable UUID id, @Valid @RequestBody CleaningRuleRequest request) {
        return ruleLibraryService.update(id, request);
    }

    @PatchMapping("/{id}/enabled")
    public CleaningRuleVO setEnabled(
            @PathVariable UUID id, @Valid @RequestBody CleaningRuleEnabledRequest request) {
        return ruleLibraryService.setEnabled(id, request.getEnabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ruleLibraryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
