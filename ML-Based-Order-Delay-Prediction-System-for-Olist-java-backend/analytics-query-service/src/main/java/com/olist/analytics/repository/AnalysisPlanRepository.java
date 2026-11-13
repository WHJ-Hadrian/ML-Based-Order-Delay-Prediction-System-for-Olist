package com.olist.analytics.repository;

import com.olist.analytics.entity.AnalysisPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisPlanRepository extends JpaRepository<AnalysisPlan, UUID> {

    List<AnalysisPlan> findTop100ByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    Optional<AnalysisPlan> findByIdAndOwnerId(UUID id, String ownerId);

    long deleteByIdAndOwnerId(UUID id, String ownerId);
}
