package com.olist.datasets.dto;

import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

public class DatasetUploadRequest {

    private MultipartFile file;

    @Size(max = 255, message = "数据集名称不能超过 255 个字符")
    private String name;

    @Size(max = 1000, message = "数据集描述不能超过 1000 个字符")
    private String description;

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
