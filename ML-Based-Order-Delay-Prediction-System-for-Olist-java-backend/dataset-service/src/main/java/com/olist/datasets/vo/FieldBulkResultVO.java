package com.olist.datasets.vo;

import java.util.List;

public record FieldBulkResultVO(int created, int skipped, List<String> errors) {
}
