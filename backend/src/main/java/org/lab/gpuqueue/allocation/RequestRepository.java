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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestRepository {
    private final JdbcClient jdbc;

    public RequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID create(UUID memberId, Instant submittedAt, int durationHours, List<Integer> gpuIds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO requests (id, member_id, submitted_at, duration_hours)
                VALUES (:id, :memberId, :submittedAt, :durationHours)
                """)
                .param("id", id).param("memberId", memberId)
                .param("submittedAt", submittedAt.atOffset(ZoneOffset.UTC))
                .param("durationHours", durationHours).update();
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
                SELECT id, submitted_at, receive_seq, duration_hours
                FROM requests WHERE member_id = :memberId ORDER BY receive_seq DESC
                """)
                .param("memberId", memberId).query(this::readRequest).list();
    }

    Optional<RequestView> findForMember(UUID memberId, UUID requestId) {
        return jdbc.sql("""
                SELECT id, submitted_at, receive_seq, duration_hours
                FROM requests WHERE member_id = :memberId AND id = :id
                """)
                .param("memberId", memberId).param("id", requestId).query(this::readRequest).optional();
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
                       a.started_at
                FROM queue_items qi
                LEFT JOIN allocations a ON a.queue_item_id = qi.id AND a.gpu_id = qi.gpu_id
                WHERE qi.request_id = :id
                ORDER BY qi.gpu_id
                """)
                .param("id", id)
                .query(this::readQueueItem)
                .list();
        return new RequestView(id.toString(), row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("receive_seq"), row.getInt("duration_hours"), items);
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
            return new QueueItemView(
                    item.getString("id"),
                    item.getString("gpu_id"),
                    status,
                    allocationId.toString(),
                    allocatedAt,
                    expiresAt,
                    startedAt,
                    startedAt != null ? "IN_USE" : "UNSTARTED");
        }
        return new QueueItemView(item.getString("id"), item.getString("gpu_id"), status, null, null, null, null, null);
    }

    public record RequestView(String id, Instant submittedAt, String receiveSeq, int durationHours,
                              List<QueueItemView> queueItems) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueItemView(String id, String gpuId, String status,
                                String allocationId, Instant allocatedAt, Instant expiresAt,
                                Instant startedAt, String allocationStatus) {}
}
