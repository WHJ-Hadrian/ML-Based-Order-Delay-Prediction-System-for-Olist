package com.olist.models.repository;

import com.olist.models.entity.ModelTrial;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelTrialRepository extends JpaRepository<ModelTrial, UUID> {

    List<ModelTrial> findTop100ByOwnerIdAndModelIdOrderByCreatedAtDesc(String ownerId, UUID modelId);

    Optional<ModelTrial> findByIdAndOwnerId(UUID id, String ownerId);

    List<ModelTrial> findAllByComparisonIdAndOwnerIdOrderByCreatedAtAsc(UUID comparisonId, String ownerId);
}
