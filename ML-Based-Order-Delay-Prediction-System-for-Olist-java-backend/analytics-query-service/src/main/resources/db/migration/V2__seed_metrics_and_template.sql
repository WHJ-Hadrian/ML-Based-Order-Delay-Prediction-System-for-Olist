-- 系统指标库 + 延误分析模板

INSERT INTO metric_definitions
    (id, owner_id, metric_key, name, description, value_type, expression_json, format_json, created_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', NULL, 'orders', '订单量',
     '数据表内的订单行数', 'number', '{"type":"count"}', '{"decimals":0}', NOW(6)),
    ('11111111-1111-1111-1111-111111111112', NULL, 'delivered_orders', '已签收订单量',
     '存在签收时间的订单数', 'number',
     '{"type":"countIf","column":"total_delivery_days","op":"IS_NOT_NULL"}', '{"decimals":0}', NOW(6)),
    ('11111111-1111-1111-1111-111111111113', NULL, 'delayed_orders', '延误订单量',
     'is_delayed = 1 的订单数', 'number',
     '{"type":"countIf","column":"is_delayed","op":"EQ","value":1}', '{"decimals":0}', NOW(6)),
    ('11111111-1111-1111-1111-111111111114', NULL, 'delay_rate', '延误率',
     '延误订单量 ÷ 订单量', 'percent',
     '{"type":"ratio","left":{"type":"countIf","column":"is_delayed","op":"EQ","value":1},"right":{"type":"count"}}',
     '{"decimals":2,"suffix":"%"}', NOW(6)),
    ('11111111-1111-1111-1111-111111111115', NULL, 'avg_delivery_days', '平均配送耗时',
     '下单到签收的平均天数', 'duration',
     '{"type":"avg","column":"total_delivery_days"}', '{"decimals":2,"suffix":"天"}', NOW(6)),
    ('11111111-1111-1111-1111-111111111116', NULL, 'avg_delay_days', '平均延误天数',
     '仅统计延误订单，晚到多少天', 'duration',
     '{"type":"avgIf","column":"delay_days","op":"EQ","value":1}', '{"decimals":2,"suffix":"天"}', NOW(6));

INSERT INTO analysis_templates (template_key, name, description, category, spec_json, created_at)
VALUES
    ('delay_analysis', '订单延误分析',
     '整体延误指标 + 按月维度 + 延误率，可复制为自己的方案',
     'delay',
     '{"schemaVersion":1,"metrics":[{"ref":"orders"},{"ref":"delivered_orders"},{"ref":"delayed_orders"},{"ref":"delay_rate"},{"ref":"avg_delivery_days"},{"ref":"avg_delay_days"}],"time":{"column":"order_purchase_timestamp","grain":"month"},"visuals":[{"type":"kpi"},{"type":"table"},{"type":"bar","x":"month","y":"delay_rate"}],"report":{"title":"Olist 订单延误分析报告","sections":["kpi","table","chart"]}}',
     NOW(6));
