package com.olist.datasets.profiler;

import com.olist.datasets.enums.DatasetFormat;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

public interface TablePreviewReader {

    TablePreview read(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            TablePreviewOptions options);

    TableRows readRows(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            int offset,
            int limit);

    TableRowSearch searchRows(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            String keyword,
            int columnIndex,
            long offset,
            int limit,
            long maxScannedRows);

    void forEachRow(
            String datasetFilename,
            String sourceFile,
            String tableName,
            DatasetFormat tableFormat,
            InputStream input,
            Consumer<List<String>> consumer);
}