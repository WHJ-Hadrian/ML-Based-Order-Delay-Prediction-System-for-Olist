package com.olist.jobs.repository;

import com.olist.jobs.entity.WorkflowRunNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunNodeRepository extends JpaRepository<WorkflowRunNode, UUID> {
    List<WorkflowRunNode> findByRunIdOrderByCreatedAtAsc(UUID runId);
    Optional<WorkflowRunNode> findByRunIdAndNodeId(UUID runId, String nodeId);
}
