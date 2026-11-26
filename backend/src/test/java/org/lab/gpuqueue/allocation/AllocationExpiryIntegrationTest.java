package org.lab.gpuqueue.allocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
@Import(AllocationExpiryIntegrationTest.ClockConfig.class)
class AllocationExpiryIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T03:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("allocation-expiry-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AllocationService allocationService;
    @Autowired AllocationScanner allocationScanner;
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
    }

    @Test
    void autoReleasesShortLivedUnstartedAllocationAtExpiryAndHandsOverToSuccessor() throws Exception {
        var alice = login("alice");
        var bob = login("bob");

        // 6h (< 24h) unstarted on GPU 1, Bob waiting behind Alice.
        var aliceReq = submit(alice, List.of("1"), "6", 201);
        UUID a101 = UUID.fromString(aliceReq.path("queueItems").get(0).path("allocationId").asText());
        submit(bob, List.of("1"), "12", 201);

        // Before expiry (5h 59m): scan does nothing.
        clock.now = BASE_TIME.plus(Duration.ofHours(6)).minusSeconds(1);
        allocationScanner.scanIdleGpus();
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 1").query(UUID.class).single())
                .isEqualTo(a101);

        // At exact expiry (6h): auto-releases A101, notifies Alice, allocates Bob (A102).
        clock.now = BASE_TIME.plus(Duration.ofHours(6));
        allocationScanner.scanIdleGpus();

        var bobAllocations = getJson(bob, "/api/allocations", 200);
        assertThat(bobAllocations.size()).isEqualTo(1);
        UUID a102 = UUID.fromString(bobAllocations.get(0).path("id").asText());
        assertThat(a102).isNotEqualTo(a101);
        assertThat(getJson(alice, "/api/allocations", 200).size()).isZero();

        var aliceMessages = getJson(alice, "/api/messages", 200);
        assertThat(aliceMessages.size()).isEqualTo(2);
        assertThat(aliceMessages.get(0).path("type").asText()).isEqualTo("UNSTARTED_EXPIRED");

        // Stale expiry check on A101 and repeated scan do NOT affect A102 or duplicate audits/messages.
        int auditsBefore = count("audit_logs");
        int messagesBefore = count("inbox_messages");
        assertThat(allocationService.processExpiryForAllocation(a101)).isFalse();
        allocationScanner.scanIdleGpus();
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 1").query(UUID.class).single())
                .isEqualTo(a102);
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);
    }

    @Test
    void marksAnomalyAndRetainsMappingAt24HoursForExact24hAndLongerUnstartedAllocations() throws Exception {
        var alice = login("alice");
        var bob = login("bob");

        // Exact 24h on GPU 2 and 48h on GPU 3, Bob waiting on both.
        var req24 = submit(alice, List.of("2"), "24", 201);
        var req48 = submit(alice, List.of("3"), "48", 201);
        String alloc24 = req24.path("queueItems").get(0).path("allocationId").asText();
        String alloc48 = req48.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("2", "3"), "12", 201);

        // At 24h: BOTH are marked ANOMALY and retain current_allocations (Bob does NOT get allocated).
        Instant at24h = BASE_TIME.plus(Duration.ofHours(24));
        clock.now = at24h;
        allocationScanner.scanIdleGpus();

        assertThat(getJson(bob, "/api/allocations", 200).size()).isZero();
        var detail24 = getJson(alice, "/api/allocations/" + alloc24, 200);
        var detail48 = getJson(alice, "/api/allocations/" + alloc48, 200);
        assertThat(detail24.path("status").asText()).isEqualTo("ANOMALY");
        assertThat(detail24.path("notStartedAnomalyAt").asText()).isEqualTo(at24h.toString());
        assertThat(detail48.path("status").asText()).isEqualTo("ANOMALY");
        assertThat(detail48.path("notStartedAnomalyAt").asText()).isEqualTo(at24h.toString());

        // Repeat scan at 25h produces no duplicate ANOMALY audits or messages.
        int auditsBefore = count("audit_logs");
        int messagesBefore = count("inbox_messages");
        clock.now = BASE_TIME.plus(Duration.ofHours(25));
        allocationScanner.scanIdleGpus();
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);

        // Confirming start on GPU 3 preserves historical not_started_anomaly_at.
        postAction(alice, "/api/allocations/" + alloc48 + "/start", 200);
        var afterStart = getJson(alice, "/api/allocations/" + alloc48, 200);
        assertThat(afterStart.path("status").asText()).isEqualTo("IN_USE");
        assertThat(afterStart.path("notStartedAnomalyAt").asText()).isEqualTo(at24h.toString());
    }

    @Test
    void marksOverdueAndRetainsMappingForStartedAllocationAtExpiryAndRollsBackOnWriteFailure() throws Exception {
        var alice = login("alice");
        var bob = login("bob");

        // Short 6h allocation started BEFORE expiry -> at 6h becomes OVERDUE and retains mapping!
        var shortStartedReq = submit(alice, List.of("4"), "6", 201);
        String shortStartedId = shortStartedReq.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("4"), "6", 201);
        clock.now = BASE_TIME.plus(Duration.ofHours(1));
        postAction(alice, "/api/allocations/" + shortStartedId + "/start", 200);

        clock.now = BASE_TIME.plus(Duration.ofHours(6));
        allocationScanner.scanIdleGpus();
        assertThat(getJson(bob, "/api/allocations", 200).size()).isZero();
        var overdueDetail = getJson(alice, "/api/allocations/" + shortStartedId, 200);
        assertThat(overdueDetail.path("status").asText()).isEqualTo("OVERDUE");
        assertThat(getJson(alice, "/api/messages", 200).get(0).path("type").asText()).isEqualTo("OVERDUE");

        // Repeat scan does not duplicate OVERDUE notification.
        int messagesBefore = count("inbox_messages");
        allocationScanner.scanIdleGpus();
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);

        // Rollback test on short unstarted auto-release when inbox_messages insert fails.
        clock.now = BASE_TIME.plus(Duration.ofHours(10));
        var failingReq = submit(alice, List.of("5"), "2", 201);
        String failingAllocId = failingReq.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("5"), "2", 201);

        clock.now = BASE_TIME.plus(Duration.ofHours(12));
        jdbc.sql("ALTER TABLE inbox_messages ADD CONSTRAINT test_exp_msg_fail CHECK (message_type <> 'UNSTARTED_EXPIRED')")
                .update();
        try {
            allocationScanner.scanIdleGpus();
            assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 5").query(UUID.class).single())
                    .isEqualTo(UUID.fromString(failingAllocId));
            assertThat(getJson(bob, "/api/allocations", 200).size()).isZero();
        } finally {
            jdbc.sql("ALTER TABLE inbox_messages DROP CONSTRAINT test_exp_msg_fail").update();
        }

        allocationScanner.scanIdleGpus();
        assertThat(getJson(bob, "/api/allocations", 200).size()).isEqualTo(1);
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

    private JsonNode postAction(MockHttpSession session, String path, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post(path)
                        .session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
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
