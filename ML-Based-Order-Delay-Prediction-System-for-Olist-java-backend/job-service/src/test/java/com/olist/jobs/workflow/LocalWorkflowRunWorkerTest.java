package com.olist.jobs.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import com.olist.jobs.workflow.LocalWorkflowRunQueue.ClaimedRun;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:local-workflow-worker-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/olist",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/olist/protocol/openid-connect/certs",
    "jobs.runner=local",
    "jobs.local.worker.enabled=true",
    "jobs.local.worker.poll-interval=60000",
    "jobs.local.worker.lease-duration=PT30S"
})
class LocalWorkflowRunWorkerTest {
    @Autowired private WorkflowRunRepository runs;
    @Autowired private WorkflowRunNodeRepository nodes;
    @Autowired private LocalWorkflowRunQueue queue;
    @Autowired private WorkflowRunExecutionStore store;
    @Autowired private WorkflowRunActivityImpl executor;
    @Autowired private LocalWorkflowRunWorker worker;

    @BeforeEach
    void clearRuns() {
        nodes.deleteAll();
        runs.deleteAll();
    }

    @Test
    void claimsQueuedRunsAndReclaimsExpiredLease() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(id, UUID.randomUUID(), "owner-a", "Local run", 1,
                "{\"nodes\":[],\"edges\":[]}", null, now);
        run.setExecutionEngine("local");
        runs.saveAndFlush(run);

        ClaimedRun firstClaim = queue.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        WorkflowRun claimed = runs.findById(id).orElseThrow();
        assertEquals(id, firstClaim.id());
        assertEquals("RUNNING", claimed.getStatus());
        assertEquals("worker-a", claimed.getExecutionLeaseOwner());

        Instant expiredAt = Instant.now();
        claimed.claim("stopped-worker", UUID.randomUUID().toString(), expiredAt.minusSeconds(1), expiredAt);
        runs.saveAndFlush(claimed);

        ClaimedRun recoveredClaim = queue.claimNext("worker-b", Duration.ofSeconds(30)).orElseThrow();
        WorkflowRun recovered = runs.findById(id).orElseThrow();
        assertEquals(id, recoveredClaim.id());
        assertEquals("worker-b", recovered.getExecutionLeaseOwner());
        assertNotEquals(firstClaim.leaseToken(), recoveredClaim.leaseToken());
        assertTrue(recovered.getExecutionLeaseUntil().isAfter(Instant.now()));
    }

    @Test
    void rejectsStaleWritesAndResetsNodesOnTakeover() {
        WorkflowRun run = queuedRun();
        ClaimedRun first = queue.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(store.startNode(run.getId(), first, "source", "Read sample"));
        assertTrue(store.completeNode(run.getId(), first, "source", "{}", "Read", 100, "[{}]"));
        expire(run.getId());
        assertFalse(store.succeed(run.getId(), first, "[{}]"));
        ClaimedRun next = queue.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertNotEquals(first.leaseToken(), next.leaseToken());
        assertFalse(queue.renew(first, Duration.ofSeconds(30)));
        queue.release(first);
        assertTrue(queue.renew(next, Duration.ofSeconds(30)));
        assertFalse(store.startNode(run.getId(), first, "source", "Old worker"));
        assertFalse(store.completeNode(run.getId(), first, "source", "{}", "Old result", 100, "[{}]"));
        assertFalse(store.fail(run.getId(), first, "source", "Old error"));
        WorkflowRunNode node = nodes.findByRunIdAndNodeId(run.getId(), "source").orElseThrow();
        assertEquals("QUEUED", node.getStatus());
        assertNull(node.getResultJson());
        assertNull(node.getFinishedAt());
        assertEquals(0, runs.findById(run.getId()).orElseThrow().getProgress());
        assertNull(runs.findById(run.getId()).orElseThrow().getOutputJson());
    }

    @Test
    void runningCancellationPreventsResultWrite() {
        WorkflowRun run = queuedRun();
        ClaimedRun claim = queue.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(store.startNode(run.getId(), claim, "source", "Read sample"));
        requestCancellation(run.getId());
        assertFalse(store.completeNode(run.getId(), claim, "source", "{}", "Read", 100, "[{}]"));
        assertEquals("CANCELED", runs.findById(run.getId()).orElseThrow().getStatus());
        assertEquals("CANCELED", nodes.findByRunIdAndNodeId(run.getId(), "source").orElseThrow().getStatus());
        assertNull(runs.findById(run.getId()).orElseThrow().getExecutionLeaseToken());
    }

    @Test
    void reclaimsExpiredCancellationInsteadOfLeavingItStuck() {
        WorkflowRun run = queuedRun();
        queue.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        requestCancellation(run.getId());
        expire(run.getId());
        ClaimedRun claim = queue.claimNext("worker-b", Duration.ofSeconds(30)).orElseThrow();
        executor.executeLocal(claim);
        assertEquals("CANCELED", runs.findById(run.getId()).orElseThrow().getStatus());
        assertEquals("CANCELED", nodes.findByRunIdAndNodeId(run.getId(), "source").orElseThrow().getStatus());
    }

    @Test
    void queuedCancellationRemovesRunFromQueue() {
        WorkflowRun run = queuedRun();
        store.cancel(run.getId());
        assertTrue(queue.claimNext("worker", Duration.ofSeconds(30)).isEmpty());
        assertEquals("CANCELED", nodes.findByRunIdAndNodeId(run.getId(), "source").orElseThrow().getStatus());
    }

    @Test
    void renewsLeaseAndCompletesRunWithoutBrowser() {
        WorkflowRun run = queuedRun();
        ClaimedRun claim = queue.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(queue.claimNext("worker-b", Duration.ofSeconds(30)).isEmpty());
        assertTrue(queue.renew(claim, Duration.ofSeconds(60)));
        assertTrue(runs.findById(run.getId()).orElseThrow().getExecutionLeaseUntil()
                .isAfter(Instant.now().plusSeconds(50)));
        executor.executeLocal(claim);
        WorkflowRun completed = runs.findById(run.getId()).orElseThrow();
        assertEquals("SUCCEEDED", completed.getStatus());
        assertEquals(100, completed.getProgress());
        assertNull(completed.getExecutionLeaseToken());
        assertFalse(queue.renew(claim, Duration.ofSeconds(30)));
        assertFalse(store.fail(run.getId(), claim, null, "Late failure"));
    }

    @Test
    void workerPollDispatchesPersistedRun() throws InterruptedException {
        WorkflowRun run = queuedRun();
        worker.poll();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!"SUCCEEDED".equals(runs.findById(run.getId()).orElseThrow().getStatus())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", runs.findById(run.getId()).orElseThrow().getStatus());
    }

    private WorkflowRun queuedRun() {
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), "owner-a", "Local run", 1,
                "{\"nodes\":[],\"edges\":[]}", null, now);
        run.setExecutionEngine("local");
        runs.saveAndFlush(run);
        nodes.saveAndFlush(new WorkflowRunNode(UUID.randomUUID(), run.getId(), "source", "DATA_SOURCE", now));
        return run;
    }

    private void expire(UUID id) {
        WorkflowRun run = runs.findById(id).orElseThrow();
        run.expireLease(Instant.now().minusSeconds(1));
        runs.saveAndFlush(run);
    }

    private void requestCancellation(UUID id) {
        WorkflowRun run = runs.findById(id).orElseThrow();
        run.requestCancellation(Instant.now());
        runs.saveAndFlush(run);
    }
}
