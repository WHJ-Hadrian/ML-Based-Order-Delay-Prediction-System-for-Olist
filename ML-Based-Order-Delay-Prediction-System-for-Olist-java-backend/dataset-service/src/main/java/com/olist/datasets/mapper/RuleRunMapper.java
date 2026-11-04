package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleRun;
import com.olist.datasets.enums.RuleRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleRunMapper {

    int insert(RuleRun run);

    RuleRun findByIdAndOwner(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    RuleRun findByIdAndOwnerForUpdate(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    RuleRun findClaimCandidate(
            @Param("mode") String mode,
            @Param("now") Instant now);

    int failExpiredFullRuns(@Param("now") Instant now);

    RuleRun findByIdAndClaim(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("executionAttempt") int executionAttempt,
            @Param("now") Instant now);

    RuleRun findByIdAndClaimForUpdate(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("executionAttempt") int executionAttempt,
            @Param("now") Instant now);

    List<RuleRun> findByWorkbookId(
            @Param("workbookId") UUID workbookId,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    int updateStatusIfCurrent(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("expectedStatus") RuleRunStatus expectedStatus,
            @Param("newStatus") RuleRunStatus newStatus,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("errorMessage") String errorMessage);

    int cancelIfActive(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("finishedAt") Instant finishedAt);

    int claimIfAvailable(
            @Param("id") UUID id,
            @Param("expectedStatus") RuleRunStatus expectedStatus,
            @Param("expectedAttempt") int expectedAttempt,
            @Param("claimToken") UUID claimToken,
            @Param("claimedBy") String claimedBy,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now);

    int renewLease(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("executionAttempt") int executionAttempt,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("now") Instant now);

    int updateClaimedStatus(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("executionAttempt") int executionAttempt,
            @Param("expectedStatus") RuleRunStatus expectedStatus,
            @Param("newStatus") RuleRunStatus newStatus,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("errorMessage") String errorMessage,
            @Param("now") Instant now);
}
