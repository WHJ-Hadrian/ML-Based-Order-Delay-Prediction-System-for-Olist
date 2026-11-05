package com.olist.jobs.workflow;

import com.olist.jobs.workflow.LocalWorkflowRunQueue.ClaimedRun;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobs.local.worker.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "jobs.runner", havingValue = "local")
public class LocalWorkflowRunWorker {
    private static final Logger log = LoggerFactory.getLogger(LocalWorkflowRunWorker.class);
    private final LocalWorkflowRunQueue queue;
    private final WorkflowRunActivityImpl executor;
    private final Duration leaseDuration;
    private final int concurrency;
    private final String workerId = "local-" + UUID.randomUUID();
    private final ExecutorService workers;
    private final ScheduledExecutorService leaseRenewer;
    private final ConcurrentHashMap<UUID, TaskHandle> inFlight = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public LocalWorkflowRunWorker(LocalWorkflowRunQueue queue, WorkflowRunActivityImpl executor,
            @Value("${jobs.local.worker.concurrency:2}") int concurrency,
            @Value("${jobs.local.worker.lease-duration:PT30S}") Duration leaseDuration) {
        if (concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("本地 Worker 并发数必须在 1 到 16 之间");
        if (leaseDuration.compareTo(Duration.ofSeconds(10)) < 0) throw new IllegalArgumentException("本地 Worker 租约不能短于 10 秒");
        this.queue = queue;
        this.executor = executor;
        this.concurrency = concurrency;
        this.leaseDuration = leaseDuration;
        this.workers = Executors.newFixedThreadPool(concurrency, namedThreads("workflow-local-"));
        this.leaseRenewer = Executors.newSingleThreadScheduledExecutor(namedThreads("workflow-lease-"));
        long renewalMillis = leaseDuration.toMillis() / 3;
        leaseRenewer.scheduleWithFixedDelay(this::renewLeases, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
    }

    @Scheduled(fixedDelayString = "${jobs.local.worker.poll-interval:500}",
            initialDelayString = "${jobs.local.worker.poll-interval:500}")
    public synchronized void poll() {
        while (!stopping && inFlight.size() < concurrency) {
            var claimed = queue.claimNext(workerId, leaseDuration);
            if (claimed.isEmpty()) return;
            ClaimedRun run = claimed.get();
            TaskHandle handle = new TaskHandle(run);
            inFlight.put(run.id(), handle);
            try {
                workers.execute(() -> execute(handle));
            } catch (RuntimeException rejected) {
                inFlight.remove(run.id(), handle);
                queue.release(run);
                throw rejected;
            }
        }
    }

    private void execute(TaskHandle handle) {
        ClaimedRun claim = handle.claim;
        handle.thread = Thread.currentThread();
        try {
            if (!stopping && !handle.fenced) executor.executeLocal(claim);
        } catch (RuntimeException exception) {
            log.warn("Workflow run {} stopped with an execution error", claim.id(), exception);
        } finally {
            try {
                queue.release(claim);
            } catch (RuntimeException exception) {
                log.warn("Could not release workflow lease {}; it will expire", claim.id(), exception);
            } finally {
                inFlight.remove(claim.id(), handle);
            }
        }
    }

    private void renewLeases() {
        for (TaskHandle handle : inFlight.values()) {
            try {
                if (queue.renew(handle.claim, leaseDuration)) continue;
            } catch (RuntimeException exception) {
                log.warn("Could not renew workflow lease {}", handle.claim.id(), exception);
            }
            handle.fenced = true;
            Thread thread = handle.thread;
            if (thread != null) thread.interrupt();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopping = true;
        leaseRenewer.shutdownNow();
        workers.shutdownNow();
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class TaskHandle {
        private final ClaimedRun claim;
        private volatile Thread thread;
        private volatile boolean fenced;

        private TaskHandle(ClaimedRun claim) { this.claim = claim; }
    }
}
