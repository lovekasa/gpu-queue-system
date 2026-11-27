package org.lab.gpuqueue.allocation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.lab.gpuqueue.allocation.QueueOrderingPolicy.WaitingCandidate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class AllocationRepository {
    private final JdbcClient jdbc;

    public AllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean lockGpu(int gpuId) {
        return jdbc.sql("SELECT id FROM gpus WHERE id = :gpuId FOR UPDATE")
                .param("gpuId", gpuId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    boolean hasCurrentAllocation(int gpuId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM current_allocations WHERE gpu_id = :gpuId)")
                .param("gpuId", gpuId)
                .query(Boolean.class)
                .single();
    }

    List<Integer> findIdleCandidateGpuIds() {
        return jdbc.sql("""
                SELECT DISTINCT qi.gpu_id
                FROM queue_items qi
                LEFT JOIN current_allocations ca ON ca.gpu_id = qi.gpu_id
                WHERE qi.status = 'WAITING' AND ca.gpu_id IS NULL
                ORDER BY qi.gpu_id ASC
                """)
                .query(Integer.class)
                .list();
    }

    List<WaitingCandidate> findWaitingCandidates(int gpuId) {
        return jdbc.sql("""
                SELECT qi.id AS queue_item_id,
                       r.id AS request_id,
                       r.member_id,
                       qi.gpu_id,
                       r.duration_hours,
                       r.submitted_at,
                       r.receive_seq,
                       (r.request_type = 'EMERGENCY' AND r.emergency_status = 'APPROVED') AS approved_emergency
                FROM queue_items qi
                JOIN requests r ON r.id = qi.request_id
                WHERE qi.gpu_id = :gpuId AND qi.status = 'WAITING'
                """)
                .param("gpuId", gpuId)
                .query((row, index) -> new WaitingCandidate(
                        row.getObject("queue_item_id", UUID.class),
                        row.getObject("request_id", UUID.class),
                        row.getObject("member_id", UUID.class),
                        row.getInt("gpu_id"),
                        row.getInt("duration_hours"),
                        row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                        row.getLong("receive_seq"),
                        row.getBoolean("approved_emergency")))
                .list();
    }

    void markQueueItemAllocated(UUID queueItemId, int gpuId) {
        int updated = jdbc.sql("""
                UPDATE queue_items
                SET status = 'ALLOCATED'
                WHERE id = :queueItemId AND gpu_id = :gpuId AND status = 'WAITING'
                """)
                .param("queueItemId", queueItemId)
                .param("gpuId", gpuId)
                .update();
        if (updated != 1) {
            throw new DataIntegrityViolationException("Failed to transition queue item to ALLOCATED.");
        }
    }

    void insertAllocation(UUID allocationId, UUID queueItemId, int gpuId, Instant allocatedAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at)
                VALUES (:id, :queueItemId, :gpuId, :allocatedAt, :expiresAt)
                """)
                .param("id", allocationId)
                .param("queueItemId", queueItemId)
                .param("gpuId", gpuId)
                .param("allocatedAt", allocatedAt.atOffset(ZoneOffset.UTC))
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    void insertCurrentAllocation(int gpuId, UUID allocationId) {
        jdbc.sql("""
                INSERT INTO current_allocations (gpu_id, allocation_id)
                VALUES (:gpuId, :allocationId)
                """)
                .param("gpuId", gpuId)
                .param("allocationId", allocationId)
                .update();
    }

    long insertSystemAuditLog(Instant occurredAt, String action, UUID requestId, UUID queueItemId,
                              UUID allocationId, int gpuId, String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'SYSTEM', NULL, :action,
                    :requestId, :queueItemId, :allocationId, :gpuId,
                    NULL, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("action", action)
                .param("requestId", requestId)
                .param("queueItemId", queueItemId)
                .param("allocationId", allocationId)
                .param("gpuId", gpuId)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "TRANSITION_SEQ");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new DataIntegrityViolationException("Missing generated transition_seq");
        }
        return key.longValue();
    }

    long insertAuditLog(Instant occurredAt, UUID requestId, UUID queueItemId,
                        UUID allocationId, int gpuId, String beforeState, String afterState) {
        return insertSystemAuditLog(occurredAt, "ALLOCATED", requestId, queueItemId,
                allocationId, gpuId, beforeState, afterState);
    }

    void insertInboxMessage(UUID messageId, UUID recipientId, long transitionSeq, UUID allocationId,
                            UUID requestId, int gpuId, String title, String body, Instant createdAt) {
        insertInboxMessage(messageId, recipientId, transitionSeq, allocationId, requestId, gpuId,
                "ALLOCATED", title, body, createdAt);
    }

    void insertInboxMessage(UUID messageId, UUID recipientId, long transitionSeq, UUID allocationId,
                            UUID requestId, int gpuId, String messageType, String title, String body,
                            Instant createdAt) {
        jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, :allocationId, :requestId, :gpuId,
                    :messageType, :title, :body, :createdAt
                )
                """)
                .param("id", messageId)
                .param("recipientId", recipientId)
                .param("transitionSeq", transitionSeq)
                .param("allocationId", allocationId)
                .param("requestId", requestId)
                .param("gpuId", gpuId)
                .param("messageType", messageType)
                .param("title", title)
                .param("body", body)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    long insertMemberActionAuditLog(Instant occurredAt, UUID memberId, String action, UUID requestId,
                                    UUID queueItemId, UUID allocationId, int gpuId,
                                    String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'MEMBER', :actorAccountId, :action,
                    :requestId, :queueItemId, :allocationId, :gpuId,
                    NULL, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("actorAccountId", memberId)
                .param("action", action)
                .param("requestId", requestId)
                .param("queueItemId", queueItemId)
                .param("allocationId", allocationId)
                .param("gpuId", gpuId)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "TRANSITION_SEQ");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new DataIntegrityViolationException("Missing generated transition_seq");
        }
        return key.longValue();
    }

    long insertStartAuditLog(Instant occurredAt, UUID memberId, UUID requestId, UUID queueItemId,
                             UUID allocationId, int gpuId, String beforeState, String afterState) {
        return insertMemberActionAuditLog(occurredAt, memberId, "STARTED", requestId, queueItemId,
                allocationId, gpuId, beforeState, afterState);
    }

    long insertReturnAuditLog(Instant occurredAt, UUID memberId, UUID requestId, UUID queueItemId,
                              UUID allocationId, int gpuId, String beforeState, String afterState) {
        return insertMemberActionAuditLog(occurredAt, memberId, "RETURNED", requestId, queueItemId,
                allocationId, gpuId, beforeState, afterState);
    }

    Optional<OwnedAllocationState> findOwnedAllocationState(UUID memberId, UUID allocationId) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.ended_at,
                       a.end_reason,
                       (ca.allocation_id IS NOT NULL) AS is_current
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                LEFT JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE r.member_id = :memberId AND a.id = :allocationId
                """)
                .param("memberId", memberId)
                .param("allocationId", allocationId)
                .query((row, index) -> {
                    OffsetDateTime rawStartedAt = row.getObject("started_at", OffsetDateTime.class);
                    OffsetDateTime rawEndedAt = row.getObject("ended_at", OffsetDateTime.class);
                    return new OwnedAllocationState(
                            row.getObject("id", UUID.class),
                            row.getObject("request_id", UUID.class),
                            row.getObject("queue_item_id", UUID.class),
                            row.getInt("gpu_id"),
                            row.getInt("duration_hours"),
                            row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                            row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            rawStartedAt != null ? rawStartedAt.toInstant() : null,
                            rawEndedAt != null ? rawEndedAt.toInstant() : null,
                            row.getString("end_reason"),
                            row.getBoolean("is_current"));
                })
                .optional();
    }

    boolean markAllocationStarted(UUID allocationId, Instant startedAt) {
        int updated = jdbc.sql("""
                UPDATE allocations
                SET started_at = :startedAt
                WHERE id = :allocationId AND started_at IS NULL
                """)
                .param("allocationId", allocationId)
                .param("startedAt", startedAt.atOffset(ZoneOffset.UTC))
                .update();
        return updated == 1;
    }

    void markAllocationEnded(UUID allocationId, Instant endedAt, String endReason) {
        int updated = jdbc.sql("""
                UPDATE allocations
                SET ended_at = :endedAt, end_reason = :endReason
                WHERE id = :allocationId AND ended_at IS NULL
                """)
                .param("allocationId", allocationId)
                .param("endedAt", endedAt.atOffset(ZoneOffset.UTC))
                .param("endReason", endReason)
                .update();
        if (updated != 1) {
            throw new DataIntegrityViolationException("Failed to mark allocation ended: " + allocationId);
        }
    }

    void deleteCurrentAllocation(int gpuId, UUID allocationId) {
        int deleted = jdbc.sql("""
                DELETE FROM current_allocations
                WHERE gpu_id = :gpuId AND allocation_id = :allocationId
                """)
                .param("gpuId", gpuId)
                .param("allocationId", allocationId)
                .update();
        if (deleted != 1) {
            throw new DataIntegrityViolationException("Failed to remove current_allocations for " + allocationId);
        }
    }

    long insertAdminAuditLog(Instant occurredAt, UUID adminId, String action, UUID requestId,
                             UUID queueItemId, UUID allocationId, int gpuId, String reason,
                             String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'ADMIN', :actorAccountId, :action,
                    :requestId, :queueItemId, :allocationId, :gpuId,
                    :reason, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("actorAccountId", adminId)
                .param("action", action)
                .param("requestId", requestId)
                .param("queueItemId", queueItemId)
                .param("allocationId", allocationId)
                .param("gpuId", gpuId)
                .param("reason", reason)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "TRANSITION_SEQ");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new DataIntegrityViolationException("Missing generated transition_seq");
        }
        return key.longValue();
    }

    List<PendingVerificationView> listPendingVerificationAllocations(Instant now) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       acc.display_name AS member_display_name,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at
                FROM current_allocations ca
                JOIN allocations a ON a.id = ca.allocation_id AND a.gpu_id = ca.gpu_id
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                JOIN accounts acc ON acc.id = r.member_id
                WHERE a.ended_at IS NULL
                  AND (
                      (a.started_at IS NOT NULL AND a.expires_at <= :now)
                      OR (a.started_at IS NULL AND a.not_started_anomaly_at IS NOT NULL)
                  )
                ORDER BY a.gpu_id ASC
                """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((row, idx) -> {
                    OffsetDateTime rawStarted = row.getObject("started_at", OffsetDateTime.class);
                    Instant startedAt = rawStarted != null ? rawStarted.toInstant() : null;
                    OffsetDateTime rawAnomaly = row.getObject("not_started_anomaly_at", OffsetDateTime.class);
                    Instant anomalyAt = rawAnomaly != null ? rawAnomaly.toInstant() : null;
                    Instant expiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
                    return new PendingVerificationView(
                            row.getString("id"),
                            row.getString("request_id"),
                            row.getString("queue_item_id"),
                            row.getString("gpu_id"),
                            row.getString("member_display_name"),
                            row.getInt("duration_hours"),
                            row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                            expiresAt,
                            startedAt,
                            anomalyAt,
                            deriveActiveStatus(startedAt, anomalyAt, expiresAt, now));
                })
                .list();
    }

    List<UUID> findExpiryCandidateAllocationIds(Instant now) {
        return jdbc.sql("""
                SELECT a.id
                FROM current_allocations ca
                JOIN allocations a ON a.id = ca.allocation_id AND a.gpu_id = ca.gpu_id
                WHERE a.ended_at IS NULL
                  AND (a.expires_at <= :now OR (a.started_at IS NULL AND a.not_started_anomaly_at IS NULL))
                ORDER BY a.gpu_id ASC
                """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    Optional<AllocationLifecycleState> findAllocationLifecycleState(UUID allocationId) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       r.member_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at,
                       a.overdue_notified_expires_at,
                       a.ended_at,
                       a.end_reason,
                       (ca.allocation_id IS NOT NULL) AS is_current
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                LEFT JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE a.id = :allocationId
                """)
                .param("allocationId", allocationId)
                .query((row, index) -> {
                    OffsetDateTime rawStarted = row.getObject("started_at", OffsetDateTime.class);
                    OffsetDateTime rawAnomaly = row.getObject("not_started_anomaly_at", OffsetDateTime.class);
                    OffsetDateTime rawOverdue = row.getObject("overdue_notified_expires_at", OffsetDateTime.class);
                    OffsetDateTime rawEnded = row.getObject("ended_at", OffsetDateTime.class);
                    return new AllocationLifecycleState(
                            row.getObject("id", UUID.class),
                            row.getObject("request_id", UUID.class),
                            row.getObject("member_id", UUID.class),
                            row.getObject("queue_item_id", UUID.class),
                            row.getInt("gpu_id"),
                            row.getInt("duration_hours"),
                            row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                            row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            rawStarted != null ? rawStarted.toInstant() : null,
                            rawAnomaly != null ? rawAnomaly.toInstant() : null,
                            rawOverdue != null ? rawOverdue.toInstant() : null,
                            rawEnded != null ? rawEnded.toInstant() : null,
                            row.getString("end_reason"),
                            row.getBoolean("is_current"));
                })
                .optional();
    }

    void markNotStartedAnomaly(UUID allocationId, Instant anomalyAt) {
        int updated = jdbc.sql("""
                UPDATE allocations
                SET not_started_anomaly_at = :anomalyAt
                WHERE id = :allocationId AND not_started_anomaly_at IS NULL AND ended_at IS NULL
                """)
                .param("allocationId", allocationId)
                .param("anomalyAt", anomalyAt.atOffset(ZoneOffset.UTC))
                .update();
        if (updated != 1) {
            throw new DataIntegrityViolationException("Failed to mark not_started_anomaly_at for " + allocationId);
        }
    }

    void markOverdueNotified(UUID allocationId, Instant expiresAt) {
        int updated = jdbc.sql("""
                UPDATE allocations
                SET overdue_notified_expires_at = :expiresAt
                WHERE id = :allocationId AND ended_at IS NULL
                """)
                .param("allocationId", allocationId)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
        if (updated != 1) {
            throw new DataIntegrityViolationException("Failed to mark overdue_notified_expires_at for " + allocationId);
        }
    }

    List<AllocationView> listAllocationsForMember(UUID memberId, Instant now) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE r.member_id = :memberId
                ORDER BY a.allocated_at DESC, r.receive_seq DESC, a.gpu_id ASC
                """)
                .param("memberId", memberId)
                .query((row, idx) -> readAllocation(row, now))
                .list();
    }

    Optional<AllocationView> findAllocationForMember(UUID memberId, UUID allocationId, Instant now) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE r.member_id = :memberId AND a.id = :allocationId
                """)
                .param("memberId", memberId)
                .param("allocationId", allocationId)
                .query((row, idx) -> readAllocation(row, now))
                .optional();
    }

    List<Integer> listAllGpuIds() {
        return jdbc.sql("SELECT id FROM gpus ORDER BY id ASC")
                .query(Integer.class)
                .list();
    }

    List<NamedWaitingCandidate> findNamedWaitingCandidates(int gpuId) {
        return jdbc.sql("""
                SELECT qi.id AS queue_item_id,
                       r.id AS request_id,
                       r.member_id,
                       acc.display_name AS member_name,
                       qi.gpu_id,
                       r.duration_hours,
                       r.submitted_at,
                       r.receive_seq,
                       (r.request_type = 'EMERGENCY' AND r.emergency_status = 'APPROVED') AS approved_emergency
                FROM queue_items qi
                JOIN requests r ON r.id = qi.request_id
                JOIN accounts acc ON acc.id = r.member_id
                WHERE qi.gpu_id = :gpuId AND qi.status = 'WAITING'
                """)
                .param("gpuId", gpuId)
                .query((row, index) -> new NamedWaitingCandidate(
                        new WaitingCandidate(
                                row.getObject("queue_item_id", UUID.class),
                                row.getObject("request_id", UUID.class),
                                row.getObject("member_id", UUID.class),
                                row.getInt("gpu_id"),
                                row.getInt("duration_hours"),
                                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                                row.getLong("receive_seq"),
                                row.getBoolean("approved_emergency")),
                        row.getString("member_name")))
                .list();
    }

    void updateAllocationExpiresAt(UUID allocationId, Instant newExpiresAt) {
        int updated = jdbc.sql("UPDATE allocations SET expires_at = :expiresAt WHERE id = :id AND ended_at IS NULL")
                .param("expiresAt", newExpiresAt.atOffset(ZoneOffset.UTC))
                .param("id", allocationId)
                .update();
        if (updated != 1) {
            throw new DataIntegrityViolationException("Failed to update expires_at for allocation " + allocationId);
        }
    }

    UUID insertRenewalRequest(UUID allocationId, int gpuId, UUID memberId, Instant requestedExpiresAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO renewal_requests (
                    id, allocation_id, gpu_id, member_id, requested_expires_at, status, created_at
                ) VALUES (
                    :id, :allocationId, :gpuId, :memberId, :requestedExpiresAt, 'PENDING', :createdAt
                )
                """)
                .param("id", id)
                .param("allocationId", allocationId)
                .param("gpuId", gpuId)
                .param("memberId", memberId)
                .param("requestedExpiresAt", requestedExpiresAt.atOffset(ZoneOffset.UTC))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    List<RenewalRequestView> listRenewalsForMember(UUID memberId) {
        return jdbc.sql("""
                SELECT rr.id, rr.allocation_id, rr.gpu_id, rr.member_id, acc.display_name AS member_name,
                       a.expires_at AS current_expires_at, rr.requested_expires_at, rr.status,
                       rr.approved_expires_at, rr.reviewed_at, rr.review_reason, rr.created_at
                FROM renewal_requests rr
                JOIN accounts acc ON acc.id = rr.member_id
                JOIN allocations a ON a.id = rr.allocation_id
                WHERE rr.member_id = :memberId
                ORDER BY rr.created_at DESC
                """)
                .param("memberId", memberId)
                .query(this::readRenewal)
                .list();
    }

    List<RenewalRequestView> listPendingRenewals() {
        return jdbc.sql("""
                SELECT rr.id, rr.allocation_id, rr.gpu_id, rr.member_id, acc.display_name AS member_name,
                       a.expires_at AS current_expires_at, rr.requested_expires_at, rr.status,
                       rr.approved_expires_at, rr.reviewed_at, rr.review_reason, rr.created_at
                FROM renewal_requests rr
                JOIN accounts acc ON acc.id = rr.member_id
                JOIN allocations a ON a.id = rr.allocation_id
                WHERE rr.status = 'PENDING'
                ORDER BY rr.created_at ASC
                """)
                .query(this::readRenewal)
                .list();
    }

    Optional<RenewalRequestView> findRenewalById(UUID renewalId) {
        return jdbc.sql("""
                SELECT rr.id, rr.allocation_id, rr.gpu_id, rr.member_id, acc.display_name AS member_name,
                       a.expires_at AS current_expires_at, rr.requested_expires_at, rr.status,
                       rr.approved_expires_at, rr.reviewed_at, rr.review_reason, rr.created_at
                FROM renewal_requests rr
                JOIN accounts acc ON acc.id = rr.member_id
                JOIN allocations a ON a.id = rr.allocation_id
                WHERE rr.id = :id
                """)
                .param("id", renewalId)
                .query(this::readRenewal)
                .optional();
    }

    int updateRenewalReview(UUID renewalId, String newStatus, Instant approvedExpiresAt,
                            UUID adminId, Instant reviewedAt, String reason) {
        return jdbc.sql("""
                UPDATE renewal_requests
                SET status = :newStatus,
                    approved_expires_at = :approvedExpiresAt,
                    reviewed_by = :adminId,
                    reviewed_at = :reviewedAt,
                    review_reason = :reason
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("newStatus", newStatus)
                .param("approvedExpiresAt", approvedExpiresAt != null ? approvedExpiresAt.atOffset(ZoneOffset.UTC) : null)
                .param("adminId", adminId)
                .param("reviewedAt", reviewedAt.atOffset(ZoneOffset.UTC))
                .param("reason", reason)
                .param("id", renewalId)
                .update();
    }

    long insertAdminAllocationAudit(UUID adminId, Instant occurredAt, String action,
                                    UUID requestId, UUID queueItemId, UUID allocationId, int gpuId,
                                    String reason, String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'ADMIN', :actorAccountId, :action,
                    :requestId, :queueItemId, :allocationId, :gpuId,
                    :reason, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("actorAccountId", adminId)
                .param("action", action)
                .param("requestId", requestId)
                .param("queueItemId", queueItemId)
                .param("allocationId", allocationId)
                .param("gpuId", gpuId)
                .param("reason", reason)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "transition_seq");
        Number seq = keyHolder.getKey();
        if (seq == null) {
            throw new DataIntegrityViolationException("Failed to obtain audit transition_seq.");
        }
        return seq.longValue();
    }

    private RenewalRequestView readRenewal(ResultSet row, int index) throws SQLException {
        OffsetDateTime rawApproved = row.getObject("approved_expires_at", OffsetDateTime.class);
        OffsetDateTime rawReviewed = row.getObject("reviewed_at", OffsetDateTime.class);
        return new RenewalRequestView(
                row.getString("id"),
                row.getString("allocation_id"),
                row.getString("gpu_id"),
                row.getString("member_id"),
                row.getString("member_name"),
                row.getObject("current_expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("requested_expires_at", OffsetDateTime.class).toInstant(),
                row.getString("status"),
                rawApproved != null ? rawApproved.toInstant() : null,
                rawReviewed != null ? rawReviewed.toInstant() : null,
                row.getString("review_reason"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenewalRequestView(
            String id,
            String allocationId,
            String gpuId,
            String memberId,
            String memberName,
            Instant currentExpiresAt,
            Instant requestedExpiresAt,
            String status,
            Instant approvedExpiresAt,
            Instant reviewedAt,
            String reviewReason,
            Instant createdAt
    ) {}

    List<QueueOrderingPolicy.ReorderRule> findReorderRules(int gpuId) {
        return jdbc.sql("""
                SELECT id, target_queue_item_id, before_queue_item_id, after_queue_item_id
                FROM queue_reorders
                WHERE gpu_id = :gpuId
                ORDER BY id ASC
                """)
                .param("gpuId", gpuId)
                .query((row, index) -> new QueueOrderingPolicy.ReorderRule(
                        row.getLong("id"),
                        row.getObject("target_queue_item_id", UUID.class),
                        row.getObject("before_queue_item_id", UUID.class),
                        row.getObject("after_queue_item_id", UUID.class)))
                .list();
    }

    long insertReorderRule(int gpuId, UUID targetQueueItemId, UUID beforeQueueItemId, UUID afterQueueItemId,
                           UUID adminId, String reason, Instant createdAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO queue_reorders (
                    gpu_id, target_queue_item_id, before_queue_item_id, after_queue_item_id,
                    actor_account_id, reason, created_at
                ) VALUES (
                    :gpuId, :targetQueueItemId, :beforeQueueItemId, :afterQueueItemId,
                    :actorAccountId, :reason, :createdAt
                )
                """)
                .param("gpuId", gpuId)
                .param("targetQueueItemId", targetQueueItemId)
                .param("beforeQueueItemId", beforeQueueItemId)
                .param("afterQueueItemId", afterQueueItemId)
                .param("actorAccountId", adminId)
                .param("reason", reason)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update(keyHolder, "id");
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new DataIntegrityViolationException("Failed to obtain queue_reorders id.");
        }
        return id.longValue();
    }

    long insertQueueReorderAudit(UUID adminId, Instant occurredAt, UUID requestId, UUID targetQueueItemId, int gpuId,
                                 String reason, String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'ADMIN', :actorAccountId, 'QUEUE_REORDERED',
                    :requestId, :queueItemId, NULL, :gpuId,
                    :reason, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("actorAccountId", adminId)
                .param("requestId", requestId)
                .param("queueItemId", targetQueueItemId)
                .param("gpuId", gpuId)
                .param("reason", reason)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "transition_seq");
        Number seq = keyHolder.getKey();
        if (seq == null) {
            throw new DataIntegrityViolationException("Failed to obtain audit transition_seq.");
        }
        return seq.longValue();
    }

    void insertQueueReorderInboxMessage(UUID recipientId, long transitionSeq, UUID requestId, int gpuId,
                                        String title, String body, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, NULL, :requestId, :gpuId,
                    'QUEUE_REORDERED', :title, :body, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("recipientId", recipientId)
                .param("transitionSeq", transitionSeq)
                .param("requestId", requestId)
                .param("gpuId", gpuId)
                .param("title", title)
                .param("body", body)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    long insertRequestAudit(UUID adminId, Instant occurredAt, String action, UUID requestId,
                            String reason, String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'ADMIN', :actorAccountId, :action,
                    :requestId, NULL, NULL, NULL,
                    :reason, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("actorAccountId", adminId)
                .param("action", action)
                .param("requestId", requestId)
                .param("reason", reason)
                .param("beforeState", beforeState)
                .param("afterState", afterState)
                .update(keyHolder, "transition_seq");
        Number seq = keyHolder.getKey();
        if (seq == null) {
            throw new DataIntegrityViolationException("Failed to obtain audit transition_seq.");
        }
        return seq.longValue();
    }

    void insertRequestInboxMessage(UUID recipientId, long transitionSeq, UUID requestId,
                                   String messageType, String title, String body, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, NULL, :requestId, NULL,
                    :messageType, :title, :body, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("recipientId", recipientId)
                .param("transitionSeq", transitionSeq)
                .param("requestId", requestId)
                .param("messageType", messageType)
                .param("title", title)
                .param("body", body)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    boolean markMessageRead(UUID memberId, UUID messageId, Instant readAt) {
        int updated = jdbc.sql("""
                UPDATE inbox_messages
                SET read_at = :readAt
                WHERE id = :messageId AND recipient_id = :memberId AND read_at IS NULL
                """)
                .param("readAt", readAt.atOffset(ZoneOffset.UTC))
                .param("messageId", messageId)
                .param("memberId", memberId)
                .update();
        return updated == 1;
    }

    List<InboxMessageView> listMessagesForMember(UUID memberId) {
        return jdbc.sql("""
                SELECT m.id,
                       m.transition_seq,
                       m.message_type,
                       m.request_id,
                       m.allocation_id,
                       m.gpu_id,
                       m.title,
                       m.body,
                       a.allocated_at,
                       a.expires_at,
                       m.created_at,
                       m.read_at
                FROM inbox_messages m
                LEFT JOIN allocations a ON a.id = m.allocation_id AND a.gpu_id = m.gpu_id
                WHERE m.recipient_id = :memberId
                ORDER BY m.transition_seq DESC
                """)
                .param("memberId", memberId)
                .query(this::readMessage)
                .list();
    }

    Optional<InboxMessageView> findMessageForMember(UUID memberId, UUID messageId) {
        return jdbc.sql("""
                SELECT m.id,
                       m.transition_seq,
                       m.message_type,
                       m.request_id,
                       m.allocation_id,
                       m.gpu_id,
                       m.title,
                       m.body,
                       a.allocated_at,
                       a.expires_at,
                       m.created_at,
                       m.read_at
                FROM inbox_messages m
                LEFT JOIN allocations a ON a.id = m.allocation_id AND a.gpu_id = m.gpu_id
                WHERE m.recipient_id = :memberId AND m.id = :messageId
                """)
                .param("memberId", memberId)
                .param("messageId", messageId)
                .query(this::readMessage)
                .optional();
    }

    static String deriveActiveStatus(Instant startedAt, Instant notStartedAnomalyAt, Instant expiresAt, Instant now) {
        if (startedAt != null) {
            return !now.isBefore(expiresAt) ? "OVERDUE" : "IN_USE";
        }
        if (notStartedAnomalyAt != null) {
            return "ANOMALY";
        }
        return "UNSTARTED";
    }

    private AllocationView readAllocation(ResultSet row, Instant now) throws SQLException {
        OffsetDateTime rawStartedAt = row.getObject("started_at", OffsetDateTime.class);
        Instant startedAt = rawStartedAt != null ? rawStartedAt.toInstant() : null;
        OffsetDateTime rawAnomalyAt = row.getObject("not_started_anomaly_at", OffsetDateTime.class);
        Instant anomalyAt = rawAnomalyAt != null ? rawAnomalyAt.toInstant() : null;
        Instant expiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
        return new AllocationView(
                row.getString("id"),
                row.getString("request_id"),
                row.getString("queue_item_id"),
                row.getString("gpu_id"),
                row.getInt("duration_hours"),
                row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                expiresAt,
                startedAt,
                anomalyAt,
                null,
                null,
                deriveActiveStatus(startedAt, anomalyAt, expiresAt, now));
    }

    private InboxMessageView readMessage(ResultSet row, int index) throws SQLException {
        OffsetDateTime rawAllocatedAt = row.getObject("allocated_at", OffsetDateTime.class);
        OffsetDateTime rawExpiresAt = row.getObject("expires_at", OffsetDateTime.class);
        OffsetDateTime rawReadAt = row.getObject("read_at", OffsetDateTime.class);
        return new InboxMessageView(
                row.getString("id"),
                row.getString("transition_seq"),
                row.getString("message_type"),
                row.getString("request_id"),
                row.getString("allocation_id"),
                row.getString("gpu_id"),
                row.getString("title"),
                row.getString("body"),
                rawAllocatedAt != null ? rawAllocatedAt.toInstant() : null,
                rawExpiresAt != null ? rawExpiresAt.toInstant() : null,
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                rawReadAt != null ? rawReadAt.toInstant() : null);
    }

    public record NamedWaitingCandidate(WaitingCandidate candidate, String memberName) {}

    public record PublicQueueEntryView(int position, String memberName) {}

    public record PublicGpuQueueView(String gpuId, List<PublicQueueEntryView> waiting) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingVerificationView(
            String id,
            String requestId,
            String queueItemId,
            String gpuId,
            String memberDisplayName,
            int durationHours,
            Instant allocatedAt,
            Instant expiresAt,
            Instant startedAt,
            Instant notStartedAnomalyAt,
            String status
    ) {}

    public record AllocationLifecycleState(
            UUID id,
            UUID requestId,
            UUID memberId,
            UUID queueItemId,
            int gpuId,
            int durationHours,
            Instant allocatedAt,
            Instant expiresAt,
            Instant startedAt,
            Instant notStartedAnomalyAt,
            Instant overdueNotifiedExpiresAt,
            Instant endedAt,
            String endReason,
            boolean current
    ) {}

    public record OwnedAllocationState(
            UUID id,
            UUID requestId,
            UUID queueItemId,
            int gpuId,
            int durationHours,
            Instant allocatedAt,
            Instant expiresAt,
            Instant startedAt,
            Instant endedAt,
            String endReason,
            boolean current
    ) {
        AllocationView toView() {
            String status = (endedAt != null || !current) ? "ENDED" : (startedAt != null ? "IN_USE" : "UNSTARTED");
            return new AllocationView(
                    id.toString(),
                    requestId.toString(),
                    queueItemId.toString(),
                    Integer.toString(gpuId),
                    durationHours,
                    allocatedAt,
                    expiresAt,
                    startedAt,
                    null,
                    endedAt,
                    endReason,
                    status);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AllocationView(
            String id,
            String requestId,
            String queueItemId,
            String gpuId,
            int durationHours,
            Instant allocatedAt,
            Instant expiresAt,
            Instant startedAt,
            Instant notStartedAnomalyAt,
            Instant endedAt,
            String endReason,
            String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxMessageView(
            String id,
            String transitionSeq,
            String type,
            String requestId,
            String allocationId,
            String gpuId,
            String title,
            String body,
            Instant allocatedAt,
            Instant expiresAt,
            Instant createdAt,
            Instant readAt
    ) {}
}
