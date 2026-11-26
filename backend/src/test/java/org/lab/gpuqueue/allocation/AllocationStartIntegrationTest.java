package org.lab.gpuqueue.allocation;

import java.io.IOException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AllocationStartIntegrationTest.ClockConfig.class)
class AllocationStartIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T01:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("allocation-start-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AllocationService allocationService;
    @Autowired MutableClock clock;
    private String passwordHash;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        Flyway.configure().dataSource(JDBC_URL, "sa", "").load().migrate();
        properties.add("queue.jdbc-url", () -> JDBC_URL + ";IFEXISTS=TRUE");
        properties.add("queue.scanner.initial-delay-ms", () -> "86400000");
    }

    @BeforeEach
    void reset() {
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
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void confirmsStartPerCardWithoutResettingExpiryOrAffectingOtherCards() throws Exception {
        var bob = login("bob");
        var alice = login("alice");

        // Occupy GPU 4 so Alice's request for [2, 3, 4] gets GPU 2 & 3 ALLOCATED and GPU 4 WAITING.
        submit(bob, List.of("4"), "12", 201);
        var created = submit(alice, List.of("2", "3", "4"), "24", 201);
        String requestId = created.path("id").asText();
        String gpu2AllocationId = created.path("queueItems").get(0).path("allocationId").asText();
        String gpu3AllocationId = created.path("queueItems").get(1).path("allocationId").asText();
        String waitingGpu4QueueItemId = created.path("queueItems").get(2).path("id").asText();
        Instant expectedExpiry = BASE_TIME.plus(Duration.ofHours(24));

        // Waiting queue item ID cannot be started.
        assertThat(startRaw(alice, waitingGpu4QueueItemId, 404)).isEmpty();

        // Advance clock by 2 hours and confirm start on GPU 2 ONLY.
        Instant startTime = BASE_TIME.plus(Duration.ofHours(2));
        clock.now = startTime;
        int messagesBefore = count("inbox_messages");
        var started = start(alice, gpu2AllocationId, 200);

        assertThat(started.path("alreadyStarted").asBoolean()).isFalse();
        var allocation = started.path("allocation");
        assertThat(allocation.path("id").asText()).isEqualTo(gpu2AllocationId);
        assertThat(allocation.path("gpuId").asText()).isEqualTo("2");
        assertThat(allocation.path("status").asText()).isEqualTo("IN_USE");
        assertThat(allocation.path("allocatedAt").asText()).isEqualTo(BASE_TIME.toString());
        assertThat(allocation.path("expiresAt").asText()).isEqualTo(expectedExpiry.toString());
        assertThat(allocation.path("startedAt").asText()).isEqualTo(startTime.toString());
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);

        // Verify persisted STARTED audit record.
        jdbc.sql("""
                SELECT occurred_at, actor_type, actor_account_id, action, request_id, allocation_id, gpu_id, before_state, after_state
                FROM audit_logs
                WHERE allocation_id = :allocationId AND action = 'STARTED'
                """)
                .param("allocationId", UUID.fromString(gpu2AllocationId))
                .query((row, idx) -> {
                    assertThat(row.getObject("occurred_at", OffsetDateTime.class).toInstant()).isEqualTo(startTime);
                    assertThat(row.getString("actor_type")).isEqualTo("MEMBER");
                    assertThat(row.getObject("actor_account_id", UUID.class)).isEqualTo(MEMBER_A);
                    assertThat(row.getString("action")).isEqualTo("STARTED");
                    assertThat(row.getObject("request_id", UUID.class)).isEqualTo(UUID.fromString(requestId));
                    assertThat(row.getInt("gpu_id")).isEqualTo(2);
                    assertThat(row.getString("before_state")).isEqualTo("allocationStatus=UNSTARTED");
                    assertThat(row.getString("after_state")).isEqualTo("allocationStatus=IN_USE;startedAt=" + startTime);
                    return 1;
                }).single();

        // GPU 3 remains UNSTARTED and GPU 4 remains WAITING in both /api/allocations and /api/requests.
        var gpu3Detail = getJson(alice, "/api/allocations/" + gpu3AllocationId, 200);
        assertThat(gpu3Detail.path("status").asText()).isEqualTo("UNSTARTED");
        assertThat(gpu3Detail.has("startedAt")).isFalse();
        assertThat(gpu3Detail.path("expiresAt").asText()).isEqualTo(expectedExpiry.toString());

        var requestDetail = getJson(alice, "/api/requests/" + requestId, 200);
        assertThat(requestDetail.path("queueItems").get(0).path("allocationStatus").asText()).isEqualTo("IN_USE");
        assertThat(requestDetail.path("queueItems").get(0).path("startedAt").asText()).isEqualTo(startTime.toString());
        assertThat(requestDetail.path("queueItems").get(1).path("allocationStatus").asText()).isEqualTo("UNSTARTED");
        assertThat(requestDetail.path("queueItems").get(1).has("startedAt")).isFalse();
        assertThat(requestDetail.path("queueItems").get(2).path("status").asText()).isEqualTo("WAITING");

        // Repeating start later preserves first startedAt and creates zero extra audits/messages.
        int auditsBeforeRepeat = count("audit_logs");
        clock.now = startTime.plus(Duration.ofHours(1));
        var repeated = start(alice, gpu2AllocationId, 200);
        assertThat(repeated.path("alreadyStarted").asBoolean()).isTrue();
        assertThat(repeated.path("allocation").path("startedAt").asText()).isEqualTo(startTime.toString());
        assertThat(repeated.path("allocation").path("expiresAt").asText()).isEqualTo(expectedExpiry.toString());
        assertThat(count("audit_logs")).isEqualTo(auditsBeforeRepeat);
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);
    }

    @Test
    void isolatesEndedOldAllocationEvenWhenSameMemberHoldsSuccessorAllocationOnSameGpu() throws Exception {
        var alice = login("alice");

        // Round 1 (A101) allocated to Alice on GPU 1; Alice also submits a second waiting request on GPU 1.
        var round1 = submit(alice, List.of("1"), "12", 201);
        String a101 = round1.path("queueItems").get(0).path("allocationId").asText();
        submit(alice, List.of("1"), "24", 201);

        // Controlled fixture: end A101 by removing current mapping, then allocate Round 2 (A102) to the SAME member Alice.
        Instant t2 = BASE_TIME.plus(Duration.ofHours(3));
        clock.now = t2;
        jdbc.sql("DELETE FROM current_allocations WHERE gpu_id = 1").update();
        var a102View = allocationService.allocateIfIdle(1).orElseThrow();
        String a102 = a102View.id();
        assertThat(a102).isNotEqualTo(a101);

        int auditsBeforeStaleStart = count("audit_logs");
        clock.now = t2.plus(Duration.ofMinutes(30));
        var conflict = start(alice, a101, 409);
        assertThat(conflict.path("code").asText()).isEqualTo("ALLOCATION_ENDED");

        // A101 is not revived, A102 remains UNSTARTED with unchanged expiry, and no new audit is written.
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 1")
                .query(UUID.class).single()).isEqualTo(UUID.fromString(a102));
        assertThat(jdbc.sql("SELECT started_at FROM allocations WHERE id = :id")
                .param("id", UUID.fromString(a101)).query(OffsetDateTime.class).optional()).isEmpty();
        var a102After = getJson(alice, "/api/allocations/" + a102, 200);
        assertThat(a102After.path("status").asText()).isEqualTo("UNSTARTED");
        assertThat(a102After.has("startedAt")).isFalse();
        assertThat(a102After.path("expiresAt").asText()).isEqualTo(t2.plus(Duration.ofHours(24)).toString());
        assertThat(count("audit_logs")).isEqualTo(auditsBeforeStaleStart);
    }

    @Test
    void enforcesAuthCsrfRoleOwnershipAndRollsBackOnAuditFailure() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        var aliceReq = submit(alice, List.of("5"), "24", 201);
        String aliceAllocationId = aliceReq.path("queueItems").get(0).path("allocationId").asText();

        // Anonymous without CSRF -> 403; anonymous with CSRF -> 401.
        mvc.perform(post("/api/allocations/" + aliceAllocationId + "/start")).andExpect(status().isForbidden());
        var anonCsrfRes = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var anonSession = (MockHttpSession) anonCsrfRes.getRequest().getSession(false);
        var anonToken = json.readTree(anonCsrfRes.getResponse().getContentAsString());
        mvc.perform(post("/api/allocations/" + aliceAllocationId + "/start")
                .session(anonSession)
                .header(anonToken.path("headerName").asText(), anonToken.path("token").asText()))
                .andExpect(status().isUnauthorized());

        // Member missing/invalid CSRF -> 403.
        mvc.perform(post("/api/allocations/" + aliceAllocationId + "/start").session(alice))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/allocations/" + aliceAllocationId + "/start").session(alice)
                .header("X-CSRF-TOKEN", "invalid"))
                .andExpect(status().isForbidden());

        // Admin -> 403.
        startRaw(admin, aliceAllocationId, 403);

        // Other member, missing UUID, invalid UUID -> empty 404.
        assertThat(startRaw(bob, aliceAllocationId, 404)).isEmpty();
        assertThat(startRaw(alice, UUID.randomUUID().toString(), 404)).isEmpty();
        assertThat(startRaw(alice, "not-a-uuid", 404)).isEmpty();

        // Audit write failure rolls back started_at and returns empty 503; retry succeeds cleanly once.
        jdbc.sql("ALTER TABLE audit_logs ADD CONSTRAINT test_start_audit_fail CHECK (action <> 'STARTED')").update();
        try {
            assertThat(startRaw(alice, aliceAllocationId, 503)).isEmpty();
            assertThat(jdbc.sql("SELECT started_at FROM allocations WHERE id = :id")
                    .param("id", UUID.fromString(aliceAllocationId)).query(OffsetDateTime.class).optional()).isEmpty();
        } finally {
            jdbc.sql("ALTER TABLE audit_logs DROP CONSTRAINT test_start_audit_fail").update();
        }

        var recovered = start(alice, aliceAllocationId, 200);
        assertThat(recovered.path("alreadyStarted").asBoolean()).isFalse();
        assertThat(recovered.path("allocation").path("status").asText()).isEqualTo("IN_USE");
    }

    @Test
    void upgradesFromV4PreservingExistingAllocationsAndAllowsConfirmingStart() throws Exception {
        Path dataDir = Files.createTempDirectory("gpu-queue-v4-to-v5-start-");
        String url = "jdbc:h2:file:" + dataDir.resolve("gpu-queue").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();

        var externalJdbc = JdbcClient.create(new DriverManagerDataSource(url, "sa", ""));
        seedAccount(externalJdbc, MEMBER_A, "alice", "MEMBER", passwords.encode("v4-password"));

        UUID reqId = UUID.randomUUID();
        UUID qiId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        externalJdbc.sql("INSERT INTO requests (id, member_id, submitted_at, duration_hours) VALUES (:id, :m, :t, 12)")
                .param("id", reqId).param("m", MEMBER_A).param("t", BASE_TIME.atOffset(ZoneOffset.UTC)).update();
        externalJdbc.sql("INSERT INTO queue_items (id, request_id, gpu_id, status) VALUES (:id, :r, 7, 'ALLOCATED')")
                .param("id", qiId).param("r", reqId).update();
        externalJdbc.sql("INSERT INTO allocations (id, queue_item_id, gpu_id, allocated_at, expires_at) VALUES (:id, :q, 7, :a, :e)")
                .param("id", allocId).param("q", qiId)
                .param("a", BASE_TIME.atOffset(ZoneOffset.UTC))
                .param("e", BASE_TIME.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC)).update();
        externalJdbc.sql("INSERT INTO current_allocations (gpu_id, allocation_id) VALUES (7, :a)")
                .param("a", allocId).update();

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (var ctx = GpuQueueApplication.startMode(
                "serve", dataDir, "--server.port=0", "--queue.scanner.enabled=false")) {
            var service = ctx.getBean(AllocationService.class);
            var before = service.findOwnAllocation(MEMBER_A, allocId).orElseThrow();
            assertThat(before.status()).isEqualTo("UNSTARTED");
            assertThat(before.startedAt()).isNull();

            var result = service.confirmStart(MEMBER_A, allocId).orElseThrow();
            assertThat(result.alreadyStarted()).isFalse();
            assertThat(result.allocation().status()).isEqualTo("IN_USE");
            assertThat(result.allocation().startedAt()).isNotNull();
            assertThat(result.allocation().expiresAt()).isEqualTo(BASE_TIME.plus(Duration.ofHours(12)));
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

    private JsonNode submit(MockHttpSession session, List<String> gpuIds, String hours, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post("/api/requests")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("durationHours", hours)
                        .param("gpuIds", gpuIds.toArray(String[]::new)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private String startRaw(MockHttpSession session, String allocationId, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        return mvc.perform(post("/api/allocations/" + allocationId + "/start")
                        .session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode start(MockHttpSession session, String allocationId, int expectedStatus) throws Exception {
        String body = startRaw(session, allocationId, expectedStatus);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode getJson(MockHttpSession session, String path, int expectedStatus) throws Exception {
        String body = mvc.perform(get(path).session(session))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        Instant now = BASE_TIME;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
