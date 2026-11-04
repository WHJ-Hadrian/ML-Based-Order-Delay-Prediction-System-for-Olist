package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelWorkflowResponse;
import com.olist.models.api.ModelWorkflowRequest;
import com.olist.models.api.ModelWorkflowSummaryResponse;
import com.olist.models.entity.ModelWorkflow;
import com.olist.models.repository.ModelWorkflowRepository;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelWorkflowService {

    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 400;
    private static final Set<String> NODE_TYPES = Set.of(
            "DATA_SOURCE", "FEATURE_SELECTION", "RULE_WORKBOOK", "TRANSFORM", "FILTER",
            "JOIN", "AGGREGATE", "DERIVE", "TRAIN_MODEL", "EVALUATE");
    private static final Set<String> EXECUTABLE_KEYS = Set.of("sql", "script", "python", "javascript", "code");

    private final ModelWorkflowRepository repository;
    private final ObjectMapper objectMapper;

    public ModelWorkflowService(ModelWorkflowRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ModelWorkflowSummaryResponse> list(String ownerId) {
        return repository.findTop100ByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(workflow -> new ModelWorkflowSummaryResponse(workflow.getId(), workflow.getName(),
                        workflow.getRevision(), workflow.getCreatedAt(), workflow.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ModelWorkflowResponse get(String ownerId, UUID id) {
        return response(requireOwned(ownerId, id));
    }

    @Transactional
    public ModelWorkflowResponse create(String ownerId, ModelWorkflowRequest request) {
        String name = normalizeName(request.name());
        String document = serializeAndValidate(request.document());
        Instant now = Instant.now();
        ModelWorkflow workflow = repository.save(new ModelWorkflow(
                UUID.randomUUID(), ownerId, name, 1, document, now, now));
        return response(workflow);
    }

    @Transactional
    public ModelWorkflowResponse update(String ownerId, UUID id, ModelWorkflowRequest request) {
        ModelWorkflow workflow = repository.lockByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到此工作流"));
        if (request.expectedRevision() != null && request.expectedRevision() != workflow.getRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作流已在其他位置更新，请重新载入后再保存");
        }
        String document = serializeAndValidate(request.document());
        workflow.update(normalizeName(request.name()), document, workflow.getRevision() + 1, Instant.now());
        return response(repository.save(workflow));
    }

    @Transactional
    public ModelWorkflowResponse duplicate(String ownerId, UUID id, String requestedName) {
        ModelWorkflow source = requireOwned(ownerId, id);
        String name = requestedName == null || requestedName.isBlank()
                ? trimName(source.getName() + " 副本")
                : normalizeName(requestedName);
        Instant now = Instant.now();
        ModelWorkflow copy = repository.save(new ModelWorkflow(
                UUID.randomUUID(), ownerId, name, 1, source.getDocumentJson(), now, now));
        return response(copy);
    }

    private ModelWorkflow requireOwned(String ownerId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到此工作流"));
    }

    private String serializeAndValidate(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流文档必须是对象");
        }
        JsonNode nodes = document.path("nodes");
        JsonNode edges = document.path("edges");
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流节点或连线结构无效，或超出数量限制");
        }
        validateNoExecutableContent(document);
        Set<String> nodeIds = new HashSet<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("").trim();
            String type = node.path("type").asText("").trim();
            if (id.isEmpty() || id.length() > 100 || !nodeIds.add(id) || !NODE_TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流包含无效或重复的节点");
            }
            indegree.put(id, 0);
            graph.put(id, new ArrayList<>());
        }
        for (JsonNode edge : edges) {
            String source = edge.path("source").asText("");
            String target = edge.path("target").asText("");
            if (!nodeIds.contains(source) || !nodeIds.contains(target) || source.equals(target)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流连线必须连接两个不同的已存在节点");
            }
            graph.get(source).add(target);
            indegree.compute(target, (key, value) -> value + 1);
        }
        ensureAcyclic(graph, indegree);
        try {
            String json = objectMapper.writeValueAsString(document);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "工作流文档超过 1 MiB");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流文档无法解析");
        }
    }

    private void validateNoExecutableContent(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (EXECUTABLE_KEYS.contains(entry.getKey().toLowerCase())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流不接受 SQL 或任意脚本字段");
                }
                validateNoExecutableContent(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::validateNoExecutableContent);
        }
    }

    private void ensureAcyclic(Map<String, List<String>> graph, Map<String, Integer> indegree) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) queue.add(node);
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String next : graph.get(current)) {
                int degree = indegree.compute(next, (key, value) -> value - 1);
                if (degree == 0) queue.addLast(next);
            }
        }
        if (visited != graph.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流连线形成了循环");
        }
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流名称不能为空且不能超过 255 个字符");
        }
        return normalized;
    }

    private String trimName(String name) {
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private ModelWorkflowResponse response(ModelWorkflow workflow) {
        try {
            return new ModelWorkflowResponse(workflow.getId(), workflow.getName(), workflow.getRevision(),
                    objectMapper.readTree(workflow.getDocumentJson()), workflow.getCreatedAt(), workflow.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted model workflow contains invalid JSON", exception);
        }
    }
}
