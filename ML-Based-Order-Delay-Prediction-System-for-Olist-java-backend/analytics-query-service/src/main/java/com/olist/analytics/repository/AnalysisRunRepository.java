package com.olist.analytics.repository;

import com.olist.analytics.entity.AnalysisRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    List<AnalysisRun> findTop50ByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<AnalysisRun> findTop50ByOwnerIdAndPlanIdOrderByCreatedAtDesc(String ownerId, UUID planId);

    Optional<AnalysisRun> findByIdAndOwnerId(UUID id, String ownerId);
}
