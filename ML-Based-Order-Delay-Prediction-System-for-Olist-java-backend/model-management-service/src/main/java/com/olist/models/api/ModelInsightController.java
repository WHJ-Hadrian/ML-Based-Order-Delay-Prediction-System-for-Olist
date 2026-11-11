package com.olist.models.api;

import com.olist.models.service.ModelInsightService;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/models/versions/{id}")
public class ModelInsightController {

    private final ModelInsightService service;

    public ModelInsightController(ModelInsightService service) {
        this.service = service;
    }

    @PostMapping("/explanations")
    public Map<String, Object> explain(
            @PathVariable UUID id,
            @Valid @RequestBody ModelExplanationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.explain(jwt.getSubject(), id, request);
    }

    @PostMapping("/reports")
    public ResponseEntity<StreamingResponseBody> report(
            @PathVariable UUID id,
            @Valid @RequestBody ModelReportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String ownerId = jwt.getSubject();
        StreamingResponseBody body = output -> {
            service.report(ownerId, id, request, output);
            output.flush();
        };
        return ResponseEntity.ok()
                .contentType(mediaType(request.format()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(request.title(), request.format()))
                .body(body);
    }

    @GetMapping("/reports")
    public List<ModelReportRecordResponse> records(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.records(jwt.getSubject(), id);
    }

    private MediaType mediaType(String format) {
        return switch (format) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "docx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "html" -> MediaType.parseMediaType("text/html; charset=utf-8");
            case "json" -> MediaType.APPLICATION_JSON;
            default -> MediaType.parseMediaType("text/csv; charset=utf-8");
        };
    }

    private String contentDisposition(String title, String format) {
        String ascii = "model-report." + format;
        String encoded = URLEncoder.encode(title + "." + format, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
