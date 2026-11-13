package com.olist.models.api;

import com.olist.models.service.ModelPredictionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 单笔预测：提交后立即返回任务，后台执行，历史落库。 */
@RestController
@RequestMapping("/api/v1/models/predictions")
public class ModelPredictionController {

    private final ModelPredictionService service;

    public ModelPredictionController(ModelPredictionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ModelPredictionResponse> submit(
            @Valid @RequestBody ModelPredictionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ModelPredictionResponse created = service.submit(jwt.getSubject(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(location).body(created);
    }

    @GetMapping
    public List<ModelPredictionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public ModelPredictionResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public Map<String, Object> clear(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("deleted", service.clear(jwt.getSubject()));
    }
}
