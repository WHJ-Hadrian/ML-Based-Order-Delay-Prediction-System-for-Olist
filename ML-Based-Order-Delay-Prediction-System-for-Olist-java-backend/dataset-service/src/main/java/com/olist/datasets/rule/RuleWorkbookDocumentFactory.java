package com.olist.datasets.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RuleWorkbookDocumentFactory {

    private final ObjectMapper objectMapper;

    public RuleWorkbookDocumentFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode normalize(
            JsonNode input,
            UUID workbookId,
            String name,
            UUID datasetVersionId) {
        ObjectNode document = input != null && input.isObject()
                ? ((ObjectNode) input).deepCopy()
                : defaultDocument();
        document.put("schemaVersion", 1);
        document.put("workbookId", workbookId.toString());
        document.put("name", name);
        document.put("datasetVersionId", datasetVersionId.toString());
        if (!document.path("sheets").isArray()) {
            document.putArray("sheets");
        }
        return document;
    }

    private ObjectNode defaultDocument() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        ArrayNode sheets = root.putArray("sheets");

        addSheet(sheets, "field_checks", "字段检查", "FIELD_RULES", 10,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("table", "输入", "INPUT_REF", true, true),
                column("field", "字段", "FIELD_REF", true, true, "table"),
                column("scope", "检查范围", "ENUM", false, true),
                column("condition", "条件", "PREDICATE", true, true),
                column("effect", "效果", "EFFECT", true, true),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", false, true),
                column("effectParams", "效果参数", "PARAM_OBJECT", false, true),
                column("blocking", "阻断", "ENUM", false, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "transforms", "清洗转换", "TRANSFORMS", 20,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("table", "输入", "INPUT_REF", true, true),
                column("field", "字段", "FIELD_REF", true, true, "table"),
                column("operation", "操作", "OPERATION", true, true),
                column("params", "参数", "PARAM_OBJECT", false, true),
                column("condition", "条件", "PREDICATE", false, true),
                column("outputField", "输出字段", "TEXT", false, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "filters", "行筛选", "FILTERS", 22,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("condition", "保留条件", "PREDICATE", true, true),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "projects", "字段投影", "PROJECTS", 24,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("select", "保留字段", "FIELD_LIST_REF", false, true, "input"),
                column("drop", "移除字段", "FIELD_LIST_REF", false, true, "input"),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "dedupes", "去重", "DEDUPES", 26,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("fields", "去重键", "FIELD_LIST_REF", true, true, "input"),
                column("keep", "保留策略", "ENUM", false, true),
                column("orderBy", "排序依据", "PARAM_OBJECT", false, true),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "relations", "多表关联", "RELATIONS", 30,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("leftInput", "左输入", "INPUT_REF", true, true),
                column("leftField", "左字段", "FIELD_REF", true, true, "leftInput"),
                column("rightInput", "右输入", "INPUT_REF", true, true),
                column("rightField", "右字段", "FIELD_REF", true, true, "rightInput"),
                column("joinType", "关联类型", "JOIN_TYPE", true, true),
                column("cardinality", "基数", "CARDINALITY", true, true),
                column("guards", "关联保护", "PARAM_OBJECT", false, true),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "aggregates", "聚合", "AGGREGATES", 40,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("groupBy", "分组字段", "FIELD_LIST_REF", false, true, "input"),
                column("metrics", "指标", "PARAM_OBJECT", true, true),
                column("outputAlias", "输出别名", "OUTPUT_ALIAS", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "derived", "特征派生", "DERIVED", 50,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("outputField", "新字段", "TEXT", true, true),
                column("dataType", "类型", "ENUM", true, true),
                column("expression", "表达式", "PARAM_OBJECT", true, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "gates", "质量门禁", "GATES", 60,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("metricRef", "指标", "TEXT", true, true),
                column("comparator", "比较符", "ENUM", true, true),
                column("threshold", "阈值", "NUMBER", true, true),
                column("effect", "效果", "EFFECT", true, true),
                column("dependsOn", "显式依赖", "TAGS", false, true),
                column("custom_note", "备注", "TEXT", false, false));

        addSheet(sheets, "outputs", "输出", "OUTPUTS", 70,
                column("enabled", "启用", "BOOLEAN", false, true),
                column("group", "分组", "GROUP", false, true),
                column("input", "输入", "INPUT_REF", true, true),
                column("mode", "输出方式", "ENUM", true, true),
                column("format", "格式", "ENUM", true, true),
                column("name", "名称", "TEXT", true, true),
                column("partitionBy", "分区字段", "FIELD_LIST_REF", false, true, "input"),
                column("custom_note", "备注", "TEXT", false, false));
        return root;
    }

    private void addSheet(
            ArrayNode sheets,
            String key,
            String title,
            String kind,
            int order,
            ObjectNode... columns) {
        ObjectNode sheet = sheets.addObject();
        sheet.put("key", key);
        sheet.put("title", title);
        sheet.put("kind", kind);
        sheet.put("order", order);
        ArrayNode columnArray = sheet.putArray("columns");
        for (ObjectNode column : columns) {
            columnArray.add(column);
        }
        sheet.putArray("rows");
    }

    private ObjectNode column(
            String key,
            String title,
            String cellType,
            boolean required,
            boolean system,
            String... dependsOn) {
        ObjectNode column = objectMapper.createObjectNode();
        column.put("key", key);
        column.put("title", title);
        column.put("cellType", cellType);
        column.put("required", required);
        column.put("system", system);
        column.put("hidden", false);
        column.put("width", 180);
        if (dependsOn.length > 0) {
            ArrayNode dependencies = column.putArray("dependsOn");
            for (String dependency : dependsOn) {
                dependencies.add(dependency);
            }
        }
        return column;
    }
}
