package com.olist.jobs.repository;

import com.olist.jobs.entity.WorkflowRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    List<WorkflowRun> findTop50ByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<WorkflowRun> findTop50ByWorkflowIdAndOwnerIdOrderByCreatedAtDesc(UUID workflowId, String ownerId);
    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, String ownerId);
}
