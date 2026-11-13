package com.olist.analytics.repository;

import com.olist.analytics.entity.AnalysisTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisTemplateRepository extends JpaRepository<AnalysisTemplate, String> {

    List<AnalysisTemplate> findAllByOrderByCategoryAscNameAsc();
}
