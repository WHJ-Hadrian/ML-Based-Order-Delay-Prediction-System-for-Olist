package com.olist.models.repository;

import com.olist.models.entity.ModelWorkflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ModelWorkflowRepository extends JpaRepository<ModelWorkflow, UUID> {

    List<ModelWorkflow> findTop100ByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    Optional<ModelWorkflow> findByIdAndOwnerId(UUID id, String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from ModelWorkflow workflow where workflow.id = :id and workflow.ownerId = :ownerId")
    Optional<ModelWorkflow> lockByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") String ownerId);
}
