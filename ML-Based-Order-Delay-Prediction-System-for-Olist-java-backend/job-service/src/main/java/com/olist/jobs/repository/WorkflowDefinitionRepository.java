package com.olist.jobs.repository;

import com.olist.jobs.entity.WorkflowDefinition;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    List<WorkflowDefinition> findTop100ByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    Optional<WorkflowDefinition> findByIdAndOwnerId(UUID id, String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from WorkflowDefinition workflow where workflow.id = :id and workflow.ownerId = :ownerId")
    Optional<WorkflowDefinition> lockByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") String ownerId);

    long deleteByIdAndOwnerId(UUID id, String ownerId);
}
