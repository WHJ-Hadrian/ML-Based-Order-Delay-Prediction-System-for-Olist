package com.olist.datasets.service;

import com.olist.datasets.dto.AnalysisWorkspaceRequest;
import com.olist.datasets.vo.AnalysisWorkspaceVO;
import java.util.List;
import java.util.UUID;

public interface AnalysisWorkspaceService {

    List<AnalysisWorkspaceVO> list();

    AnalysisWorkspaceVO create(AnalysisWorkspaceRequest request);

    AnalysisWorkspaceVO get(UUID id);

    AnalysisWorkspaceVO update(UUID id, AnalysisWorkspaceRequest request);

    void delete(UUID id);
}
