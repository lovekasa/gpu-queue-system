package org.lab.gpuqueue.allocation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RequestRepository {
    private final JdbcClient jdbc;

    public RequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean allGpusExist(List<Integer> gpuIds) {
        return jdbc.sql("SELECT COUNT(*) FROM gpus WHERE id IN (:gpuIds)")
                .param("gpuIds", gpuIds).query(Integer.class).single() == gpuIds.size();
    }

    RequestView create(UUID memberId, Instant submittedAt, int durationHours, List<Integer> gpuIds) {
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
        return findForMember(memberId, id).orElseThrow();
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
        var items = jdbc.sql("SELECT id, gpu_id, status FROM queue_items WHERE request_id = :id ORDER BY gpu_id")
                .param("id", id)
                .query((item, itemIndex) -> new QueueItemView(item.getString("id"),
                        item.getString("gpu_id"), item.getString("status"))).list();
        return new RequestView(id.toString(), row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                row.getString("receive_seq"), row.getInt("duration_hours"), items);
    }

    public record RequestView(String id, Instant submittedAt, String receiveSeq, int durationHours,
                              List<QueueItemView> queueItems) {}

    public record QueueItemView(String id, String gpuId, String status) {}
}
