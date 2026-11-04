package com.olist.jobs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.api.WorkflowRequest;
import com.olist.jobs.api.WorkflowResponse;
import com.olist.jobs.api.WorkflowSummaryResponse;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import java.nio.charset.StandardCharsets;
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
public class WorkflowDefinitionService {
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 400;
    private static final Set<String> NODE_TYPES = Set.of(
            "DATA_SOURCE", "ANALYSIS", "RULE", "FIELD_MAPPING", "TRANSFORM", "FILTER",
            "JOIN", "AGGREGATE", "TRAIN_MODEL", "EVALUATE", "PREDICT", "OUTPUT");
    private static final Set<String> EXECUTABLE_KEYS = Set.of("sql", "script", "python", "javascript", "code");

    private final WorkflowDefinitionRepository repository;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(WorkflowDefinitionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummaryResponse> list(String ownerId) {
        return repository.findTop100ByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(workflow -> new WorkflowSummaryResponse(workflow.getId(), workflow.getName(),
                        workflow.getRevision(), workflow.getCreatedAt(), workflow.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(String ownerId, UUID id) { return response(requireOwned(ownerId, id)); }

    @Transactional
    public WorkflowResponse create(String ownerId, WorkflowRequest request) {
        String name = normalizeName(request.name());
        String document = serializeAndValidate(request.document());
        Instant now = Instant.now();
        return response(repository.save(new WorkflowDefinition(UUID.randomUUID(), ownerId, name, 1,
                document, now, now)));
    }

    @Transactional
    public WorkflowResponse update(String ownerId, UUID id, WorkflowRequest request) {
        WorkflowDefinition workflow = repository.lockByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> notFound());
        if (request.expectedRevision() == null || request.expectedRevision() != workflow.getRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作流已更新，请重新载入后再保存");
        }
        String document = serializeAndValidate(request.document());
        workflow.update(normalizeName(request.name()), document, workflow.getRevision() + 1, Instant.now());
        return response(repository.save(workflow));
    }

    @Transactional
    public WorkflowResponse duplicate(String ownerId, UUID id, String requestedName) {
        WorkflowDefinition source = requireOwned(ownerId, id);
        String name = requestedName == null || requestedName.isBlank()
                ? trimName(source.getName() + " 副本") : normalizeName(requestedName);
        Instant now = Instant.now();
        return response(repository.save(new WorkflowDefinition(UUID.randomUUID(), ownerId, name, 1,
                source.getDocumentJson(), now, now)));
    }

    @Transactional
    public void delete(String ownerId, UUID id) {
        if (repository.deleteByIdAndOwnerId(id, ownerId) != 1) throw notFound();
    }

    private WorkflowDefinition requireOwned(String ownerId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到此工作流");
    }

    private String serializeAndValidate(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw badRequest("工作流文档必须是对象");
        }
        JsonNode nodes = document.path("nodes");
        JsonNode edges = document.path("edges");
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
            throw badRequest("工作流节点或连线无效，或超出数量限制");
        }
        validateNoExecutableContent(document);
        Set<String> nodeIds = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> connections = new HashSet<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) throw badRequest("工作流节点结构无效");
            String id = node.path("id").asText("").trim();
            String type = node.path("type").asText("").trim();
            JsonNode position = node.path("position");
            if (id.isEmpty() || id.length() > 100 || !nodeIds.add(id) || !NODE_TYPES.contains(type)
                    || !position.path("x").isNumber() || !position.path("y").isNumber()
                    || Math.abs(position.path("x").asDouble()) > 1_000_000
                    || Math.abs(position.path("y").asDouble()) > 1_000_000) {
                throw badRequest("工作流包含无效或重复节点");
            }
            if (!node.path("data").isObject()) throw badRequest("工作流节点缺少有效配置");
            indegree.put(id, 0);
            graph.put(id, new ArrayList<>());
        }
        for (JsonNode edge : edges) {
            if (!edge.isObject()) throw badRequest("工作流连线结构无效");
            String id = edge.path("id").asText("").trim();
            String source = edge.path("source").asText("");
            String target = edge.path("target").asText("");
            String sourceHandle = edge.path("sourceHandle").asText("output");
            String targetHandle = edge.path("targetHandle").asText("input");
            String connection = source + "|" + sourceHandle + "|" + target + "|" + targetHandle;
            if (id.isEmpty() || id.length() > 150 || !edgeIds.add(id)
                    || !nodeIds.contains(source) || !nodeIds.contains(target) || source.equals(target)
                    || !sourceHandle.equals("output") || !targetHandle.equals("input")
                    || !connections.add(connection)) {
                throw badRequest("工作流连线必须使用有效节点和兼容端口，且不能重复");
            }
            graph.get(source).add(target);
            indegree.compute(target, (key, value) -> value + 1);
        }
        ensureAcyclic(graph, indegree);
        try {
            String json = objectMapper.writeValueAsString(document);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "工作流文档超过 1 MiB");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw badRequest("工作流文档无法解析");
        }
    }

    private void validateNoExecutableContent(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (EXECUTABLE_KEYS.contains(entry.getKey().toLowerCase())) {
                    throw badRequest("工作流不接受 SQL 或任意脚本字段");
                }
                validateNoExecutableContent(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::validateNoExecutableContent);
        }
    }

    private void ensureAcyclic(Map<String, List<String>> graph, Map<String, Integer> indegree) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((node, degree) -> { if (degree == 0) queue.add(node); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String next : graph.get(current)) {
                int degree = indegree.compute(next, (key, value) -> value - 1);
                if (degree == 0) queue.addLast(next);
            }
        }
        if (visited != graph.size()) throw badRequest("工作流连线形成了循环");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw badRequest("工作流名称不能为空且不能超过 255 个字符");
        }
        return normalized;
    }

    private String trimName(String name) { return name.length() <= 255 ? name : name.substring(0, 255); }

    private WorkflowResponse response(WorkflowDefinition workflow) {
        try {
            return new WorkflowResponse(workflow.getId(), workflow.getName(), workflow.getRevision(),
                    objectMapper.readTree(workflow.getDocumentJson()), workflow.getCreatedAt(), workflow.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted workflow contains invalid JSON", exception);
        }
    }
}
