package com.olist.datasets.profiler;

import org.springframework.web.multipart.MultipartFile;

public interface DatasetProfiler {

    DatasetProfile profile(MultipartFile file, String filename);
}
