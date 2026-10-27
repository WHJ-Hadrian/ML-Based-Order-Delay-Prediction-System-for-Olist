package com.olist.datasets.dto;

import com.olist.datasets.enums.RuleWorkbookStatus;

public class RuleWorkbookQuery {

    private String keyword;
    private RuleWorkbookStatus status;
    private int page = 0;
    private int size = 20;

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public RuleWorkbookStatus getStatus() { return status; }
    public void setStatus(RuleWorkbookStatus status) { this.status = status; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}