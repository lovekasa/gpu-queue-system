package org.lab.gpuqueue.allocation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                       r.receive_seq
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
                        row.getLong("receive_seq")))
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

    long insertAuditLog(Instant occurredAt, UUID requestId, UUID queueItemId,
                        UUID allocationId, int gpuId, String beforeState, String afterState) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'SYSTEM', NULL, 'ALLOCATED',
                    :requestId, :queueItemId, :allocationId, :gpuId,
                    NULL, :beforeState, :afterState
                )
                """)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
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

    void insertInboxMessage(UUID messageId, UUID recipientId, long transitionSeq, UUID allocationId,
                            UUID requestId, int gpuId, String title, String body, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, :allocationId, :requestId, :gpuId,
                    'ALLOCATED', :title, :body, :createdAt
                )
                """)
                .param("id", messageId)
                .param("recipientId", recipientId)
                .param("transitionSeq", transitionSeq)
                .param("allocationId", allocationId)
                .param("requestId", requestId)
                .param("gpuId", gpuId)
                .param("title", title)
                .param("body", body)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    List<AllocationView> listAllocationsForMember(UUID memberId) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE r.member_id = :memberId
                ORDER BY a.allocated_at DESC, r.receive_seq DESC, a.gpu_id ASC
                """)
                .param("memberId", memberId)
                .query(this::readAllocation)
                .list();
    }

    Optional<AllocationView> findAllocationForMember(UUID memberId, UUID allocationId) {
        return jdbc.sql("""
                SELECT a.id,
                       r.id AS request_id,
                       qi.id AS queue_item_id,
                       a.gpu_id,
                       r.duration_hours,
                       a.allocated_at,
                       a.expires_at
                FROM allocations a
                JOIN queue_items qi ON qi.id = a.queue_item_id AND qi.gpu_id = a.gpu_id
                JOIN requests r ON r.id = qi.request_id
                JOIN current_allocations ca ON ca.allocation_id = a.id AND ca.gpu_id = a.gpu_id
                WHERE r.member_id = :memberId AND a.id = :allocationId
                """)
                .param("memberId", memberId)
                .param("allocationId", allocationId)
                .query(this::readAllocation)
                .optional();
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
                       m.created_at
                FROM inbox_messages m
                JOIN allocations a ON a.id = m.allocation_id AND a.gpu_id = m.gpu_id
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
                       m.created_at
                FROM inbox_messages m
                JOIN allocations a ON a.id = m.allocation_id AND a.gpu_id = m.gpu_id
                WHERE m.recipient_id = :memberId AND m.id = :messageId
                """)
                .param("memberId", memberId)
                .param("messageId", messageId)
                .query(this::readMessage)
                .optional();
    }

    private AllocationView readAllocation(ResultSet row, int index) throws SQLException {
        return new AllocationView(
                row.getString("id"),
                row.getString("request_id"),
                row.getString("queue_item_id"),
                row.getString("gpu_id"),
                row.getInt("duration_hours"),
                row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                "UNSTARTED");
    }

    private InboxMessageView readMessage(ResultSet row, int index) throws SQLException {
        return new InboxMessageView(
                row.getString("id"),
                row.getString("transition_seq"),
                row.getString("message_type"),
                row.getString("request_id"),
                row.getString("allocation_id"),
                row.getString("gpu_id"),
                row.getString("title"),
                row.getString("body"),
                row.getObject("allocated_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public record AllocationView(
            String id,
            String requestId,
            String queueItemId,
            String gpuId,
            int durationHours,
            Instant allocatedAt,
            Instant expiresAt,
            String status
    ) {}

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
            Instant createdAt
    ) {}
}
