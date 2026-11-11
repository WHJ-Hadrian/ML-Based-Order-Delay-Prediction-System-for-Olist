package com.olist.models.repository;

import com.olist.models.entity.TrainingRun;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrainingRunRepository extends JpaRepository<TrainingRun, UUID> {

    Optional<TrainingRun> findByIdAndOwnerId(UUID id, String ownerId);

    Optional<TrainingRun> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    Page<TrainingRun> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId, Pageable pageable);

    List<TrainingRun> findTop20ByStatusOrderByCreatedAtAsc(String status);

    List<TrainingRun> findTop20ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(String status, Instant cutoff);

    boolean existsByIdAndStatus(UUID id, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TrainingRun r where r.id = :id and r.ownerId = :ownerId")
    Optional<TrainingRun> lockByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TrainingRun r where r.id = :id")
    Optional<TrainingRun> lockById(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingRun r
               set r.status = 'RUNNING',
                   r.stage = 'VALIDATING',
                   r.progress = 10,
                   r.claimToken = :claimToken,
                   r.leaseExpiresAt = :leaseExpiresAt,
                   r.attempts = r.attempts + 1,
                   r.startedAt = coalesce(r.startedAt, :now),
                   r.updatedAt = :now,
                   r.errorMessage = null
             where r.id = :id
               and r.status = 'QUEUED'
            """)
    int claimQueued(@Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingRun r
               set r.status = 'RUNNING',
                   r.stage = 'VALIDATING',
                   r.progress = 10,
                   r.claimToken = :claimToken,
                   r.leaseExpiresAt = :leaseExpiresAt,
                   r.attempts = r.attempts + 1,
                   r.updatedAt = :now,
                   r.errorMessage = null
             where r.id = :id
               and r.status = 'RUNNING'
               and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now)
            """)
    int reclaimExpired(@Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingRun r
               set r.status = 'FAILED',
                   r.stage = 'FAILED',
                   r.errorMessage = :message,
                   r.finishedAt = :now,
                   r.updatedAt = :now,
                   r.claimToken = null,
                   r.leaseExpiresAt = null,
                   r.ownerToken = null
             where r.id = :id
               and r.status = 'RUNNING'
               and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now)
            """)
    int failExpired(@Param("id") UUID id, @Param("message") String message, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingRun r
               set r.stage = :stage,
                   r.progress = :progress,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = 'RUNNING'
               and r.claimToken = :claimToken
            """)
    int updateStage(@Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("stage") String stage,
            @Param("progress") int progress,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TrainingRun r
               set r.leaseExpiresAt = :leaseExpiresAt,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = 'RUNNING'
               and r.claimToken = :claimToken
            """)
    int renewLease(@Param("id") UUID id,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now);
}
