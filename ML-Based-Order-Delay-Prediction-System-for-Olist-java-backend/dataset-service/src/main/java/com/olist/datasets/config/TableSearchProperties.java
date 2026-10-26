package com.olist.datasets.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "olist.dataset.table-search")
public class TableSearchProperties {

    private long maxScannedRows = 250_000;

    public long getMaxScannedRows() {
        return maxScannedRows;
    }

    public void setMaxScannedRows(long maxScannedRows) {
        this.maxScannedRows = Math.max(maxScannedRows, 1);
    }
}
