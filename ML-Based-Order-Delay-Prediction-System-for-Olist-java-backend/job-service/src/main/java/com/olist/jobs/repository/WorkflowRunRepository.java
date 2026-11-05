package com.olist.jobs.repository;

import com.olist.jobs.entity.WorkflowRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    List<WorkflowRun> findTop50ByWorkflowIdAndOwnerIdOrderByCreatedAtDesc(UUID workflowId, String ownerId);
    Optional<WorkflowRun> findByIdAndOwnerId(UUID id, String ownerId);

    @Query(value = "select new com.olist.jobs.api.WorkflowRunSummaryResponse("
            + "run.id, run.workflowId, run.workflowName, run.workflowRevision, run.status, run.progress, "
            + "run.errorMessage, run.retryOfId, run.createdAt, run.startedAt, run.finishedAt, run.updatedAt) "
            + "from WorkflowRun run where run.ownerId = :ownerId "
            + "and (:status = '' or run.status = :status) "
            + "and (:keyword = '' or lower(run.workflowName) like lower(concat('%', :keyword, '%')) escape '!')",
            countQuery = "select count(run) from WorkflowRun run where run.ownerId = :ownerId "
                    + "and (:status = '' or run.status = :status) "
                    + "and (:keyword = '' or lower(run.workflowName) like lower(concat('%', :keyword, '%')) escape '!')")
    Page<com.olist.jobs.api.WorkflowRunSummaryResponse> searchOwnedRuns(
            @Param("ownerId") String ownerId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable);
}
