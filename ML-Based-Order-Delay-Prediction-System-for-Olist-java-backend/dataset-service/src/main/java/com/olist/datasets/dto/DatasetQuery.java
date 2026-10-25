package com.olist.datasets.dto;

import com.olist.datasets.enums.DatasetStatus;

public class DatasetQuery {

    private DatasetStatus status;
    private String keyword;
    private String format;
    private Boolean hasData;
    private int page = 0;
    private int size = 20;

    public DatasetStatus getStatus() {
        return status;
    }

    public void setStatus(DatasetStatus status) {
        this.status = status;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Boolean getHasData() {
        return hasData;
    }

    public void setHasData(Boolean hasData) {
        this.hasData = hasData;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
