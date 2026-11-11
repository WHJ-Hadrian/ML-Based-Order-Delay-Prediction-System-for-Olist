package com.olist.models.repository;

import com.olist.models.entity.ModelReportExport;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelReportExportRepository extends JpaRepository<ModelReportExport, UUID> {

    List<ModelReportExport> findTop50ByOwnerIdAndModelIdOrderByCreatedAtDesc(String ownerId, UUID modelId);
}
