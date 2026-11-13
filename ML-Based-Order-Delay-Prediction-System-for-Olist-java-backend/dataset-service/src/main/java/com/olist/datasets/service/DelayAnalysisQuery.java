package com.olist.datasets.service;

import java.util.List;

/** 延误分析的可选过滤条件：维度、时间范围、类目、卖家州、客户州。 */
public record DelayAnalysisQuery(
        String groupBy,
        String from,
        String to,
        List<String> categories,
        List<String> sellerStates,
        List<String> customerStates) {

    public static DelayAnalysisQuery empty() {
        return new DelayAnalysisQuery(null, null, null, List.of(), List.of(), List.of());
    }
}
