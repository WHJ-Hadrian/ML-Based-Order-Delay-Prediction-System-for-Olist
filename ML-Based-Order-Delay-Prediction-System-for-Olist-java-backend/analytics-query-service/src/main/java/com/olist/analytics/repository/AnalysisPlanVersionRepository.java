package com.olist.analytics.repository;

import com.olist.analytics.entity.AnalysisPlanVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisPlanVersionRepository extends JpaRepository<AnalysisPlanVersion, UUID> {

    Optional<AnalysisPlanVersion> findTopByPlanIdOrderByVersionNoDesc(UUID planId);

    Optional<AnalysisPlanVersion> findByPlanIdAndVersionNo(UUID planId, int versionNo);

    List<AnalysisPlanVersion> findByPlanIdOrderByVersionNoDesc(UUID planId);
}
