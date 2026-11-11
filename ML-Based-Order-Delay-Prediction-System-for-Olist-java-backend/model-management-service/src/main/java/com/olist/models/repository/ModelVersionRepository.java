package com.olist.models.repository;

import com.olist.models.entity.ModelVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, UUID> {

    List<ModelVersion> findTop100ByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<ModelVersion> findByIdAndOwnerId(UUID id, String ownerId);

    long countByOwnerIdAndModelFamily(String ownerId, String modelFamily);
}