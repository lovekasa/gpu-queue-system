package org.lab.gpuqueue.allocation;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lab.gpuqueue.GpuQueueApplication;
import org.lab.gpuqueue.allocation.QueueOrderingPolicy.WaitingCandidate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AllocationIntegrationTest.TestBeans.class)
class AllocationIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID MEMBER_C = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-25T08:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("allocations-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AllocationService allocationService;
    @Autowired AllocationScanner allocationScanner;
    @Autowired QueueOrderingPolicy orderingPolicy;
    @Autowired MutableClock clock;
    @Autowired FaultToggleAllocationRepository faultRepository;
    private String passwordHash;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        Flyway.configure().dataSource(JDBC_URL, "sa", "").load().migrate();
        properties.add("queue.jdbc-url", () -> JDBC_URL + ";IFEXISTS=TRUE");
        properties.add("queue.scanner.initial-delay-ms", () -> "86400000");
    }

    @BeforeEach
    void reset() {
        faultRepository.failInboxWithCheckedException = false;
        jdbc.sql("DELETE FROM inbox_messages").update();
        jdbc.sql("DELETE FROM audit_logs").update();
        jdbc.sql("DELETE FROM current_allocations").update();
        jdbc.sql("DELETE FROM allocations").update();
        jdbc.sql("DELETE FROM queue_items").update();
        jdbc.sql("DELETE FROM requests").update();
        jdbc.sql("DELETE FROM accounts").update();
        clock.now = BASE_TIME;
        if (passwordHash == null) {
            passwordHash = passwords.encode("integration-password");
        }
        seedAccount(jdbc, MEMBER_A, "alice", "MEMBER", passwordHash);
        seedAccount(jdbc, MEMBER_B, "bob", "MEMBER", passwordHash);
        seedAccount(jdbc, MEMBER_C, "carol", "MEMBER", passwordHash);
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void allocatesIdleCardImmediatelyAndLeavesBusyCardWaitingWithOriginalTimestampAndSequence() throws Exception {
        var bobSession = login("bob");
        var aliceSession = login("alice");

        var bobAllocated = submit(bobSession, List.of("2"), "12", 201);
        String bobAllocationId = bobAllocated.path("queueItems").get(0).path("allocationId").asText();

        Instant aliceSubmitTime = BASE_TIME.plus(Duration.ofMinutes(15));
        clock.now = aliceSubmitTime;
        var partial = submit(aliceSession, List.of("2", "3"), "24", 201);

        assertThat(partial.size()).isEqualTo(5);
        assertThat(partial.path("submittedAt").asText()).isEqualTo(aliceSubmitTime.toString());
        assertThat(partial.path("receiveSeq").isTextual()).isTrue();
        assertThat(partial.path("queueItems").size()).isEqualTo(2);

        var gpu2Item = partial.path("queueItems").get(0);
        assertThat(gpu2Item.path("gpuId").asText()).isEqualTo("2");
        assertThat(gpu2Item.path("status").asText()).isEqualTo("WAITING");
        assertThat(gpu2Item.size()).isEqualTo(3);

        var gpu3Item = partial.path("queueItems").get(1);
        assertThat(gpu3Item.path("gpuId").asText()).isEqualTo("3");
        assertThat(gpu3Item.path("status").asText()).isEqualTo("ALLOCATED");
        assertThat(gpu3Item.path("allocationId").isTextual()).isTrue();
        assertThat(gpu3Item.path("allocatedAt").asText()).isEqualTo(aliceSubmitTime.toString());
        assertThat(gpu3Item.path("expiresAt").asText())
                .isEqualTo(aliceSubmitTime.plus(Duration.ofHours(24)).toString());
        assertThat(gpu3Item.path("allocationStatus").asText()).isEqualTo("UNSTARTED");
        assertThat(gpu3Item.size()).isEqualTo(7);

        String aliceRequestId = partial.path("id").asText();
        String aliceQueueItemId = gpu3Item.path("id").asText();
        String aliceAllocationId = gpu3Item.path("allocationId").asText();
        Instant aliceExpiresAt = aliceSubmitTime.plus(Duration.ofHours(24));
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 2")
                .query(UUID.class).single()).isEqualTo(UUID.fromString(bobAllocationId));
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 3")
                .query(UUID.class).single()).isEqualTo(UUID.fromString(aliceAllocationId));
        assertThat(count("audit_logs")).isEqualTo(2);
        assertThat(count("inbox_messages")).isEqualTo(2);

        long aliceTransitionSeq = jdbc.sql("""
                SELECT transition_seq, occurred_at, actor_type, actor_account_id, action,
                       request_id, queue_item_id, allocation_id, gpu_id, reason, before_state, after_state
                FROM audit_logs
                WHERE allocation_id = :allocationId
                """)
                .param("allocationId", UUID.fromString(aliceAllocationId))
                .query((row, idx) -> {
                    assertThat(row.getObject("occurred_at", OffsetDateTime.class).toInstant()).isEqualTo(aliceSubmitTime);
                    assertThat(row.getString("actor_type")).isEqualTo("SYSTEM");
                    assertThat(row.getObject("actor_account_id")).isNull();
                    assertThat(row.getString("action")).isEqualTo("ALLOCATED");
                    assertThat(row.getObject("request_id", UUID.class)).isEqualTo(UUID.fromString(aliceRequestId));
                    assertThat(row.getObject("queue_item_id", UUID.class)).isEqualTo(UUID.fromString(aliceQueueItemId));
                    assertThat(row.getObject("allocation_id", UUID.class)).isEqualTo(UUID.fromString(aliceAllocationId));
                    assertThat(row.getInt("gpu_id")).isEqualTo(3);
                    assertThat(row.getString("reason")).isNull();
                    assertThat(row.getString("before_state")).isEqualTo("queueItemStatus=WAITING;currentAllocation=NONE");
                    assertThat(row.getString("after_state")).isEqualTo(
                            "queueItemStatus=ALLOCATED;allocationId=" + aliceAllocationId
                                    + ";allocationStatus=UNSTARTED;expiresAt=" + aliceExpiresAt);
                    return row.getLong("transition_seq");
                }).single();

        var aliceAllocations = getJson(aliceSession, "/api/allocations", 200);
        assertThat(aliceAllocations.size()).isEqualTo(1);
        var allocationNode = aliceAllocations.get(0);
        assertThat(allocationNode.size()).isEqualTo(8);
        assertThat(allocationNode.path("id").asText()).isEqualTo(aliceAllocationId);
        assertThat(allocationNode.path("requestId").asText()).isEqualTo(aliceRequestId);
        assertThat(allocationNode.path("queueItemId").asText()).isEqualTo(aliceQueueItemId);
        assertThat(allocationNode.path("gpuId").asText()).isEqualTo("3");
        assertThat(allocationNode.path("durationHours").asInt()).isEqualTo(24);
        assertThat(allocationNode.path("allocatedAt").asText()).isEqualTo(aliceSubmitTime.toString());
        assertThat(allocationNode.path("expiresAt").asText()).isEqualTo(aliceExpiresAt.toString());
        assertThat(allocationNode.path("status").asText()).isEqualTo("UNSTARTED");
        assertThat(getJson(aliceSession, "/api/allocations/" + aliceAllocationId, 200)).isEqualTo(allocationNode);

        var aliceMessages = getJson(aliceSession, "/api/messages", 200);
        assertThat(aliceMessages.size()).isEqualTo(1);
        var messageNode = aliceMessages.get(0);
        assertThat(messageNode.size()).isEqualTo(11);
        assertThat(messageNode.path("id").isTextual()).isTrue();
        assertThat(messageNode.path("transitionSeq").asText()).isEqualTo(Long.toString(aliceTransitionSeq));
        assertThat(messageNode.path("type").asText()).isEqualTo("ALLOCATED");
        assertThat(messageNode.path("requestId").asText()).isEqualTo(aliceRequestId);
        assertThat(messageNode.path("allocationId").asText()).isEqualTo(aliceAllocationId);
        assertThat(messageNode.path("gpuId").asText()).isEqualTo("3");
        assertThat(messageNode.path("title").asText()).isEqualTo("GPU 3 使用名额已获配");
        assertThat(messageNode.path("body").asText()).contains(aliceRequestId, "GPU 3", "使用权");
        assertThat(messageNode.path("allocatedAt").asText()).isEqualTo(aliceSubmitTime.toString());
        assertThat(messageNode.path("expiresAt").asText()).isEqualTo(aliceExpiresAt.toString());
        assertThat(messageNode.path("createdAt").asText()).isEqualTo(aliceSubmitTime.toString());
        assertThat(getJson(aliceSession, "/api/messages/" + messageNode.path("id").asText(), 200)).isEqualTo(messageNode);
    }

    @Test
    void doesNotBlockOtherIdleCardsAndAllowsMemberToHoldMultipleCards() throws Exception {
        var carolSession = login("carol");
        var aliceSession = login("alice");
        var bobSession = login("bob");

        submit(carolSession, List.of("2"), "24", 201);
        var aliceWaitingOnGpu2 = submit(aliceSession, List.of("2"), "24", 201);
        assertThat(aliceWaitingOnGpu2.path("queueItems").get(0).path("status").asText()).isEqualTo("WAITING");

        var bobOnGpu3 = submit(bobSession, List.of("3"), "12", 201);
        assertThat(bobOnGpu3.path("queueItems").get(0).path("status").asText()).isEqualTo("ALLOCATED");
        assertThat(bobOnGpu3.path("queueItems").get(0).path("gpuId").asText()).isEqualTo("3");

        var aliceGpu1 = submit(aliceSession, List.of("1"), "6", 201);
        clock.now = BASE_TIME.plus(Duration.ofMinutes(5));
        var aliceGpu4 = submit(aliceSession, List.of("4"), "48", 201);
        assertThat(aliceGpu1.path("queueItems").get(0).path("status").asText()).isEqualTo("ALLOCATED");
        assertThat(aliceGpu4.path("queueItems").get(0).path("status").asText()).isEqualTo("ALLOCATED");

        var aliceAllocations = getJson(aliceSession, "/api/allocations", 200);
        assertThat(aliceAllocations.size()).isEqualTo(2);
        assertThat(aliceAllocations.get(0).path("gpuId").asText()).isEqualTo("4");
        assertThat(aliceAllocations.get(1).path("gpuId").asText()).isEqualTo("1");
    }

    @Test
    void ordersByStrict72HourBoostOriginalSubmissionTimeAndReceiveSeqWithoutSameMemberBias() throws Exception {
        Instant evaluationTime = BASE_TIME.plus(Duration.ofDays(4));
        Instant exact72h = evaluationTime.minus(Duration.ofHours(72));
        Instant strictlyOver72h = exact72h.minusNanos(1);
        Instant recentTied = evaluationTime.minus(Duration.ofHours(5));
        assertThat(orderingPolicy.isBoostedOver72Hours(exact72h, evaluationTime)).isFalse();
        assertThat(orderingPolicy.isBoostedOver72Hours(strictlyOver72h, evaluationTime)).isTrue();
        assertThat(orderingPolicy.selectNext(List.of(), evaluationTime)).isEmpty();

        var cExact72 = new WaitingCandidate(UUID.randomUUID(), UUID.randomUUID(), MEMBER_B, 5, 8, exact72h, 1L);
        var cOver72 = new WaitingCandidate(UUID.randomUUID(), UUID.randomUUID(), MEMBER_A, 5, 8, strictlyOver72h, 99L);
        var cTiedSeq2 = new WaitingCandidate(UUID.randomUUID(), UUID.randomUUID(), MEMBER_B, 5, 8, recentTied, 20L);
        var cTiedSeq1 = new WaitingCandidate(UUID.randomUUID(), UUID.randomUUID(), MEMBER_A, 5, 8, recentTied, 10L);
        assertThat(orderingPolicy.order(List.of(cTiedSeq2, cExact72, cTiedSeq1, cOver72), evaluationTime))
                .containsExactly(cOver72, cExact72, cTiedSeq1, cTiedSeq2);

        var carolSession = login("carol");
        var aliceSession = login("alice");
        var bobSession = login("bob");

        // Occupy GPU 5 so all subsequent requests queue in WAITING state.
        submit(carolSession, List.of("5"), "1", 201);

        // Insert exact72h FIRST (smaller receive_seq) and strictlyOver72h SECOND (larger receive_seq)
        // so strict >72h boost and submitted_at ordering beat lower receive_seq.
        clock.now = exact72h;
        var exact72hReq = submit(bobSession, List.of("5"), "8", 201);
        clock.now = strictlyOver72h;
        var over72hReq = submit(aliceSession, List.of("5"), "8", 201);

        // Tied submission time + intentional repeated same-card submission by Alice:
        Instant tiedTime = evaluationTime.minus(Duration.ofHours(5));
        clock.now = tiedTime;
        var tiedFirstAlice = submit(aliceSession, List.of("5"), "8", 201);
        var tiedSecondBob = submit(bobSession, List.of("5"), "8", 201);
        var tiedThirdAliceRepeat = submit(aliceSession, List.of("5"), "8", 201);

        clock.now = evaluationTime;
        // While GPU 5 is still occupied, repeated triggers do nothing.
        assertThat(allocationService.allocateIfIdle(5)).isEmpty();

        // Simulate controlled slot availability step-by-step at evaluationTime:
        List<String> expectedRequestOrder = List.of(
                over72hReq.path("id").asText(),
                exact72hReq.path("id").asText(),
                tiedFirstAlice.path("id").asText(),
                tiedSecondBob.path("id").asText(),
                tiedThirdAliceRepeat.path("id").asText());

        for (String expectedRequestId : expectedRequestOrder) {
            jdbc.sql("DELETE FROM current_allocations WHERE gpu_id = 5").update();
            var allocated = allocationService.allocateIfIdle(5);
            assertThat(allocated).isPresent();
            assertThat(allocated.get().requestId()).isEqualTo(expectedRequestId);
            assertThat(allocated.get().gpuId()).isEqualTo("5");
            // Repeated trigger while occupied produces no second allocation or message.
            int auditsBeforeRepeat = count("audit_logs");
            int messagesBeforeRepeat = count("inbox_messages");
            assertThat(allocationService.allocateIfIdle(5)).isEmpty();
            assertThat(count("audit_logs")).isEqualTo(auditsBeforeRepeat);
            assertThat(count("inbox_messages")).isEqualTo(messagesBeforeRepeat);
        }

        jdbc.sql("DELETE FROM current_allocations WHERE gpu_id = 5").update();
        assertThat(allocationService.allocateIfIdle(5)).isEmpty();
    }

    @Test
    void computesIndependentAllocationAndExpiryTimesForCardsAllocatedAtDifferentMoments() throws Exception {
        var bobSession = login("bob");
        var aliceSession = login("alice");

        // GPU 2 starts busy; GPU 3 starts idle.
        var bobCreated = submit(bobSession, List.of("2"), "12", 201);
        String bobEndedAllocationId = bobCreated.path("queueItems").get(0).path("allocationId").asText();

        Instant t1 = BASE_TIME.plus(Duration.ofHours(1));
        clock.now = t1;
        var created = submit(aliceSession, List.of("2", "3"), "24", 201);
        String requestId = created.path("id").asText();
        String originalReceiveSeq = created.path("receiveSeq").asText();

        assertThat(created.path("queueItems").get(0).path("status").asText()).isEqualTo("WAITING");
        assertThat(created.path("queueItems").get(1).path("status").asText()).isEqualTo("ALLOCATED");
        assertThat(created.path("queueItems").get(1).path("allocatedAt").asText()).isEqualTo(t1.toString());
        assertThat(created.path("queueItems").get(1).path("expiresAt").asText())
                .isEqualTo(t1.plus(Duration.ofHours(24)).toString());

        // Advance to t2 and release GPU 2's current mapping in controlled test fixture, then run scanner.
        Instant t2 = t1.plus(Duration.ofHours(6)).plus(Duration.ofMinutes(30));
        clock.now = t2;
        jdbc.sql("DELETE FROM current_allocations WHERE gpu_id = 2").update();
        // Ended allocation remaining in allocations table is excluded from active current quotas and returns empty 404.
        assertThat(getJson(bobSession, "/api/allocations", 200).size()).isZero();
        assertThat(mvc.perform(get("/api/allocations/" + bobEndedAllocationId).session(bobSession))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString()).isEmpty();
        allocationScanner.scanIdleGpus();

        var updatedRequest = getJson(aliceSession, "/api/requests/" + requestId, 200);
        assertThat(updatedRequest.path("submittedAt").asText()).isEqualTo(t1.toString());
        assertThat(updatedRequest.path("receiveSeq").asText()).isEqualTo(originalReceiveSeq);

        var gpu2After = updatedRequest.path("queueItems").get(0);
        var gpu3After = updatedRequest.path("queueItems").get(1);
        assertThat(gpu2After.path("status").asText()).isEqualTo("ALLOCATED");
        assertThat(gpu2After.path("allocatedAt").asText()).isEqualTo(t2.toString());
        assertThat(gpu2After.path("expiresAt").asText()).isEqualTo(t2.plus(Duration.ofHours(24)).toString());
        assertThat(gpu3After.path("allocatedAt").asText()).isEqualTo(t1.toString());
        assertThat(gpu3After.path("expiresAt").asText()).isEqualTo(t1.plus(Duration.ofHours(24)).toString());
        assertThat(gpu2After.path("allocationId").asText()).isNotEqualTo(gpu3After.path("allocationId").asText());

        var allocations = getJson(aliceSession, "/api/allocations", 200);
        assertThat(allocations.size()).isEqualTo(2);
        assertThat(allocations.get(0).path("gpuId").asText()).isEqualTo("2");
        assertThat(allocations.get(0).path("allocatedAt").asText()).isEqualTo(t2.toString());
        assertThat(allocations.get(0).path("expiresAt").asText())
                .isEqualTo(t2.plus(Duration.ofHours(24)).toString());
        assertThat(allocations.get(1).path("gpuId").asText()).isEqualTo("3");
        assertThat(allocations.get(1).path("allocatedAt").asText()).isEqualTo(t1.toString());
        assertThat(allocations.get(1).path("expiresAt").asText())
                .isEqualTo(t1.plus(Duration.ofHours(24)).toString());

        var messages = getJson(aliceSession, "/api/messages", 200);
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("gpuId").asText()).isEqualTo("2");
        assertThat(messages.get(1).path("gpuId").asText()).isEqualTo("3");
        assertThat(Long.parseLong(messages.get(0).path("transitionSeq").asText()))
                .isGreaterThan(Long.parseLong(messages.get(1).path("transitionSeq").asText()));
    }

    @Test
    void enforcesMemberIsolation404EmptyBodiesRoleChecksAnd503Failures() throws Exception {
        var aliceSession = login("alice");
        var bobSession = login("bob");
        var adminSession = login("admin");

        submit(aliceSession, List.of("1"), "24", 201);
        submit(bobSession, List.of("2"), "24", 201);

        String aliceAllocationId = getJson(aliceSession, "/api/allocations", 200).get(0).path("id").asText();
        String bobAllocationId = getJson(bobSession, "/api/allocations", 200).get(0).path("id").asText();
        String aliceMessageId = getJson(aliceSession, "/api/messages", 200).get(0).path("id").asText();
        String bobMessageId = getJson(bobSession, "/api/messages", 200).get(0).path("id").asText();

        // Anonymous -> 401
        for (String path : List.of(
                "/api/allocations",
                "/api/allocations/" + aliceAllocationId,
                "/api/messages",
                "/api/messages/" + aliceMessageId)) {
            assertThat(mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
        }

        // Admin -> 403
        for (String path : List.of(
                "/api/allocations",
                "/api/allocations/" + aliceAllocationId,
                "/api/messages",
                "/api/messages/" + aliceMessageId)) {
            mvc.perform(get(path).session(adminSession)).andExpect(status().isForbidden());
        }

        // Unsupported write methods -> 403
        var token = getJson(aliceSession, "/api/csrf", 200);
        mvc.perform(post("/api/allocations").session(aliceSession)
                .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/messages").session(aliceSession)
                .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().isForbidden());

        // Own details -> 200
        assertThat(getJson(aliceSession, "/api/allocations/" + aliceAllocationId, 200).path("id").asText())
                .isEqualTo(aliceAllocationId);
        assertThat(getJson(aliceSession, "/api/messages/" + aliceMessageId, 200).path("id").asText())
                .isEqualTo(aliceMessageId);

        // Foreign, missing, invalid UUID -> empty 404
        for (String notFoundPath : List.of(
                "/api/allocations/" + bobAllocationId,
                "/api/allocations/" + UUID.randomUUID(),
                "/api/allocations/not-a-uuid",
                "/api/messages/" + bobMessageId,
                "/api/messages/" + UUID.randomUUID(),
                "/api/messages/not-a-uuid")) {
            assertThat(mvc.perform(get(notFoundPath).session(aliceSession))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
        }

        // Database failures -> empty 503, followed by clean recovery
        jdbc.sql("ALTER TABLE allocations RENAME TO allocations_unavailable").update();
        try {
            assertThat(mvc.perform(get("/api/allocations").session(aliceSession))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
            assertThat(mvc.perform(get("/api/allocations/" + aliceAllocationId).session(aliceSession))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
        } finally {
            jdbc.sql("ALTER TABLE allocations_unavailable RENAME TO allocations").update();
        }

        jdbc.sql("ALTER TABLE inbox_messages RENAME TO inbox_messages_unavailable").update();
        try {
            assertThat(mvc.perform(get("/api/messages").session(aliceSession))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
            assertThat(mvc.perform(get("/api/messages/" + aliceMessageId).session(aliceSession))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString()).isEmpty();
        } finally {
            jdbc.sql("ALTER TABLE inbox_messages_unavailable RENAME TO inbox_messages").update();
        }

        assertThat(getJson(aliceSession, "/api/allocations", 200).size()).isEqualTo(1);
        assertThat(getJson(aliceSession, "/api/messages", 200).size()).isEqualTo(1);
    }

    @Test
    void rollsBackWholeTransactionWhenAuditOrInboxOrCheckedExceptionFailsAndRecoversCleanly() throws Exception {
        assertThat(AopUtils.isAopProxy(allocationService)).isTrue();
        assertThat(AopUtils.isAopProxy(faultRepository)).isFalse();

        var aliceSession = login("alice");
        var bobSession = login("bob");

        // 1. Audit write failure during multi-card POST /api/requests rolls back request + earlier card allocation.
        jdbc.sql("ALTER TABLE audit_logs ADD CONSTRAINT test_audit_failure CHECK (gpu_id <> 2)").update();
        try {
            assertThat(submitRaw(aliceSession, List.of("1", "2"), "24", 503)).isEmpty();
            assertThat(count("requests")).isZero();
            assertThat(count("queue_items")).isZero();
            assertThat(count("allocations")).isZero();
            assertThat(count("current_allocations")).isZero();
            assertThat(count("audit_logs")).isZero();
            assertThat(count("inbox_messages")).isZero();
        } finally {
            jdbc.sql("ALTER TABLE audit_logs DROP CONSTRAINT test_audit_failure").update();
        }

        // 2. Checked exception (IOException) after SQL writes rolls back POST /api/requests and returns empty 503.
        faultRepository.failInboxWithCheckedException = true;
        assertThat(submitRaw(aliceSession, List.of("1", "2"), "24", 503)).isEmpty();
        assertThat(count("requests")).isZero();
        assertThat(count("queue_items")).isZero();
        assertThat(count("allocations")).isZero();
        assertThat(count("current_allocations")).isZero();
        assertThat(count("audit_logs")).isZero();
        assertThat(count("inbox_messages")).isZero();
        faultRepository.failInboxWithCheckedException = false;

        // 3. Checked exception and inbox constraint failure during single-card scanner / allocateIfIdle:
        // Occupy GPU 3 and GPU 4 first, enqueue Alice on GPU 3 and Bob on GPU 4, then clear current mappings.
        submit(bobSession, List.of("3", "4"), "1", 201);
        var aliceQueuedGpu3 = submit(aliceSession, List.of("3"), "12", 201);
        var bobQueuedGpu4 = submit(bobSession, List.of("4"), "12", 201);
        jdbc.sql("DELETE FROM current_allocations WHERE gpu_id IN (3, 4)").update();

        int allocationsBaseline = count("allocations");
        int auditsBaseline = count("audit_logs");
        int messagesBaseline = count("inbox_messages");

        faultRepository.failInboxWithCheckedException = true;
        assertThatThrownBy(() -> allocationService.allocateIfIdle(3))
                .isInstanceOf(UndeclaredThrowableException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT status FROM queue_items WHERE id = :id")
                .param("id", UUID.fromString(aliceQueuedGpu3.path("queueItems").get(0).path("id").asText()))
                .query(String.class).single()).isEqualTo("WAITING");
        assertThat(count("current_allocations")).isZero();
        assertThat(count("allocations")).isEqualTo(allocationsBaseline);
        assertThat(count("audit_logs")).isEqualTo(auditsBaseline);
        assertThat(count("inbox_messages")).isEqualTo(messagesBaseline);
        faultRepository.failInboxWithCheckedException = false;

        // Isolate per-card scanner failure: GPU 3 fails inbox write and rolls back, while GPU 4 allocates cleanly!
        jdbc.sql("ALTER TABLE inbox_messages ADD CONSTRAINT test_inbox_failure CHECK (NOT (gpu_id = 3 AND recipient_id = '"
                + MEMBER_A + "'))").update();
        try {
            allocationScanner.scanIdleGpus();
            assertThat(jdbc.sql("SELECT status FROM queue_items WHERE id = :id")
                    .param("id", UUID.fromString(aliceQueuedGpu3.path("queueItems").get(0).path("id").asText()))
                    .query(String.class).single()).isEqualTo("WAITING");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 3")
                    .query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT status FROM queue_items WHERE id = :id")
                    .param("id", UUID.fromString(bobQueuedGpu4.path("queueItems").get(0).path("id").asText()))
                    .query(String.class).single()).isEqualTo("ALLOCATED");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 4")
                    .query(Integer.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("ALTER TABLE inbox_messages DROP CONSTRAINT test_inbox_failure").update();
        }

        // Subsequent scan recovers GPU 3 cleanly once without duplicating GPU 4.
        allocationScanner.scanIdleGpus();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 3")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 4")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(count("allocations")).isEqualTo(allocationsBaseline + 2);
        assertThat(count("audit_logs")).isEqualTo(auditsBaseline + 2);
        assertThat(count("inbox_messages")).isEqualTo(messagesBaseline + 2);
    }

    @Test
    void databaseConstraintsEnforceSameCardCompositeForeignKeysAndUniqueCurrentMapping() throws Exception {
        var aliceSession = login("alice");
        var bobSession = login("bob");
        var created = submit(aliceSession, List.of("1"), "24", 201);
        UUID queueItemGpu1 = UUID.fromString(created.path("queueItems").get(0).path("id").asText());
        UUID allocationGpu1 = UUID.fromString(created.path("queueItems").get(0).path("allocationId").asText());

        // Occupy GPU 2 first so Bob's second request on GPU 2 remains WAITING.
        submit(bobSession, List.of("2"), "24", 201);
        var waitingOnGpu2 = submit(aliceSession, List.of("2"), "24", 201);
        UUID waitingQueueItemGpu2 = UUID.fromString(waitingOnGpu2.path("queueItems").get(0).path("id").asText());

        // queue_items status check
        assertThatThrownBy(() -> jdbc.sql("UPDATE queue_items SET status = 'INVALID' WHERE id = :id")
                .param("id", waitingQueueItemGpu2).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // allocations cross-card FK mismatch (queue_item is for GPU 2, insert tries GPU 3)
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at)
                VALUES (:id, :queueItemId, 3, :allocatedAt, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("queueItemId", waitingQueueItemGpu2)
                .param("allocatedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("expiresAt", BASE_TIME.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        // allocations duplicate queue_item_id
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at)
                VALUES (:id, :queueItemId, 1, :allocatedAt, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("queueItemId", queueItemGpu1)
                .param("allocatedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("expiresAt", BASE_TIME.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        // allocations expires_at > allocated_at check
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at)
                VALUES (:id, :queueItemId, 2, :allocatedAt, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("queueItemId", waitingQueueItemGpu2)
                .param("allocatedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("expiresAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        // current_allocations cross-card FK mismatch (allocation is for GPU 1, mapping tries GPU 3)
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO current_allocations (gpu_id, allocation_id) VALUES (3, :allocationId)
                """).param("allocationId", allocationGpu1).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // current_allocations dangling allocation_id
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO current_allocations (gpu_id, allocation_id) VALUES (3, :allocationId)
                """).param("allocationId", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // Create a valid historical allocation row on GPU 2 (after clearing current_allocations on GPU 1? No, test duplicate gpu_id on GPU 1!)
        var waitingOnGpu1 = submit(bobSession, List.of("1"), "12", 201);
        UUID waitingQueueItemGpu1 = UUID.fromString(waitingOnGpu1.path("queueItems").get(0).path("id").asText());
        UUID secondAllocationGpu1 = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at)
                VALUES (:id, :queueItemId, 1, :allocatedAt, :expiresAt)
                """)
                .param("id", secondAllocationGpu1)
                .param("queueItemId", waitingQueueItemGpu1)
                .param("allocatedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("expiresAt", BASE_TIME.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC))
                .update();

        // current_allocations primary key gpu_id prevents second active mapping on GPU 1
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO current_allocations (gpu_id, allocation_id) VALUES (1, :allocationId)
                """).param("allocationId", secondAllocationGpu1).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // Insert a valid audit_logs row for secondAllocationGpu1 to obtain a distinct unused transition_seq.
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO audit_logs (
                    occurred_at, actor_type, actor_account_id, action,
                    request_id, queue_item_id, allocation_id, gpu_id,
                    reason, before_state, after_state
                ) VALUES (
                    :occurredAt, 'SYSTEM', NULL, 'ALLOCATED',
                    :requestId, :queueItemId, :allocationId, 1,
                    NULL, 'before', 'after'
                )
                """)
                .param("occurredAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("requestId", UUID.fromString(waitingOnGpu1.path("id").asText()))
                .param("queueItemId", waitingQueueItemGpu1)
                .param("allocationId", secondAllocationGpu1)
                .update(keyHolder, "TRANSITION_SEQ");
        long unusedTransitionSeq = keyHolder.getKey().longValue();
        long existingTransitionSeq = jdbc.sql("SELECT MIN(transition_seq) FROM audit_logs").query(Long.class).single();

        // inbox_messages_transition_recipient_uk: already-used (transition_seq, recipient_id) + unmessaged secondAllocationGpu1
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, :allocationId, :requestId, 1,
                    'ALLOCATED', 'dup-seq', 'dup-seq', :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("recipientId", MEMBER_A)
                .param("transitionSeq", existingTransitionSeq)
                .param("allocationId", secondAllocationGpu1)
                .param("requestId", UUID.fromString(waitingOnGpu1.path("id").asText()))
                .param("createdAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        // inbox_messages_allocation_gpu_fk: unused transition_seq + secondAllocationGpu1 (GPU 1) + mismatched gpu_id = 2
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO inbox_messages (
                    id, recipient_id, transition_seq, allocation_id, request_id, gpu_id,
                    message_type, title, body, created_at
                ) VALUES (
                    :id, :recipientId, :transitionSeq, :allocationId, :requestId, 2,
                    'ALLOCATED', 'wrong-gpu', 'wrong-gpu', :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("recipientId", MEMBER_B)
                .param("transitionSeq", unusedTransitionSeq)
                .param("allocationId", secondAllocationGpu1)
                .param("requestId", UUID.fromString(waitingOnGpu1.path("id").asText()))
                .param("createdAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradesFromV3SkipsNonWebScanAndRecoversWaitingItemsOnStartupAndScheduledScan() throws Exception {
        Path dataDir = Files.createTempDirectory("gpu-queue-allocations-v3-upgrade-restart-");
        String url = "jdbc:h2:file:" + dataDir.resolve("gpu-queue").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();

        var externalJdbc = JdbcClient.create(new DriverManagerDataSource(url, "sa", ""));
        String hash = passwords.encode("upgrade-password");
        seedAccount(externalJdbc, MEMBER_A, "alice", "MEMBER", hash);
        seedAccount(externalJdbc, MEMBER_B, "bob", "MEMBER", hash);

        UUID reqFirst = UUID.randomUUID();
        UUID reqSecond = UUID.randomUUID();
        UUID itemFirstGpu1 = UUID.randomUUID();
        UUID itemFirstGpu2 = UUID.randomUUID();
        UUID itemSecondGpu1 = UUID.randomUUID();

        externalJdbc.sql("""
                INSERT INTO requests (id, member_id, submitted_at, duration_hours)
                VALUES (:id, :memberId, :submittedAt, 12)
                """)
                .param("id", reqFirst)
                .param("memberId", MEMBER_A)
                .param("submittedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update();
        externalJdbc.sql("""
                INSERT INTO queue_items (id, request_id, gpu_id, status)
                VALUES (:id1, :requestId, 1, 'WAITING'), (:id2, :requestId, 2, 'WAITING')
                """)
                .param("id1", itemFirstGpu1)
                .param("id2", itemFirstGpu2)
                .param("requestId", reqFirst)
                .update();

        externalJdbc.sql("""
                INSERT INTO requests (id, member_id, submitted_at, duration_hours)
                VALUES (:id, :memberId, :submittedAt, 24)
                """)
                .param("id", reqSecond)
                .param("memberId", MEMBER_B)
                .param("submittedAt", BASE_TIME.plusSeconds(30).atOffset(ZoneOffset.UTC))
                .update();
        externalJdbc.sql("""
                INSERT INTO queue_items (id, request_id, gpu_id, status)
                VALUES (:id, :requestId, 1, 'WAITING')
                """)
                .param("id", itemSecondGpu1)
                .param("requestId", reqSecond)
                .update();

        // Migrate V3 -> V4 and verify existing waiting records are preserved.
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(externalJdbc.sql("SELECT COUNT(*) FROM queue_items WHERE status = 'WAITING'")
                .query(Integer.class).single()).isEqualTo(3);

        // GpuQueueApplication init-admin mode (WebApplicationType.NONE + scanner disabled) must NOT scan.
        try (var initAdminContext = GpuQueueApplication.startMode("init-admin", dataDir)) {
            assertThat(initAdminContext.getBeansOfType(AllocationScanner.class)).isEmpty();
            assertThat(externalJdbc.sql("SELECT COUNT(*) FROM allocations").query(Integer.class).single()).isZero();
        }

        // SERVLET mode with queue.scanner.enabled=false must also NOT register AllocationScanner or scan.
        try (var servletScannerDisabled = GpuQueueApplication.startMode(
                "serve", dataDir, "--server.port=0", "--queue.scanner.enabled=false")) {
            assertThat(servletScannerDisabled.getBeansOfType(AllocationScanner.class)).isEmpty();
            assertThat(servletScannerDisabled.getBeansOfType(SchedulingConfiguration.class)).isEmpty();
            assertThat(externalJdbc.sql("SELECT COUNT(*) FROM allocations").query(Integer.class).single()).isZero();
        }

        // Start GpuQueueApplication serve mode: ApplicationReadyEvent startup catch-up allocates legal head on GPU 1 & GPU 2.
        try (var firstServe = GpuQueueApplication.startMode(
                "serve", dataDir, "--server.port=0", "--queue.scanner.initial-delay-ms=86400000")) {
            var service = firstServe.getBean(AllocationService.class);
            assertThat(service.listOwnAllocations(MEMBER_A)).hasSize(2);
            assertThat(service.listOwnAllocations(MEMBER_B)).isEmpty();
            assertThat(service.listOwnMessages(MEMBER_A)).hasSize(2);
            assertThat(externalJdbc.sql("SELECT status FROM queue_items WHERE id = :id")
                    .param("id", itemSecondGpu1).query(String.class).single()).isEqualTo("WAITING");
        }

        // Restart GpuQueueApplication serve mode + verify repeated startup catch-up produces no duplicates,
        // and verify @Scheduled periodic scan picks up a newly inserted waiting item on idle GPU 6.
        try (var secondServe = GpuQueueApplication.startMode(
                "serve",
                dataDir,
                "--server.port=0",
                "--queue.scanner.initial-delay-ms=50",
                "--queue.scanner.fixed-delay-ms=50")) {
            assertThat(externalJdbc.sql("SELECT COUNT(*) FROM allocations").query(Integer.class).single()).isEqualTo(2);
            assertThat(externalJdbc.sql("SELECT COUNT(*) FROM audit_logs").query(Integer.class).single()).isEqualTo(2);
            assertThat(externalJdbc.sql("SELECT COUNT(*) FROM inbox_messages").query(Integer.class).single()).isEqualTo(2);

            UUID scheduledReq = UUID.randomUUID();
            UUID scheduledItem = UUID.randomUUID();
            externalJdbc.sql("""
                    INSERT INTO requests (id, member_id, submitted_at, duration_hours)
                    VALUES (:id, :memberId, :submittedAt, 6)
                    """)
                    .param("id", scheduledReq)
                    .param("memberId", MEMBER_B)
                    .param("submittedAt", BASE_TIME.plusSeconds(120).atOffset(ZoneOffset.UTC))
                    .update();
            externalJdbc.sql("""
                    INSERT INTO queue_items (id, request_id, gpu_id, status)
                    VALUES (:id, :requestId, 6, 'WAITING')
                    """)
                    .param("id", scheduledItem)
                    .param("requestId", scheduledReq)
                    .update();

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                Integer allocatedOnGpu6 = externalJdbc.sql(
                        "SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 6")
                        .query(Integer.class).single();
                if (allocatedOnGpu6 == 1) {
                    break;
                }
                Thread.sleep(25);
            }
            var service = secondServe.getBean(AllocationService.class);
            assertThat(service.listOwnAllocations(MEMBER_B)).hasSize(1);
            assertThat(service.listOwnAllocations(MEMBER_B).get(0).gpuId()).isEqualTo("6");
            assertThat(service.listOwnMessages(MEMBER_B)).hasSize(1);
        }
    }

    private static String newDatabase(String prefix) {
        try {
            Path path = Files.createTempDirectory("gpu-queue-" + prefix).resolve("gpu-queue");
            return "jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/');
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void seedAccount(JdbcClient client, UUID id, String loginName, String role, String hash) {
        client.sql("""
                INSERT INTO accounts (id, login_name, display_name, password_hash, role)
                VALUES (:id, :loginName, :displayName, :hash, :role)
                """)
                .param("id", id)
                .param("loginName", loginName)
                .param("displayName", loginName + "测试成员")
                .param("hash", hash)
                .param("role", role)
                .update();
    }

    private MockHttpSession login(String username) throws Exception {
        var csrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var token = json.readTree(csrfResult.getResponse().getContentAsString());
        var result = mvc.perform(post("/api/login").session(session)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("username", username)
                .param("password", "integration-password"))
                .andExpect(status().isNoContent())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String submitRaw(MockHttpSession session, List<String> gpuIds, String hours, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        var request = post("/api/requests")
                .session(session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("durationHours", hours)
                .param("gpuIds", gpuIds.toArray(String[]::new));
        return mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode submit(MockHttpSession session, List<String> gpuIds, String hours, int expectedStatus) throws Exception {
        String body = submitRaw(session, gpuIds, hours, expectedStatus);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode getJson(MockHttpSession session, String path, int expectedStatus) throws Exception {
        String body = mvc.perform(get(path).session(session))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FaultToggleAllocationRepository faultToggleAllocationRepository(JdbcClient jdbc) {
            return new FaultToggleAllocationRepository(jdbc);
        }
    }

    static class MutableClock extends Clock {
        Instant now = BASE_TIME;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static class FaultToggleAllocationRepository extends AllocationRepository {
        volatile boolean failInboxWithCheckedException;

        FaultToggleAllocationRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        void insertInboxMessage(UUID messageId, UUID recipientId, long transitionSeq, UUID allocationId,
                                UUID requestId, int gpuId, String title, String body, Instant createdAt) {
            if (failInboxWithCheckedException) {
                sneakyThrow(new IOException("Simulated checked exception during inbox persistence"));
            }
            super.insertInboxMessage(messageId, recipientId, transitionSeq, allocationId,
                    requestId, gpuId, title, body, createdAt);
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
            throw (E) throwable;
        }
    }
}
