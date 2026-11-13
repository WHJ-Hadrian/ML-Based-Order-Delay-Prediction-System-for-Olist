package com.olist.models.repository;

import com.olist.models.entity.ModelPrediction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelPredictionRepository extends JpaRepository<ModelPrediction, UUID> {

    List<ModelPrediction> findTop50ByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<ModelPrediction> findByIdAndOwnerId(UUID id, String ownerId);

    Optional<ModelPrediction> findFirstByStatusOrderByCreatedAtAsc(String status);

    List<ModelPrediction> findByStatus(String status);

    long deleteByIdAndOwnerId(UUID id, String ownerId);

    long deleteByOwnerId(String ownerId);
}
