package com.olist.datasets.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum DatasetFormat {
    CSV(".csv"),
    TSV(".tsv"),
    ZIP(".zip"),
    XLSX(".xlsx");

    private final String extension;

    DatasetFormat(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }

    public static Optional<DatasetFormat> fromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> lower.endsWith(format.extension))
                .findFirst();
    }

    public static Optional<DatasetFormat> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        String candidate = normalized;
        return Arrays.stream(values())
                .filter(format -> format.name().equals(candidate))
                .findFirst();
    }
}
