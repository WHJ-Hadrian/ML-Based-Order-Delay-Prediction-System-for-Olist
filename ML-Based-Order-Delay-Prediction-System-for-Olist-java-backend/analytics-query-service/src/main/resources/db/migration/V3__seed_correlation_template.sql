-- 相关性分析模板：选两个数值字段看散点与结论

INSERT INTO analysis_templates (template_key, name, description, category, spec_json, created_at)
VALUES
    ('correlation_probe', '相关性分析',
     '选择两个数值字段（如配送时长与用户评分），看散点图和相关系数结论；复制后可在可视化设置里改成任意两个字段',
     'correlation',
     '{"schemaVersion":1,"metrics":[{"ref":"orders"}],"visuals":[{"id":"scatter","type":"scatter","title":"配送时长 vs 用户评分","metric":"review_score","dimension":"total_delivery_days","width":"full"}],"report":{"title":"相关性分析报告"}}',
     NOW(6));
