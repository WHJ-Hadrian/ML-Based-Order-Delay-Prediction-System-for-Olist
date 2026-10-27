package com.olist.datasets.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olist.datasets.entity.DatasetColumn;
import com.olist.datasets.entity.DatasetFieldBinding;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.mapper.DatasetColumnMapper;
import com.olist.datasets.mapper.DatasetFieldBindingMapper;
import com.olist.datasets.mapper.DatasetTableMapper;
import com.olist.datasets.vo.RuleValidationIssueVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RuleWorkbookCompiler {

    private static final Set<String> NODE_KINDS = Set.of(
            "CHECK", "TRANSFORM", "PROJECT", "FILTER", "DEDUPE", "JOIN",
            "AGGREGATE", "DERIVE", "GATE", "OUTPUT");
    private static final Map<String, String> DEFAULT_KIND = Map.of(
            "FIELD_RULES", "CHECK",
            "TRANSFORMS", "TRANSFORM",
            "FILTERS", "FILTER",
            "PROJECTS", "PROJECT",
            "DEDUPES", "DEDUPE",
            "RELATIONS", "JOIN",
            "AGGREGATES", "AGGREGATE",
            "DERIVED", "DERIVE",
            "GATES", "GATE",
            "OUTPUTS", "OUTPUT");
    private static final Map<String, String> DEFAULT_STAGE = Map.of(
            "CHECK", "CHECK",
            "TRANSFORM", "CLEAN",
            "PROJECT", "CLEAN",
            "FILTER", "CLEAN",
            "DEDUPE", "CLEAN",
            "JOIN", "RELATE",
            "AGGREGATE", "ENRICH",
            "DERIVE", "FEATURE",
            "GATE", "VALIDATE",
            "OUTPUT", "OUTPUT");
    private static final Set<String> STAGES = Set.of(
            "PREPARE", "CHECK", "CLEAN", "RELATE", "ENRICH", "FEATURE", "VALIDATE", "OUTPUT");
    private static final Set<String> SCOPES = Set.of(
            "ROW", "FIELD_AGGREGATE", "RELATION_AGGREGATE", "DATASET_AGGREGATE");
    private static final Set<String> EFFECTS = Set.of(
            "OBSERVE", "WARN", "FAIL_RUN", "BLOCK_PUBLISH", "QUARANTINE_ROWS", "DROP_ROWS", "FLAG_ROWS");
    private static final Set<String> ROW_EFFECTS = Set.of(
            "QUARANTINE_ROWS", "DROP_ROWS", "FLAG_ROWS");
    private static final Set<String> TRANSFORM_OPERATIONS = Set.of(
            "TRIM", "LOWER", "UPPER", "CAST", "REPLACE", "REGEX_REPLACE", "MAP_VALUES",
            "FILL_VALUE", "COALESCE", "DATE_PARSE", "CLIP_OUTLIER", "MASK",
            "SPLIT_COLUMN", "MERGE_COLUMNS");
    private static final Set<String> JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");
    private static final Set<String> CARDINALITIES = Set.of("1:1", "1:N", "N:1", "N:N");
    private static final Set<String> AGGREGATE_OPS = Set.of(
            "COUNT", "COUNT_DISTINCT", "SUM", "AVG", "MIN", "MAX", "MEDIAN");
    private static final Set<String> COMPARATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE");
    private static final Set<String> CELL_TYPES = Set.of(
            "DATASET_REF", "INPUT_REF", "TABLE_REF", "FIELD_REF", "FIELD_LIST_REF", "NODE_OUTPUT_REF",
            "RELATION_REF", "NODE_KIND", "PREDICATE", "EFFECT", "OPERATION", "PARAM_OBJECT",
            "JOIN_TYPE", "CARDINALITY", "VALUE", "VALUE_LIST", "DATE_VALUE", "NUMBER", "BOOLEAN",
            "ENUM", "TEXT", "TAGS", "GROUP", "ORDER", "OUTPUT_ALIAS");
    private static final Set<String> CUSTOM_CELL_TYPES = Set.of(
            "TEXT", "NUMBER", "BOOLEAN", "ENUM", "DATE_VALUE", "TAGS");
    private static final Set<String> PREDICATE_OPS = Set.of(
            "IS_NULL", "IS_EMPTY", "IS_BLANK", "EQ", "NE", "GT", "GTE", "LT", "LTE",
            "BETWEEN", "IN", "NOT_IN", "REGEX_MATCH", "DATE_PARSEABLE", "DATE_BEFORE",
            "DATE_AFTER", "DATE_ORDER_INVALID", "DUPLICATE", "UNIQUE", "DUPLICATE_RATE_GT",
            "FK_EXISTS", "JOIN_MATCH_RATE_LT", "JOIN_FANOUT_GT", "MISSING_RATE_GT",
            "ROW_COUNT_LT", "CATEGORY_RATIO_LT", "DISTINCT_COUNT_LT", "AND", "OR", "NOT");
    private static final int MAX_PREDICATE_DEPTH = 24;

    private final DatasetTableMapper datasetTableMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetFieldBindingMapper datasetFieldBindingMapper;
    private final ObjectMapper objectMapper;

    public RuleWorkbookCompiler(
            DatasetTableMapper datasetTableMapper,
            DatasetColumnMapper datasetColumnMapper,
            DatasetFieldBindingMapper datasetFieldBindingMapper,
            ObjectMapper objectMapper) {
        this.datasetTableMapper = datasetTableMapper;
        this.datasetColumnMapper = datasetColumnMapper;
        this.datasetFieldBindingMapper = datasetFieldBindingMapper;
        this.objectMapper = objectMapper;
    }

    public RuleCompilation compile(JsonNode document, UUID datasetVersionId) {
        CompilationContext context = new CompilationContext(datasetVersionId);
        if (datasetVersionId == null) {
            error(context, null, "DATASET_REQUIRED", "规则工作簿必须绑定数据集版本", null);
        } else {
            for (DatasetTable table : datasetTableMapper.findByDatasetId(datasetVersionId)) {
                context.tablesById.put(table.getId(), table);
            }
            for (DatasetColumn column : datasetColumnMapper.findByDatasetVersionId(datasetVersionId)) {
                context.columnsById.put(column.getId(), column);
                context.columnsByTable
                        .computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>())
                        .add(column);
            }
        }
        compileDocument(document, context);
        if (context.nodes.isEmpty()) {
            error(context, null, "NO_ENABLED_NODES", "规则库还没有启用任何规则节点", "添加至少一条规则后再校验或发布");
        }
        validateGraph(context);
        ObjectNode plan = buildPlan(document, context);
        boolean valid = context.issues.stream()
                .noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new RuleCompilation(valid, context.issues, context.nodes.size(), plan);
    }

    private void compileDocument(JsonNode document, CompilationContext context) {
        if (document == null || !document.isObject()) {
            error(context, null, "DOCUMENT_INVALID", "规则文档必须是 JSON 对象", "重新加载工作簿或检查请求正文");
            return;
        }
        String documentDatasetId = text(document, "datasetVersionId");
        if (documentDatasetId != null && !documentDatasetId.equals(context.datasetVersionId.toString())) {
            error(context, null, "DATASET_MISMATCH",
                    "规则文档绑定的数据集与工作簿不一致，不能隐式混合多个数据集",
                    "将规则工作表绑定到当前工作簿的数据集");
        }
        JsonNode schemaVersion = document.path("schemaVersion");
        if (!schemaVersion.isMissingNode() && schemaVersion.asInt() != 1) {
            error(context, null, "SCHEMA_VERSION_UNSUPPORTED", "当前只支持规则文档 schemaVersion=1", null);
        }
        JsonNode sheets = document.path("sheets");
        if (!sheets.isArray()) {
            error(context, null, "SHEETS_REQUIRED", "规则文档缺少 sheets 数组", null);
            return;
        }
        Set<String> sheetKeys = new HashSet<>();
        for (JsonNode sheet : sheets) {
            if (!sheet.isObject()) {
                error(context, null, "SHEET_INVALID", "工作表必须是对象", null);
                continue;
            }
            String sheetKey = text(sheet, "key");
            if (sheetKey == null || sheetKey.isBlank()) {
                error(context, null, "SHEET_KEY_REQUIRED", "工作表缺少稳定 key", null);
                continue;
            }
            if (!sheetKeys.add(sheetKey)) {
                error(context, new RowContext(sheetKey, null, null),
                        "SHEET_KEY_DUPLICATE", "工作表 key「" + sheetKey + "」重复", "使用唯一的工作表 key");
            }
            validateColumns(sheet, context, sheetKey);
            JsonNode rows = sheet.path("rows");
            if (!rows.isArray()) {
                error(context, new RowContext(sheetKey, null, null),
                        "ROWS_REQUIRED", "工作表 rows 必须是数组", null);
                continue;
            }
            Set<String> rowIds = new HashSet<>();
            String sheetKind = upper(text(sheet, "kind"));
            int rowIndex = 0;
            for (JsonNode row : rows) {
                rowIndex++;
                String rowId = text(row, "id");
                if (rowId == null || rowId.isBlank()) {
                    rowId = "row_" + rowIndex;
                }
                if (!rowIds.add(rowId)) {
                    error(context, new RowContext(sheetKey, rowId, null),
                            "ROW_ID_DUPLICATE", "行 ID「" + rowId + "」在工作表内重复", null);
                }
                compileRow(row, sheetKey, sheetKind, rowId, context);
            }
        }
    }

    private void validateColumns(JsonNode sheet, CompilationContext context, String sheetKey) {
        JsonNode columns = sheet.path("columns");
        if (columns.isMissingNode() || columns.isNull()) {
            return;
        }
        if (!columns.isArray()) {
            error(context, new RowContext(sheetKey, null, null),
                    "COLUMNS_INVALID", "工作表 columns 必须是数组", null);
            return;
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode column : columns) {
            String key = text(column, "key");
            if (key == null || key.isBlank()) {
                error(context, new RowContext(sheetKey, null, null),
                        "COLUMN_KEY_REQUIRED", "列定义缺少 key", null);
                continue;
            }
            if (!keys.add(key)) {
                error(context, new RowContext(sheetKey, null, key),
                        "COLUMN_KEY_DUPLICATE", "列 key「" + key + "」重复", null);
            }
            String cellType = upper(text(column, "cellType"));
            if (cellType == null || !CELL_TYPES.contains(cellType)) {
                error(context, new RowContext(sheetKey, null, key),
                        "COLUMN_CELL_TYPE_INVALID", "未知的单元格类型「" + cellType + "」", null);
            }
            if (key.startsWith("custom_") && cellType != null && !CUSTOM_CELL_TYPES.contains(cellType)) {
                error(context, new RowContext(sheetKey, null, key),
                        "CUSTOM_COLUMN_TYPE_INVALID",
                        "自定义列只能使用文本、数字、布尔、枚举、日期或标签类型",
                        null);
            }
        }
    }

    private void compileRow(
            JsonNode row, String sheetKey, String sheetKind, String rowId, CompilationContext context) {
        RowContext rowContext = new RowContext(sheetKey, rowId, null);
        if (!row.isObject()) {
            error(context, rowContext, "ROW_INVALID", "规则行必须是对象", null);
            return;
        }
        if (row.has("enabled") && !row.path("enabled").asBoolean(true)) {
            return;
        }
        JsonNode cells = row.path("cells").isObject() ? row.path("cells") : row;
        String kind = upper(firstText(cells, "nodeKind", "kind"));
        if (kind == null) {
            kind = DEFAULT_KIND.get(sheetKind);
        }
        if (kind == null) {
            error(context, rowContext, "NODE_KIND_REQUIRED",
                    "无法确定规则节点类型，请选择节点类型或使用标准工作表", null);
            return;
        }
        if (!NODE_KINDS.contains(kind)) {
            error(context, rowContext, "NODE_KIND_UNSUPPORTED", "第一版不支持节点类型「" + kind + "」", null);
            return;
        }

        String nodeId = firstText(cells, "nodeId");
        if (nodeId == null) {
            nodeId = sheetKey + ":" + rowId;
        }
        String stage = upper(firstText(cells, "stage"));
        if (stage == null) {
            stage = DEFAULT_STAGE.get(kind);
        }
        String name = firstText(cells, "name", "title");
        if (name == null) {
            name = kind + " " + rowId;
        }

        ArrayNode inputs = objectMapper.createArrayNode();
        Map<String, UUID> inputTables = new LinkedHashMap<>();
        List<String> inputAliases = new ArrayList<>();
        JsonNode explicitInputs = cells.path("inputs");
        if (explicitInputs.isArray()) {
            for (JsonNode rawInput : explicitInputs) {
                addInput(rawInput, kind, context, rowContext, inputs, inputTables, inputAliases);
            }
        } else {
            List<String> inputKeys = "JOIN".equals(kind)
                    ? List.of("leftInput", "rightInput")
                    : List.of("table", "input");
            for (String key : inputKeys) {
                if (cells.has(key) && looksLikeDataRef(cells.get(key))) {
                    addInput(cells.get(key), kind, context, rowContext, inputs, inputTables, inputAliases);
                }
            }
        }
        if (inputs.isEmpty() && !"GATE".equals(kind)) {
            error(context, rowContext, "INPUT_REQUIRED", "节点「" + nodeId + "」缺少输入表或上游节点", null);
        }

        ObjectNode config = buildConfig(cells, kind, inputAliases);
        String outputAlias = text(config, "outputAlias");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", nodeId);
        node.put("kind", kind);
        node.put("name", name);
        node.put("stage", stage);
        node.put("enabled", true);
        node.set("inputs", inputs);
        node.set("config", config);
        node.set("dependsOn", parseDependsOn(cells));
        node.set("policy", buildPolicy(cells));
        if (outputAlias != null) {
            node.set("output", objectMapper.createObjectNode().put("alias", outputAlias));
        } else {
            node.putNull("output");
        }
        context.nodes.add(node);
        ObjectNode previous = context.nodesById.putIfAbsent(nodeId, node);
        if (previous != null) {
            error(context, rowContext, "NODE_ID_DUPLICATE", "节点 ID「" + nodeId + "」重复", "为节点设置唯一 ID");
        }
        if (!STAGES.contains(stage)) {
            error(context, rowContext, "STAGE_INVALID", "未知执行阶段「" + stage + "」", null);
        }
        context.contextByNodeId.put(nodeId, rowContext);
    }
    private void addInput(
            JsonNode rawInput,
            String kind,
            CompilationContext context,
            RowContext rowContext,
            ArrayNode inputs,
            Map<String, UUID> inputTables,
            List<String> inputAliases) {
        ObjectNode input;
        if (rawInput.isTextual()) {
            input = objectMapper.createObjectNode();
            input.put("type", "TABLE");
            input.put("tableId", rawInput.asText());
        } else if (rawInput.isObject()) {
            input = ((ObjectNode) rawInput).deepCopy();
        } else {
            error(context, rowContext, "INPUT_INVALID", "节点输入必须是表引用或节点输出引用", null);
            return;
        }

        String type = upper(text(input, "type"));
        if (type == null) {
            if (text(input, "tableId") != null) {
                type = "TABLE";
            } else if (text(input, "nodeId") != null) {
                type = "NODE_OUTPUT";
            }
        }
        String alias;
        if ("TABLE".equals(type)) {
            UUID tableId = uuid(input, "tableId");
            DatasetTable table = tableId == null ? null : context.tablesById.get(tableId);
            if (table == null) {
                error(context, rowContext, "TABLE_NOT_FOUND",
                        "节点引用了不存在或不属于当前数据集的表", "从当前数据集中重新选择数据表");
                return;
            }
            input.put("type", "TABLE");
            input.put("datasetVersionId", context.datasetVersionId.toString());
            alias = text(input, "alias");
            if (alias == null) {
                alias = sanitizeAlias(table.getTableName());
                input.put("alias", alias);
            }
            inputTables.put(alias, tableId);
        } else if ("NODE_OUTPUT".equals(type)) {
            String upstreamNodeId = text(input, "nodeId");
            if (upstreamNodeId == null) {
                error(context, rowContext, "NODE_OUTPUT_ID_REQUIRED", "上游节点输出引用缺少 nodeId", null);
                return;
            }
            alias = text(input, "outputAlias");
            if (alias == null) {
                alias = upstreamNodeId;
                input.put("outputAlias", alias);
            }
            input.put("type", "NODE_OUTPUT");
            inputTables.put(alias, null);
        } else {
            error(context, rowContext, "INPUT_TYPE_INVALID", "不支持的输入引用类型「" + type + "」", null);
            return;
        }
        input.put("alias", alias);
        if (inputAliases.contains(alias)) {
            error(context, rowContext, "INPUT_ALIAS_DUPLICATE", "节点输入别名「" + alias + "」重复", null);
            return;
        }
        inputAliases.add(alias);
        inputs.add(input);
    }

    private boolean looksLikeDataRef(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return parseUuid(value.asText()) != null;
        }
        if (!value.isObject()) {
            return false;
        }
        String type = upper(text(value, "type"));
        return "TABLE".equals(type)
                || "NODE_OUTPUT".equals(type)
                || value.has("tableId")
                || value.has("nodeId");
    }

    private ObjectNode buildConfig(JsonNode cells, String kind, List<String> inputAliases) {
        ObjectNode config = cells.path("config").isObject()
                ? ((ObjectNode) cells.path("config")).deepCopy()
                : objectMapper.createObjectNode();
        List<String> names = List.of(
                "scope", "predicate", "effect", "effectParams", "target", "operation", "params",
                "condition", "outputField", "dataType", "expression", "replaceExisting",
                "leftAlias", "rightAlias", "leftKeys", "rightKeys", "joinType", "cardinality",
                "duplicatePolicy", "guards", "groupBy", "metrics", "having", "metricRef",
                "comparator", "threshold", "mode", "format", "name", "partitionBy",
                "overwritePolicy", "keys", "keep", "outputAlias", "select", "rename", "drop");
        for (String name : names) {
            if (!config.has(name) && cells.has(name)) {
                config.set(name, cells.get(name).deepCopy());
            }
        }
        if (!config.has("schemaVersion")) {
            config.put("schemaVersion", 1);
        }
        if ("CHECK".equals(kind) || "FILTER".equals(kind)) {
            if (!config.has("predicate") && cells.has("condition")) {
                config.set("predicate", cells.get("condition").deepCopy());
            }
        }
        if ("CHECK".equals(kind) && !config.has("scope")) {
            config.put("scope", "ROW");
        }
        if ("TRANSFORM".equals(kind) && !config.has("target") && cells.has("field")) {
            config.set("target", cells.get("field").deepCopy());
        }
        if ("DEDUPE".equals(kind) && !config.has("keys") && cells.has("fields")) {
            config.set("keys", cells.get("fields").deepCopy());
        }
        if ("AGGREGATE".equals(kind) && !config.has("groupBy") && cells.has("groupFields")) {
            config.set("groupBy", cells.get("groupFields").deepCopy());
        }
        if ("JOIN".equals(kind)) {
            if (!config.has("leftAlias") && inputAliases.size() > 0) {
                config.put("leftAlias", inputAliases.get(0));
            }
            if (!config.has("rightAlias") && inputAliases.size() > 1) {
                config.put("rightAlias", inputAliases.get(1));
            }
            if (!config.has("leftKeys") && cells.has("leftField")) {
                config.putArray("leftKeys").add(cells.get("leftField").deepCopy());
            }
            if (!config.has("rightKeys") && cells.has("rightField")) {
                config.putArray("rightKeys").add(cells.get("rightField").deepCopy());
            }
        }
        if (!config.has("outputAlias") && cells.has("output")) {
            JsonNode output = cells.get("output");
            String outputAlias = output.isTextual() ? output.asText() : text(output, "alias");
            if (outputAlias != null) {
                config.put("outputAlias", outputAlias);
            }
        }
        return config;
    }

    private ArrayNode parseDependsOn(JsonNode cells) {
        ArrayNode dependencies = objectMapper.createArrayNode();
        JsonNode raw = cells.path("dependsOn");
        if (raw.isArray()) {
            for (JsonNode item : raw) {
                String dependency = text(item);
                if (dependency != null && !dependencies.toString().contains("\"" + dependency + "\"")) {
                    dependencies.add(dependency);
                }
            }
        } else {
            String rawText = text(raw);
            if (rawText != null) {
                for (String dependency : rawText.split(",")) {
                    String normalized = dependency.trim();
                    if (!normalized.isEmpty()) {
                        dependencies.add(normalized);
                    }
                }
            }
        }
        return dependencies;
    }

    private ObjectNode buildPolicy(JsonNode cells) {
        ObjectNode policy = cells.path("policy").isObject()
                ? ((ObjectNode) cells.path("policy")).deepCopy()
                : objectMapper.createObjectNode();
        if (!policy.has("blocking")) {
            String blocking = upper(firstText(cells, "blocking"));
            policy.put("blocking", blocking == null ? "NONE" : blocking);
        }
        if (!policy.has("onError")) {
            String onError = upper(firstText(cells, "onError"));
            policy.put("onError", onError == null ? "FAIL_RUN" : onError);
        }
        return policy;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? null : text(node.path(field));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String resolved = value.asText().trim();
            return resolved.isEmpty() ? null : resolved;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        if (value.isObject()) {
            return firstText(value, "value", "name", "alias");
        }
        return null;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sanitizeAlias(String value) {
        String normalized = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .replaceAll("_+", "_");
        if (normalized.isBlank()) {
            normalized = "input";
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "t_" + normalized;
        }
        return normalized;
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return parseUuid(value);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
    private void validateGraph(CompilationContext context) {
        Set<String> outputAliases = new HashSet<>();
        for (ObjectNode node : context.nodes) {
            String nodeId = text(node, "id");
            RowContext rowContext = context.contextByNodeId.get(nodeId);
            JsonNode output = node.path("output");
            String outputAlias = output.isObject() ? text(output, "alias") : null;
            if (outputAlias != null) {
                if (!outputAlias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    error(context, rowContext, "OUTPUT_ALIAS_INVALID",
                            "输出别名「" + outputAlias + "」只能包含字母、数字和下划线，且不能以数字开头",
                            null);
                }
                if (!outputAliases.add(outputAlias)) {
                    error(context, rowContext, "OUTPUT_ALIAS_DUPLICATE",
                            "输出别名「" + outputAlias + "」重复", "为每个节点设置唯一输出别名");
                }
                context.aliasToNodeId.put(outputAlias, nodeId);
            }
        }

        for (ObjectNode node : context.nodes) {
            validateNode(node, context);
        }

        for (ObjectNode node : context.nodes) {
            String nodeId = text(node, "id");
            RowContext rowContext = context.contextByNodeId.get(nodeId);
            Set<String> dependencies = new LinkedHashSet<>();
            JsonNode explicitDependencies = node.path("dependsOn");
            if (explicitDependencies.isArray()) {
                for (JsonNode dependency : explicitDependencies) {
                    String dependencyId = text(dependency);
                    if (dependencyId == null) {
                        continue;
                    }
                    ObjectNode upstream = context.nodesById.get(dependencyId);
                    if (upstream == null) {
                        error(context, rowContext, "DEPENDENCY_NOT_FOUND",
                                "依赖节点「" + dependencyId + "」不存在", null);
                        continue;
                    }
                    if ("OUTPUT".equals(text(upstream, "kind"))) {
                        error(context, rowContext, "OUTPUT_HAS_DEPENDENTS",
                                "OUTPUT 节点是终态，不能被其他节点依赖", null);
                    }
                    dependencies.add(dependencyId);
                }
            }
            JsonNode inputs = node.path("inputs");
            if (inputs.isArray()) {
                for (JsonNode input : inputs) {
                    if (!"NODE_OUTPUT".equals(upper(text(input, "type")))) {
                        continue;
                    }
                    String upstreamId = text(input, "nodeId");
                    String expectedAlias = text(input, "outputAlias");
                    ObjectNode upstream = upstreamId == null ? null : context.nodesById.get(upstreamId);
                    if (upstream == null) {
                        error(context, rowContext, "UPSTREAM_NODE_NOT_FOUND",
                                "上游节点「" + upstreamId + "」不存在", null);
                        continue;
                    }
                    if ("OUTPUT".equals(text(upstream, "kind"))) {
                        error(context, rowContext, "OUTPUT_HAS_DEPENDENTS",
                                "OUTPUT 节点是终态，不能被其他节点依赖", null);
                    }
                    String declaredAlias = upstream.path("output").isObject()
                            ? text(upstream.path("output"), "alias")
                            : null;
                    if (declaredAlias == null || !declaredAlias.equals(expectedAlias)) {
                        error(context, rowContext, "UPSTREAM_OUTPUT_NOT_FOUND",
                                "上游节点「" + upstreamId + "」没有输出「" + expectedAlias + "」",
                                "重新选择上游节点的输出表");
                    }
                    dependencies.add(upstreamId);
                }
            }
            context.dependencies.put(nodeId, dependencies);
        }

        addGateMetricDependencies(context);

        Map<String, Integer> marks = new HashMap<>();
        for (String nodeId : context.nodesById.keySet()) {
            detectCycle(nodeId, context, marks);
        }
    }

    private void addGateMetricDependencies(CompilationContext context) {
        for (ObjectNode node : context.nodes) {
            if (!"GATE".equals(upper(text(node, "kind")))) {
                continue;
            }
            String metricRef = text(node.path("config"), "metricRef");
            if (metricRef == null) {
                continue;
            }
            String prefix = metricRef.contains(".")
                    ? metricRef.substring(0, metricRef.indexOf('.'))
                    : metricRef;
            String upstreamId = context.aliasToNodeId.getOrDefault(prefix, prefix);
            if (!context.nodesById.containsKey(upstreamId)) {
                continue;
            }
            String nodeId = text(node, "id");
            context.dependencies
                    .computeIfAbsent(nodeId, ignored -> new LinkedHashSet<>())
                    .add(upstreamId);
        }
    }

    private void detectCycle(String nodeId, CompilationContext context, Map<String, Integer> marks) {
        Integer mark = marks.get(nodeId);
        if (mark != null && mark == 2) {
            return;
        }
        if (mark != null && mark == 1) {
            if (!context.cycleReported) {
                error(context, context.contextByNodeId.get(nodeId), "DEPENDENCY_CYCLE",
                        "规则节点之间存在循环依赖，无法生成执行计划", "移除环上的显式依赖或上游引用");
                context.cycleReported = true;
            }
            return;
        }
        marks.put(nodeId, 1);
        for (String dependency : context.dependencies.getOrDefault(nodeId, Set.of())) {
            if (context.nodesById.containsKey(dependency)) {
                detectCycle(dependency, context, marks);
            }
        }
        marks.put(nodeId, 2);
    }

    private void validateNode(ObjectNode node, CompilationContext context) {
        String nodeId = text(node, "id");
        String kind = upper(text(node, "kind"));
        RowContext rowContext = context.contextByNodeId.get(nodeId);
        JsonNode config = node.path("config");
        if (!config.isObject()) {
            error(context, rowContext, "CONFIG_REQUIRED", "节点「" + nodeId + "」缺少 config 对象", null);
            return;
        }
        Map<String, UUID> inputTables = inputTables(node);
        if (inputTables.size() != inputAliases(node).size()) {
            error(context, rowContext, "INPUT_ALIAS_INVALID", "节点输入别名必须非空且唯一", null);
        }
        int inputCount = inputAliases(node).size();
        if (Set.of("CHECK", "TRANSFORM", "PROJECT", "FILTER", "DEDUPE", "AGGREGATE", "DERIVE", "OUTPUT")
                .contains(kind) && inputCount != 1) {
            error(context, rowContext, "INPUT_COUNT_INVALID",
                    "节点「" + nodeId + "」必须且只能有一个输入", null);
        }
        if ("JOIN".equals(kind) && inputCount != 2) {
            error(context, rowContext, "JOIN_INPUT_COUNT_INVALID", "JOIN 必须且只能有两个输入", null);
        }
        String outputAlias = text(config, "outputAlias");
        switch (kind) {
            case "CHECK" -> validateCheck(nodeId, config, inputTables, context, rowContext);
            case "TRANSFORM" -> validateTransform(nodeId, config, inputTables, context, rowContext);
            case "PROJECT" -> {
                boolean selectEmpty = !config.path("select").isArray() || config.path("select").isEmpty();
                boolean dropEmpty = !config.path("drop").isArray() || config.path("drop").isEmpty();
                if (selectEmpty && dropEmpty) {
                    error(context, rowContext, "PROJECT_FIELDS_REQUIRED",
                            "PROJECT 需要至少选择或移除一个字段", null);
                }
                String projectAlias = primaryAlias(inputTables);
                validateProjectFieldList(config.path("select"), projectAlias, inputTables,
                        context, rowContext, "PROJECT_SELECT_INVALID");
                validateProjectFieldList(config.path("drop"), projectAlias, inputTables,
                        context, rowContext, "PROJECT_DROP_INVALID");
                requireOutputAlias(context, rowContext, outputAlias, "PROJECT");
            }
            case "FILTER" -> {
                if (!config.path("predicate").isObject()) {
                    error(context, rowContext, "PREDICATE_REQUIRED", "FILTER 缺少结构化条件", null);
                } else {
                    validatePredicate(config.path("predicate"), null, inputTables, context, rowContext, 0);
                }
                requireOutputAlias(context, rowContext, outputAlias, "FILTER");
            }
            case "DEDUPE" -> {
                JsonNode keys = config.path("keys");
                if (!keys.isArray() || keys.isEmpty()) {
                    error(context, rowContext, "DEDUPE_KEYS_REQUIRED", "DEDUPE 至少需要一个去重键", null);
                } else {
                    for (JsonNode key : keys) {
                        validateFieldRef(key, null, inputTables, context, rowContext, "DEDUPE_KEY_INVALID");
                    }
                }
                requireOutputAlias(context, rowContext, outputAlias, "DEDUPE");
            }
            case "JOIN" -> validateJoin(nodeId, config, inputTables, context, rowContext);
            case "AGGREGATE" -> validateAggregate(nodeId, config, inputTables, context, rowContext);
            case "DERIVE" -> validateDerive(nodeId, config, inputTables, context, rowContext);
            case "GATE" -> validateGate(nodeId, config, context, rowContext);
            case "OUTPUT" -> validateOutput(nodeId, config, context, rowContext);
            default -> error(context, rowContext, "NODE_KIND_UNSUPPORTED",
                    "不支持的节点类型「" + kind + "」", null);
        }
    }

    private void validateCheck(
            String nodeId,
            JsonNode config,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext) {
        String scope = upper(text(config, "scope"));
        if (scope == null) {
            scope = "ROW";
        }
        if (!SCOPES.contains(scope)) {
            error(context, rowContext, "CHECK_SCOPE_INVALID", "未知的检查作用域「" + scope + "」", null);
        }
        String effect = upper(text(config, "effect"));
        if (effect == null || !EFFECTS.contains(effect)) {
            error(context, rowContext, "CHECK_EFFECT_INVALID", "CHECK 缺少合法效果", null);
        }
        JsonNode predicate = config.path("predicate");
        if (!predicate.isObject()) {
            error(context, rowContext, "PREDICATE_REQUIRED", "CHECK 缺少结构化条件", null);
        } else {
            validatePredicate(predicate, primaryAlias(inputTables), inputTables, context, rowContext, 0);
        }
        if (effect != null && ROW_EFFECTS.contains(effect)) {
            if (!"ROW".equals(scope)) {
                error(context, rowContext, "EFFECT_SCOPE_INCOMPATIBLE",
                        "行级效果只能用于 ROW 作用域", null);
            }
            requireOutputAlias(context, rowContext, text(config, "outputAlias"), "CHECK");
            if ("FLAG_ROWS".equals(effect)
                    && text(config.path("effectParams"), "flagField") == null) {
                error(context, rowContext, "FLAG_FIELD_REQUIRED", "FLAG_ROWS 必须指定标记字段", null);
            }
        }
    }

    private void validateTransform(
            String nodeId,
            JsonNode config,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext) {
        String operation = upper(text(config, "operation"));
        if (operation == null || !TRANSFORM_OPERATIONS.contains(operation)) {
            error(context, rowContext, "TRANSFORM_OPERATION_INVALID", "TRANSFORM 缺少合法操作", null);
        }
        JsonNode target = config.path("target");
        if (!target.isObject() && !target.isTextual()) {
            error(context, rowContext, "TRANSFORM_TARGET_REQUIRED", "TRANSFORM 缺少目标字段", null);
        } else {
            validateFieldRef(target, primaryAlias(inputTables), inputTables, context, rowContext,
                    "TRANSFORM_TARGET_INVALID");
        }
    }

    private void validateProjectFieldList(
            JsonNode fields,
            String alias,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext,
            String code) {
        if (fields == null || fields.isMissingNode() || fields.isNull()) {
            return;
        }
        if (!fields.isArray()) {
            error(context, rowContext, code, "PROJECT 字段列表必须是数组", null);
            return;
        }
        for (JsonNode field : fields) {
            validateFieldRef(field, alias, inputTables, context, rowContext, code);
        }
    }
    private void validateJoin(
            String nodeId,
            JsonNode config,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext) {
        String leftAlias = text(config, "leftAlias");
        String rightAlias = text(config, "rightAlias");
        if (leftAlias == null || !inputTables.containsKey(leftAlias)) {
            error(context, rowContext, "JOIN_LEFT_ALIAS_INVALID", "JOIN 左输入别名不存在", null);
        }
        if (rightAlias == null || !inputTables.containsKey(rightAlias)) {
            error(context, rowContext, "JOIN_RIGHT_ALIAS_INVALID", "JOIN 右输入别名不存在", null);
        }
        JsonNode leftKeys = config.path("leftKeys");
        JsonNode rightKeys = config.path("rightKeys");
        if (!leftKeys.isArray() || leftKeys.isEmpty() || !rightKeys.isArray() || rightKeys.isEmpty()) {
            error(context, rowContext, "JOIN_KEYS_REQUIRED", "JOIN 必须配置左右关联键", null);
        } else if (leftKeys.size() != rightKeys.size()) {
            error(context, rowContext, "JOIN_KEYS_LENGTH_MISMATCH", "JOIN 左右关联键数量不一致", null);
        } else {
            for (int index = 0; index < leftKeys.size(); index++) {
                validateJoinKey(leftKeys.get(index), leftAlias, inputTables, context, rowContext, "LEFT");
                validateJoinKey(rightKeys.get(index), rightAlias, inputTables, context, rowContext, "RIGHT");
            }
        }
        String joinType = upper(text(config, "joinType"));
        if (joinType == null) {
            joinType = "INNER";
        }
        if (!JOIN_TYPES.contains(joinType)) {
            error(context, rowContext, "JOIN_TYPE_INVALID", "未知的关联类型「" + joinType + "」", null);
        }
        String cardinality = text(config, "cardinality");
        if (cardinality == null) {
            error(context, rowContext, "JOIN_CARDINALITY_REQUIRED",
                    "JOIN 必须声明基数，避免意外的行数膨胀", null);
        } else if (!CARDINALITIES.contains(cardinality)) {
            error(context, rowContext, "JOIN_CARDINALITY_INVALID", "未知的关联基数「" + cardinality + "」", null);
        }
        requireOutputAlias(context, rowContext, text(config, "outputAlias"), "JOIN");
        JsonNode guards = config.path("guards");
        if (!guards.isMissingNode() && !guards.isObject()) {
            error(context, rowContext, "JOIN_GUARDS_INVALID", "JOIN guards 必须是对象", null);
        }
    }

    private void validateJoinKey(
            JsonNode key,
            String alias,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext,
            String side) {
        validateFieldRef(key, alias, inputTables, context, rowContext, "JOIN_" + side + "_KEY_INVALID");
    }

    private void validateAggregate(
            String nodeId,
            JsonNode config,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext) {
        String alias = primaryAlias(inputTables);
        JsonNode groupBy = config.path("groupBy");
        if (!groupBy.isMissingNode()) {
            if (!groupBy.isArray()) {
                error(context, rowContext, "AGGREGATE_GROUP_BY_INVALID", "AGGREGATE groupBy 必须是数组", null);
            } else {
                for (JsonNode group : groupBy) {
                    validateFieldRef(group, alias, inputTables, context, rowContext, "AGGREGATE_GROUP_BY_INVALID");
                }
            }
        }
        JsonNode metrics = config.path("metrics");
        if (!metrics.isArray() || metrics.isEmpty()) {
            error(context, rowContext, "AGGREGATE_METRICS_REQUIRED", "AGGREGATE 至少需要一个指标", null);
        } else {
            Set<String> metricNames = new HashSet<>();
            for (JsonNode metric : metrics) {
                String name = text(metric, "name");
                String operation = upper(text(metric, "op"));
                if (name == null) {
                    error(context, rowContext, "AGGREGATE_METRIC_NAME_REQUIRED", "聚合指标缺少名称", null);
                } else if (!metricNames.add(name)) {
                    error(context, rowContext, "AGGREGATE_METRIC_NAME_DUPLICATE",
                            "聚合指标「" + name + "」重复", null);
                }
                if (operation == null || !AGGREGATE_OPS.contains(operation)) {
                    error(context, rowContext, "AGGREGATE_OPERATION_INVALID",
                            "聚合指标「" + name + "」使用了未知操作", null);
                }
                JsonNode field = metric.path("field");
                if ("COUNT".equals(operation) && field.isTextual() && "*".equals(field.asText())) {
                    continue;
                }
                if (field.isMissingNode() || field.isNull()) {
                    error(context, rowContext, "AGGREGATE_METRIC_FIELD_REQUIRED",
                            "聚合指标「" + name + "」缺少字段", null);
                } else {
                    validateFieldRef(field, alias, inputTables, context, rowContext, "AGGREGATE_METRIC_FIELD_INVALID");
                }
            }
        }
        requireOutputAlias(context, rowContext, text(config, "outputAlias"), "AGGREGATE");
    }

    private void validateDerive(
            String nodeId,
            JsonNode config,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext) {
        if (text(config, "outputField") == null) {
            error(context, rowContext, "DERIVE_OUTPUT_FIELD_REQUIRED", "DERIVE 缺少新字段名", null);
        }
        String dataType = upper(text(config, "dataType"));
        if (dataType == null || !Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME")
                .contains(dataType)) {
            error(context, rowContext, "DERIVE_DATA_TYPE_INVALID", "DERIVE 缺少合法字段类型", null);
        }
        JsonNode expression = config.path("expression");
        if (!expression.isObject() && !expression.isTextual()) {
            error(context, rowContext, "DERIVE_EXPRESSION_REQUIRED", "DERIVE 缺少表达式", null);
        } else if (expression.isObject() && text(expression, "op") == null) {
            error(context, rowContext, "DERIVE_EXPRESSION_OP_REQUIRED", "DERIVE 表达式缺少 op", null);
        }
    }

    private void validateGate(
            String nodeId,
            JsonNode config,
            CompilationContext context,
            RowContext rowContext) {
        String metricRef = text(config, "metricRef");
        if (metricRef == null) {
            error(context, rowContext, "GATE_METRIC_REQUIRED", "GATE 缺少指标引用", null);
        } else {
            String prefix = metricRef.contains(".")
                    ? metricRef.substring(0, metricRef.indexOf('.'))
                    : metricRef;
            if (!context.nodesById.containsKey(prefix) && !context.aliasToNodeId.containsKey(prefix)) {
                error(context, rowContext, "GATE_METRIC_NOT_FOUND",
                        "GATE 指标「" + metricRef + "」引用的节点不存在", null);
            }
        }
        String comparator = upper(text(config, "comparator"));
        if (comparator == null || !COMPARATORS.contains(comparator)) {
            error(context, rowContext, "GATE_COMPARATOR_INVALID", "GATE 缺少合法比较符", null);
        }
        if (!config.has("threshold")) {
            error(context, rowContext, "GATE_THRESHOLD_REQUIRED", "GATE 缺少阈值", null);
        }
        String effect = upper(text(config, "effect"));
        if (effect == null || !Set.of("WARN", "FAIL_RUN", "BLOCK_PUBLISH").contains(effect)) {
            error(context, rowContext, "GATE_EFFECT_INVALID", "GATE 效果必须是 WARN、FAIL_RUN 或 BLOCK_PUBLISH", null);
        }
    }

    private void validateOutput(
            String nodeId,
            JsonNode config,
            CompilationContext context,
            RowContext rowContext) {
        String mode = upper(text(config, "mode"));
        if (mode == null || !Set.of("MATERIALIZE_VERSION", "EXPORT", "QUARANTINE").contains(mode)) {
            error(context, rowContext, "OUTPUT_MODE_INVALID", "OUTPUT 缺少合法输出模式", null);
        }
        String format = upper(text(config, "format"));
        if (format == null || !Set.of("PARQUET", "CSV", "JSONL", "XLSX").contains(format)) {
            error(context, rowContext, "OUTPUT_FORMAT_INVALID", "OUTPUT 缺少合法输出格式", null);
        }
        if (text(config, "name") == null) {
            error(context, rowContext, "OUTPUT_NAME_REQUIRED", "OUTPUT 缺少输出名称", null);
        }
    }

    private void requireOutputAlias(
            CompilationContext context, RowContext rowContext, String outputAlias, String kind) {
        if (outputAlias == null) {
            error(context, rowContext, "OUTPUT_ALIAS_REQUIRED",
                    kind + " 产生新表，必须声明 outputAlias", "填写唯一输出别名");
        }
    }

    private static String primaryAlias(Map<String, UUID> inputTables) {
        return inputTables.isEmpty() ? null : inputTables.keySet().iterator().next();
    }

    private static List<String> inputAliases(JsonNode node) {
        List<String> aliases = new ArrayList<>();
        JsonNode inputs = node.path("inputs");
        if (!inputs.isArray()) {
            return aliases;
        }
        for (JsonNode input : inputs) {
            String alias = text(input, "alias");
            if (alias != null) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private static Map<String, UUID> inputTables(JsonNode node) {
        Map<String, UUID> tables = new LinkedHashMap<>();
        JsonNode inputs = node.path("inputs");
        if (!inputs.isArray()) {
            return tables;
        }
        for (JsonNode input : inputs) {
            String alias = text(input, "alias");
            if (alias == null) {
                continue;
            }
            UUID tableId = uuid(input, "tableId");
            tables.put(alias, tableId);
        }
        return tables;
    }
    private void validateFieldRef(
            JsonNode ref,
            String alias,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext,
            String code) {
        if (ref == null || ref.isMissingNode() || ref.isNull()) {
            return;
        }
        if (!ref.isObject() && !ref.isTextual()) {
            return;
        }
        String type = upper(text(ref, "type"));
        if ("LITERAL".equals(type) || "LIST".equals(type) || "RANGE".equals(type)) {
            return;
        }
        String fieldIdValue = ref.isObject() ? text(ref, "fieldId") : null;
        String columnIdValue = ref.isObject() ? text(ref, "columnId") : null;
        String fieldName = ref.isTextual()
                ? ref.asText()
                : firstText(ref, "nameSnapshot", "name", "field", "columnName");
        if (fieldIdValue == null && columnIdValue == null && fieldName == null) {
            return;
        }
        DatasetColumn referencedColumn = fieldIdValue == null && columnIdValue == null
                ? null
                : resolveReferencedColumn(fieldIdValue, columnIdValue, context, rowContext, code);

        JsonNode tableRef = ref.isObject() ? ref.path("tableRef") : null;
        String resolvedAlias = alias;
        if (tableRef != null && tableRef.isObject()) {
            String nestedAlias = text(tableRef, "alias");
            if (nestedAlias != null) {
                resolvedAlias = nestedAlias;
            }
        }
        UUID expectedTableId = null;
        if (resolvedAlias != null) {
            if (!inputTables.containsKey(resolvedAlias)) {
                error(context, rowContext, code,
                        "字段引用了未知的输入别名「" + resolvedAlias + "」", "重新选择字段所属表");
                return;
            }
            expectedTableId = inputTables.get(resolvedAlias);
        }

        JsonNode explicitTableId = ref.isObject() ? ref.path("tableId") : null;
        if (explicitTableId == null || explicitTableId.isMissingNode() || explicitTableId.isNull()) {
            if (tableRef != null && tableRef.isObject()) {
                explicitTableId = tableRef.path("tableId");
            }
        }
        UUID tableId = null;
        if (explicitTableId != null && !explicitTableId.isMissingNode() && !explicitTableId.isNull()) {
            tableId = parseUuid(text(explicitTableId));
            if (tableId == null) {
                error(context, rowContext, code, "字段引用的 tableId 格式无效", null);
                return;
            }
        }
        if (tableId == null) {
            tableId = expectedTableId;
        }
        if (tableId == null && referencedColumn != null) {
            tableId = referencedColumn.getTableId();
        }
        if (tableId == null && resolvedAlias == null && inputTables.size() == 1) {
            tableId = inputTables.values().iterator().next();
        }
        if (tableId == null) {
            if (resolvedAlias != null && inputTables.containsKey(resolvedAlias)
                    && inputTables.get(resolvedAlias) == null) {
                return;
            }
            error(context, rowContext, code,
                    "字段「" + (fieldName == null ? fieldIdValue : fieldName) + "」无法解析所属表", null);
            return;
        }
        DatasetTable table = context.tablesById.get(tableId);
        if (table == null) {
            error(context, rowContext, code, "字段引用的数据表不存在或不属于当前数据集", null);
            return;
        }

        if (referencedColumn != null) {
            if (!tableId.equals(referencedColumn.getTableId())) {
                error(context, rowContext, code,
                        "字段「" + referencedColumn.getColumnName() + "」不属于所选的表", "重新选择字段");
            } else if (ref.isObject()) {
                ObjectNode objectRef = (ObjectNode) ref;
                objectRef.put("columnId", referencedColumn.getId().toString());
                if (text(objectRef, "nameSnapshot") == null) {
                    objectRef.put("nameSnapshot", referencedColumn.getColumnName());
                }
            }
            return;
        }
        if (fieldIdValue != null || columnIdValue != null) {
            return;
        }

        List<DatasetColumn> columns = context.columnsByTable.getOrDefault(tableId, List.of());
        boolean found = columns.stream().anyMatch(column -> column.getColumnName().equalsIgnoreCase(fieldName));
        if (!found) {
            error(context, rowContext, code,
                    "表「" + table.getTableName() + "」中不存在字段「" + fieldName + "」",
                    "从当前表的字段列表中重新选择");
        }
    }

    private DatasetColumn resolveReferencedColumn(
            String fieldIdValue,
            String columnIdValue,
            CompilationContext context,
            RowContext rowContext,
            String code) {
        if (columnIdValue != null) {
            UUID columnId = parseUuid(columnIdValue);
            if (columnId == null) {
                error(context, rowContext, code, "columnId 格式无效", null);
                return null;
            }
            DatasetColumn column = context.columnsById.get(columnId);
            if (column == null) {
                error(context, rowContext, code, "字段所在列已不存在，请重新选择", null);
            }
            return column;
        }
        UUID fieldId = parseUuid(fieldIdValue);
        if (fieldId == null) {
            error(context, rowContext, code, "fieldId 格式无效", null);
            return null;
        }
        DatasetColumn physicalColumn = context.columnsById.get(fieldId);
        if (physicalColumn != null) {
            return physicalColumn;
        }
        DatasetFieldBinding binding = context.datasetVersionId == null
                ? null
                : datasetFieldBindingMapper.findByFieldIdAndDatasetVersionId(fieldId, context.datasetVersionId);
        if (binding == null) {
            error(context, rowContext, code,
                    "字段已不存在或尚未从字段库导入，请重新选择", "先导入字段库，再重新选择字段");
            return null;
        }
        DatasetColumn boundColumn = context.columnsById.get(binding.getColumnId());
        if (boundColumn == null) {
            error(context, rowContext, code, "字段绑定的数据列已不存在，请重新导入字段库", null);
        }
        return boundColumn;
    }

    private void validatePredicate(
            JsonNode predicate,
            String alias,
            Map<String, UUID> inputTables,
            CompilationContext context,
            RowContext rowContext,
            int depth) {
        if (depth > MAX_PREDICATE_DEPTH) {
            error(context, rowContext, "PREDICATE_TOO_DEEP", "条件嵌套层级过深", null);
            return;
        }
        if (predicate == null || !predicate.isObject()) {
            error(context, rowContext, "PREDICATE_INVALID", "条件必须是结构化对象，不能是 SQL 字符串", null);
            return;
        }
        String op = upper(text(predicate, "op"));
        if (op == null || !PREDICATE_OPS.contains(op)) {
            error(context, rowContext, "PREDICATE_OP_INVALID", "未知的条件操作符「" + op + "」", null);
            return;
        }
        if ("AND".equals(op) || "OR".equals(op)) {
            JsonNode children = predicate.path("predicates");
            if (!children.isArray() || children.isEmpty()) {
                error(context, rowContext, "PREDICATE_CHILDREN_REQUIRED", op + " 条件缺少 predicates", null);
                return;
            }
            for (JsonNode child : children) {
                validatePredicate(child, alias, inputTables, context, rowContext, depth + 1);
            }
            return;
        }
        if ("NOT".equals(op)) {
            validatePredicate(predicate.path("predicate"), alias, inputTables, context, rowContext, depth + 1);
            return;
        }

        JsonNode args = predicate.path("args");
        if (args.isArray()) {
            for (JsonNode arg : args) {
                if (looksLikeFieldRef(arg)) {
                    validateFieldRef(arg, alias, inputTables, context, rowContext, "PREDICATE_FIELD_INVALID");
                }
            }
        }
        JsonNode fields = predicate.path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                if (looksLikeFieldRef(field)) {
                    validateFieldRef(field, alias, inputTables, context, rowContext, "PREDICATE_FIELD_INVALID");
                }
            }
        }
        for (String fieldName : List.of("field", "left", "right", "earlier", "later", "from", "to")) {
            JsonNode value = predicate.path(fieldName);
            if (looksLikeFieldRef(value)) {
                validateFieldRef(value, alias, inputTables, context, rowContext, "PREDICATE_FIELD_INVALID");
            }
        }
        if (Set.of("IS_NULL", "IS_EMPTY", "IS_BLANK").contains(op)
                && (!args.isArray() || args.isEmpty())) {
            error(context, rowContext, "PREDICATE_FIELD_REQUIRED", op + " 条件缺少字段参数", null);
        }
        if (Set.of("DUPLICATE", "UNIQUE", "DUPLICATE_RATE_GT").contains(op)
                && (!fields.isArray() || fields.isEmpty())) {
            error(context, rowContext, "PREDICATE_FIELDS_REQUIRED", op + " 条件缺少字段列表", null);
        }
        if ("BETWEEN".equals(op) && !predicate.has("field")) {
            error(context, rowContext, "PREDICATE_FIELD_REQUIRED", "BETWEEN 条件缺少 field", null);
        }
        if ("DATE_ORDER_INVALID".equals(op)
                && (!predicate.has("earlier") || !predicate.has("later"))) {
            error(context, rowContext, "PREDICATE_DATE_FIELDS_REQUIRED",
                    "DATE_ORDER_INVALID 需要 earlier 和 later 两个字段", null);
        }
        if (Set.of("MISSING_RATE_GT", "DUPLICATE_RATE_GT", "JOIN_MATCH_RATE_LT",
                "JOIN_FANOUT_GT", "ROW_COUNT_LT", "CATEGORY_RATIO_LT",
                "DISTINCT_COUNT_LT").contains(op) && !predicate.has("threshold")) {
            error(context, rowContext, "PREDICATE_THRESHOLD_REQUIRED", op + " 条件缺少 threshold", null);
        }
    }

    private static boolean looksLikeFieldRef(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return true;
        }
        if (!value.isObject()) {
            return false;
        }
        String type = upper(text(value, "type"));
        if ("LITERAL".equals(type) || "LIST".equals(type) || "RANGE".equals(type)) {
            return false;
        }
        return "FIELD".equals(type)
                || value.has("fieldId")
                || value.has("nameSnapshot")
                || value.has("tableRef");
    }

    private ObjectNode buildPlan(JsonNode document, CompilationContext context) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schemaVersion", 1);
        plan.put("compiler", "rule-workbook-compiler/v1");
        String workbookId = document == null ? null : text(document, "workbookId");
        if (workbookId == null) {
            plan.putNull("workbookId");
        } else {
            plan.put("workbookId", workbookId);
        }
        plan.put("datasetVersionId", context.datasetVersionId.toString());
        ArrayNode nodes = plan.putArray("nodes");
        for (ObjectNode node : context.nodes) {
            nodes.add(node.deepCopy());
        }
        ArrayNode edges = plan.putArray("edges");
        for (Map.Entry<String, Set<String>> entry : context.dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                ObjectNode edge = edges.addObject();
                edge.put("from", dependency);
                edge.put("to", entry.getKey());
            }
        }
        plan.put("nodeCount", context.nodes.size());
        return plan;
    }

    private void error(
            CompilationContext context,
            RowContext rowContext,
            String code,
            String message,
            String fixSuggestion) {
        context.issues.add(new RuleValidationIssueVO(
                code,
                "ERROR",
                rowContext == null ? null : rowContext.sheetKey(),
                rowContext == null ? null : rowContext.rowId(),
                rowContext == null ? null : rowContext.columnKey(),
                message,
                fixSuggestion));
    }

    private record RowContext(String sheetKey, String rowId, String columnKey) {
    }

    private static final class CompilationContext {

        private final UUID datasetVersionId;
        private final Map<UUID, DatasetTable> tablesById = new LinkedHashMap<>();
        private final Map<UUID, DatasetColumn> columnsById = new LinkedHashMap<>();
        private final Map<UUID, List<DatasetColumn>> columnsByTable = new LinkedHashMap<>();
        private final List<RuleValidationIssueVO> issues = new ArrayList<>();
        private final List<ObjectNode> nodes = new ArrayList<>();
        private final Map<String, ObjectNode> nodesById = new LinkedHashMap<>();
        private final Map<String, RowContext> contextByNodeId = new LinkedHashMap<>();
        private final Map<String, String> aliasToNodeId = new LinkedHashMap<>();
        private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        private boolean cycleReported;

        private CompilationContext(UUID datasetVersionId) {
            this.datasetVersionId = datasetVersionId;
        }
    }
}
