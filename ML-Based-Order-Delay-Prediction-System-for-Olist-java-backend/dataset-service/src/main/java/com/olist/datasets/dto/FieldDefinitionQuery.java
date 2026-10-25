package com.olist.datasets.dto;

import com.olist.datasets.enums.FieldStatus;

public class FieldDefinitionQuery {

    private String keyword;
    private FieldStatus status;
    private String semanticRole;
    private int page = 0;
    private int size = 20;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public FieldStatus getStatus() {
        return status;
    }

    public void setStatus(FieldStatus status) {
        this.status = status;
    }

    public String getSemanticRole() {
        return semanticRole;
    }

    public void setSemanticRole(String semanticRole) {
        this.semanticRole = semanticRole;
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
