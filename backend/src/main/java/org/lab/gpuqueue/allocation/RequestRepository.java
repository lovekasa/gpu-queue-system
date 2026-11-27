package org.lab.gpuqueue.allocation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestRepository {
    private final JdbcClient jdbc;
    private final Clock clock;

    public RequestRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    UUID create(UUID memberId, Instant submittedAt, int durationHours, List<Integer> gpuIds) {
        return create(memberId, submittedAt, durationHours, gpuIds, false);
    }

    UUID create(UUID memberId, Instant submittedAt, int durationHours, List<Integer> gpuIds, boolean emergency) {
        UUID id = UUID.randomUUID();
        String requestType = emergency ? "EMERGENCY" : "ORDINARY";
        String emergencyStatus = emergency ? "PENDING" : null;
        jdbc.sql("""
                INSERT INTO requests (id, member_id, submitted_at, duration_hours, request_type, emergency_status)
                VALUES (:id, :memberId, :submittedAt, :durationHours, :requestType, :emergencyStatus)
                """)
                .param("id", id).param("memberId", memberId)
                .param("submittedAt", submittedAt.atOffset(ZoneOffset.UTC))
                .param("durationHours", durationHours)
                .param("requestType", requestType)
                .param("emergencyStatus", emergencyStatus).update();
        for (Integer gpuId : gpuIds) {
            jdbc.sql("""
                    INSERT INTO queue_items (id, request_id, gpu_id, status)
                    VALUES (:id, :requestId, :gpuId, 'WAITING')
                    """)
                    .param("id", UUID.randomUUID()).param("requestId", id).param("gpuId", gpuId).update();
        }
        return id;
    }

    List<RequestView> listForMember(UUID memberId) {
        return jdbc.sql("""
                SELECT id, submitted_at, receive_seq, duration_hours,
                       request_type, emergency_status, emergency_reviewed_at, emergency_review_reason
                FROM requests WHERE member_id = :memberId ORDER BY receive_seq DESC
                """)
                .param("memberId", memberId).query(this::readRequest).list();
    }

    Optional<RequestView> findForMember(UUID memberId, UUID requestId) {
        return jdbc.sql("""
                SELECT id, submitted_at, receive_seq, duration_hours,
                       request_type, emergency_status, emergency_reviewed_at, emergency_review_reason
                FROM requests WHERE member_id = :memberId AND id = :id
                """)
                .param("memberId", memberId).param("id", requestId).query(this::readRequest).optional();
    }

    List<AdminEmergencyRequestView> listPendingEmergencyRequests() {
        return jdbc.sql("""
                SELECT r.id, r.member_id, a.display_name AS member_name,
                       r.submitted_at, r.receive_seq, r.duration_hours,
                       r.request_type, r.emergency_status, r.emergency_reviewed_at, r.emergency_review_reason
                FROM requests r
                JOIN accounts a ON a.id = r.member_id
                WHERE r.request_type = 'EMERGENCY' AND r.emergency_status = 'PENDING'
                ORDER BY r.submitted_at ASC, r.receive_seq ASC
                """)
                .query(this::readAdminEmergencyRequest).list();
    }

    Optional<AdminEmergencyRequestView> findAdminEmergencyRequest(UUID requestId) {
        return jdbc.sql("""
                SELECT r.id, r.member_id, a.display_name AS member_name,
                       r.submitted_at, r.receive_seq, r.duration_hours,
                       r.request_type, r.emergency_status, r.emergency_reviewed_at, r.emergency_review_reason
                FROM requests r
                JOIN accounts a ON a.id = r.member_id
                WHERE r.id = :id AND r.request_type = 'EMERGENCY'
                """)
                .param("id", requestId)
                .query(this::readAdminEmergencyRequest).optional();
    }

    int updateEmergencyReview(UUID requestId, String newStatus, UUID adminId, Instant reviewedAt, String reason) {
        return jdbc.sql("""
                UPDATE requests
                SET emergency_status = :newStatus,
                    emergency_reviewed_at = :reviewedAt,
                    emergency_reviewed_by = :adminId,
                    emergency_review_reason = :reason
                WHERE id = :id AND request_type = 'EMERGENCY' AND emergency_status = 'PENDING'
                """)
                .param("newStatus", newStatus)
                .param("reviewedAt", reviewedAt.atOffset(ZoneOffset.UTC))
                .param("adminId", adminId)
                .param("reason", reason)
                .param("id", requestId)
                .update();
    }

    private RequestView readRequest(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        var items = jdbc.sql("""
                SELECT qi.id,
                       qi.gpu_id,
                       qi.status,
                       a.id AS allocation_id,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at,
                       a.ended_at,
                       a.end_reason
                FROM queue_items qi
                LEFT JOIN allocations a ON a.queue_item_id = qi.id AND a.gpu_id = qi.gpu_id
                WHERE qi.request_id = :id
                ORDER BY qi.gpu_id
                """)
                .param("id", id)
                .query(this::readQueueItem)
                .list();
        String requestType = row.getString("request_type");
        OffsetDateTime rawReviewedAt = row.getObject("emergency_reviewed_at", OffsetDateTime.class);
        return new RequestView(
                id.toString(),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("receive_seq"),
                row.getInt("duration_hours"),
                items,
                "EMERGENCY".equals(requestType) ? requestType : null,
                row.getString("emergency_status"),
                rawReviewedAt != null ? rawReviewedAt.toInstant() : null,
                row.getString("emergency_review_reason"));
    }

    private AdminEmergencyRequestView readAdminEmergencyRequest(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        var items = jdbc.sql("""
                SELECT qi.id,
                       qi.gpu_id,
                       qi.status,
                       a.id AS allocation_id,
                       a.allocated_at,
                       a.expires_at,
                       a.started_at,
                       a.not_started_anomaly_at,
                       a.ended_at,
                       a.end_reason
                FROM queue_items qi
                LEFT JOIN allocations a ON a.queue_item_id = qi.id AND a.gpu_id = qi.gpu_id
                WHERE qi.request_id = :id
                ORDER BY qi.gpu_id
                """)
                .param("id", id)
                .query(this::readQueueItem)
                .list();
        OffsetDateTime rawReviewedAt = row.getObject("emergency_reviewed_at", OffsetDateTime.class);
        return new AdminEmergencyRequestView(
                id.toString(),
                row.getObject("member_id", UUID.class).toString(),
                row.getString("member_name"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("receive_seq"),
                row.getInt("duration_hours"),
                row.getString("request_type"),
                row.getString("emergency_status"),
                rawReviewedAt != null ? rawReviewedAt.toInstant() : null,
                row.getString("emergency_review_reason"),
                items);
    }

    private QueueItemView readQueueItem(ResultSet item, int index) throws SQLException {
        String status = item.getString("status");
        if ("ALLOCATED".equals(status)) {
            UUID allocationId = item.getObject("allocation_id", UUID.class);
            if (allocationId == null) {
                throw new DataIntegrityViolationException("Missing allocation record for ALLOCATED queue item");
            }
            Instant allocatedAt = item.getObject("allocated_at", OffsetDateTime.class).toInstant();
            Instant expiresAt = item.getObject("expires_at", OffsetDateTime.class).toInstant();
            OffsetDateTime rawStartedAt = item.getObject("started_at", OffsetDateTime.class);
            Instant startedAt = rawStartedAt != null ? rawStartedAt.toInstant() : null;
            OffsetDateTime rawAnomalyAt = item.getObject("not_started_anomaly_at", OffsetDateTime.class);
            Instant anomalyAt = rawAnomalyAt != null ? rawAnomalyAt.toInstant() : null;
            OffsetDateTime rawEndedAt = item.getObject("ended_at", OffsetDateTime.class);
            Instant endedAt = rawEndedAt != null ? rawEndedAt.toInstant() : null;
            String endReason = item.getString("end_reason");
            String allocationStatus = endedAt != null
                    ? "ENDED"
                    : AllocationRepository.deriveActiveStatus(startedAt, anomalyAt, expiresAt, clock.instant());
            return new QueueItemView(
                    item.getString("id"),
                    item.getString("gpu_id"),
                    status,
                    allocationId.toString(),
                    allocatedAt,
                    expiresAt,
                    startedAt,
                    anomalyAt,
                    endedAt,
                    endReason,
                    allocationStatus);
        }
        return new QueueItemView(item.getString("id"), item.getString("gpu_id"), status,
                null, null, null, null, null, null, null, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestView(String id, Instant submittedAt, String receiveSeq, int durationHours,
                              List<QueueItemView> queueItems,
                              String requestType, String emergencyStatus,
                              Instant emergencyReviewedAt, String emergencyReviewReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminEmergencyRequestView(
            String id,
            String memberId,
            String memberName,
            Instant submittedAt,
            String receiveSeq,
            int durationHours,
            String requestType,
            String emergencyStatus,
            Instant emergencyReviewedAt,
            String emergencyReviewReason,
            List<QueueItemView> queueItems) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueItemView(String id, String gpuId, String status,
                                String allocationId, Instant allocatedAt, Instant expiresAt,
                                Instant startedAt, Instant notStartedAnomalyAt, Instant endedAt, String endReason,
                                String allocationStatus) {}
}
