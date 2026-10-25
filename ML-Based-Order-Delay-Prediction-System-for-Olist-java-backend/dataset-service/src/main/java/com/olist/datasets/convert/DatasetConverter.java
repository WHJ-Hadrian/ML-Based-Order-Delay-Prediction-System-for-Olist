package com.olist.datasets.convert;

import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.entity.DatasetFile;
import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.vo.DatasetDetailVO;
import com.olist.datasets.vo.DatasetFileVO;
import com.olist.datasets.vo.DatasetTableVO;
import com.olist.datasets.vo.DatasetVO;
import java.util.List;

public final class DatasetConverter {

    private DatasetConverter() {
    }

    public static DatasetVO toVO(DatasetVersion entity) {
        return new DatasetVO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getOriginalFilename(),
                formatOf(entity),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getContentType(),
                entity.getStatus(),
                entity.getTableCount(),
                entity.getRowCount(),
                entity.getFileCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public static DatasetDetailVO toDetailVO(
            DatasetVersion entity, List<DatasetFile> files, List<DatasetTable> tables) {
        return new DatasetDetailVO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getOriginalFilename(),
                formatOf(entity),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getContentType(),
                entity.getStatus(),
                entity.getTableCount(),
                entity.getRowCount(),
                entity.getFileCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                files.stream().map(DatasetConverter::toFileVO).toList(),
                tables.stream().map(DatasetConverter::toTableVO).toList());
    }

    public static DatasetTableVO toTableVO(DatasetTable table) {
        return new DatasetTableVO(
                table.getId(),
                table.getTableName(),
                table.getFileName(),
                table.getTableFormat(),
                table.getRowCount(),
                table.getColumnCount());
    }

    public static DatasetFileVO toFileVO(DatasetFile file) {
        return new DatasetFileVO(
                file.getId(),
                file.getOriginalFilename(),
                file.getSizeBytes(),
                file.getSha256(),
                file.getContentType(),
                file.getCreatedAt());
    }

    private static DatasetFormat formatOf(DatasetVersion entity) {
        return DatasetFormat.fromFilename(entity.getOriginalFilename()).orElse(null);
    }
}
