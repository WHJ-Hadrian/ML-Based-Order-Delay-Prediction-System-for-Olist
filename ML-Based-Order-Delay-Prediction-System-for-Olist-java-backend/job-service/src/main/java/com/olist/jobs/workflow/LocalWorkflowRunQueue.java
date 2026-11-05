package com.olist.jobs.workflow;

import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocalWorkflowRunQueue {
    private final WorkflowRunRepository runs;
    private final WorkflowRunNodeRepository nodes;
    private final TransactionTemplate transactions;

    public LocalWorkflowRunQueue(WorkflowRunRepository runs, WorkflowRunNodeRepository nodes,
            PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.nodes = nodes;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Optional<ClaimedRun> claimNext(String workerId, Duration leaseDuration) {
        return transactions.execute(status -> {
            var candidates = runs.findClaimable(Instant.now(), PageRequest.of(0, 1));
            if (candidates.isEmpty()) return Optional.empty();
            WorkflowRun run = candidates.get(0);
            Instant now = Instant.now();
            boolean recovering = "RUNNING".equals(run.getStatus()) || "CANCELLING".equals(run.getStatus());
            if (!"local".equals(run.getExecutionEngine()) || (!"QUEUED".equals(run.getStatus())
                    && !(recovering && run.getExecutionLeaseUntil() != null
                            && !run.getExecutionLeaseUntil().isAfter(now)))) return Optional.empty();
            String token = UUID.randomUUID().toString();
            run.claim(workerId, token, now.plus(leaseDuration), now);
            if (recovering && !"CANCELLING".equals(run.getStatus())) {
                nodes.findByRunIdOrderByCreatedAtAsc(run.getId()).forEach(node -> {
                    node.resetForRecovery();
                    nodes.save(node);
                });
            }
            runs.saveAndFlush(run);
            return Optional.of(new ClaimedRun(run.getId(), run.getOwnerId(), run.getSnapshotJson(), workerId, token));
        });
    }

    public boolean renew(ClaimedRun claim, Duration leaseDuration) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            WorkflowRun run = runs.lockById(claim.id()).orElse(null);
            Instant now = Instant.now();
            if (run == null || !run.hasLease(claim.leaseOwner(), claim.leaseToken(), now)) return false;
            run.renewLease(claim.leaseOwner(), now.plus(leaseDuration), now);
            runs.save(run);
            return true;
        }));
    }

    public void release(ClaimedRun claim) {
        transactions.executeWithoutResult(status -> runs.lockById(claim.id()).ifPresent(run -> {
            if (claim.leaseOwner().equals(run.getExecutionLeaseOwner())
                    && claim.leaseToken().equals(run.getExecutionLeaseToken())) {
                run.expireLease(Instant.now());
                runs.save(run);
            }
        }));
    }

    public record ClaimedRun(UUID id, String ownerId, String snapshotJson, String leaseOwner, String leaseToken) { }
}
