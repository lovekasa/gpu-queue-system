package org.lab.gpuqueue.allocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
@Import(RenewalIntegrationTest.ClockConfig.class)
class RenewalIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-27T03:00:00.000000000Z");
    private static final String JDBC_URL = newDatabase("renewal-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired AllocationScanner scanner;
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
        jdbc.sql("DELETE FROM renewal_requests").update();
        jdbc.sql("DELETE FROM queue_reorders").update();
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
    void renewalUncappedOver72hOverdueRecoverySecondOverdueAndIdempotency() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // Alice gets GPU 1 for 6h and starts it.
        var req = submit(alice, List.of("1"), "6", 201);
        String allocId = req.path("queueItems").get(0).path("allocationId").asText();
        postEmpty(alice, "/api/allocations/" + allocId + "/start", 200);

        // Advance to +6h -> OVERDUE.
        clock.now = BASE_TIME.plus(Duration.ofHours(6));
        scanner.scanIdleGpus();
        assertThat(getJson(alice, "/api/allocations/" + allocId, 200).path("status").asText()).isEqualTo("OVERDUE");

        // Alice submits renewal to +120h (> 72h, uncapped!).
        Instant newExp = BASE_TIME.plus(Duration.ofHours(120));
        var renewal = submitRenewal(alice, allocId, newExp.toString(), 201);
        String renewalId = renewal.path("id").asText();
        assertThat(renewal.path("status").asText()).isEqualTo("PENDING");
        assertThat(getJson(alice, "/api/renewals", 200).size()).isEqualTo(1);

        // Admin approves renewal -> status becomes IN_USE again (no longer OVERDUE), expiresAt updated.
        var approved = reviewRenewal(admin, renewalId, "APPROVE", newExp.toString(),
                BASE_TIME.plus(Duration.ofHours(6)).toString(), "训练尚未收敛，批准续期至 120h", 200);
        assertThat(approved.path("alreadyReviewed").asBoolean()).isFalse();
        assertThat(getJson(alice, "/api/allocations/" + allocId, 200).path("status").asText()).isEqualTo("IN_USE");

        // Repeat APPROVE -> alreadyReviewed=true; opposite REJECT -> 409.
        assertThat(reviewRenewal(admin, renewalId, "APPROVE", newExp.toString(), null, "重复", 200)
                .path("alreadyReviewed").asBoolean()).isTrue();
        reviewRenewal(admin, renewalId, "REJECT", null, null, "反向改判", 409);

        // Advance to +120h -> triggers a SECOND OVERDUE notification!
        int msgsBefore = count("inbox_messages");
        clock.now = newExp;
        scanner.scanIdleGpus();
        assertThat(getJson(alice, "/api/allocations/" + allocId, 200).path("status").asText()).isEqualTo("OVERDUE");
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore + 1);

        // Security checks: bob cannot submit renewal on alice's allocation (404) or review (403).
        submitRenewal(bob, allocId, newExp.plus(Duration.ofHours(10)).toString(), 404);
        reviewRenewal(bob, renewalId, "APPROVE", newExp.plus(Duration.ofHours(10)).toString(), null, "越权", 403);
    }

    @Test
    void d039UnstartedRenewalBoundariesAndAutoReleaseRace() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // GPU 1: 6h unstarted -> extended to 12h (< 24h) -> auto-releases at 12h.
        // GPU 2: 6h unstarted -> extended to 24h (>= 24h) -> marked ANOMALY at 24h, retained.
        // GPU 3: 6h unstarted -> expires at 6h BEFORE admin approves -> approval returns 409.
        var r1 = submit(alice, List.of("1"), "6", 201);
        var r2 = submit(alice, List.of("2"), "6", 201);
        var r3 = submit(alice, List.of("3"), "6", 201);
        String a1 = r1.path("queueItems").get(0).path("allocationId").asText();
        String a2 = r2.path("queueItems").get(0).path("allocationId").asText();
        String a3 = r3.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("1", "3"), "12", 201);

        String ren1 = submitRenewal(alice, a1, BASE_TIME.plus(Duration.ofHours(12)).toString(), 201).path("id").asText();
        String ren2 = submitRenewal(alice, a2, BASE_TIME.plus(Duration.ofHours(24)).toString(), 201).path("id").asText();
        String ren3 = submitRenewal(alice, a3, BASE_TIME.plus(Duration.ofHours(12)).toString(), 201).path("id").asText();

        // Approve ren1 (6->12h) and ren2 (6->24h) BEFORE 6h.
        reviewRenewal(admin, ren1, "APPROVE", BASE_TIME.plus(Duration.ofHours(12)).toString(), null, "延至12h", 200);
        reviewRenewal(admin, ren2, "APPROVE", BASE_TIME.plus(Duration.ofHours(24)).toString(), null, "延至24h", 200);

        // At +6h: GPU 3 auto-releases to Bob (unapproved renewal does NOT pause release); GPU 1 & 2 remain active.
        clock.now = BASE_TIME.plus(Duration.ofHours(6));
        scanner.scanIdleGpus();
        getJson(alice, "/api/allocations/" + a3, 404);
        reviewRenewal(admin, ren3, "APPROVE", BASE_TIME.plus(Duration.ofHours(12)).toString(), null, "已释放后审批", 409);

        // At +12h: GPU 1 (6->12h, still < 24h) auto-releases to Bob!
        clock.now = BASE_TIME.plus(Duration.ofHours(12));
        scanner.scanIdleGpus();
        getJson(alice, "/api/allocations/" + a1, 404);
        assertThat(getJson(bob, "/api/allocations", 200).size()).isEqualTo(2);

        // At +24h: GPU 2 (6->24h, >= 24h) is marked ANOMALY and retained (not auto-released).
        clock.now = BASE_TIME.plus(Duration.ofHours(24));
        scanner.scanIdleGpus();
        assertThat(getJson(alice, "/api/allocations/" + a2, 200).path("status").asText()).isEqualTo("ANOMALY");
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private MockHttpSession login(String username) throws Exception {
        var csrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var token = json.readTree(csrfResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
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
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode submitRenewal(MockHttpSession session, String allocationId, String requestedExpiresAt, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post("/api/allocations/" + allocationId + "/renewals")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("requestedExpiresAt", requestedExpiresAt))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode reviewRenewal(MockHttpSession session, String renewalId, String decision,
                                   String approvedExpiresAt, String seenExpiresAt, String reason, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        var req = post("/api/admin/renewals/" + renewalId + "/review")
                .session(session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("decision", decision)
                .param("reason", reason);
        if (approvedExpiresAt != null) req = req.param("approvedExpiresAt", approvedExpiresAt);
        if (seenExpiresAt != null) req = req.param("seenExpiresAt", seenExpiresAt);
        String body = mvc.perform(req)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode postEmpty(MockHttpSession session, String path, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post(path)
                        .session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode getJson(MockHttpSession session, String path, int expectedStatus) throws Exception {
        String body = mvc.perform(get(path).session(session))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
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

    private static String newDatabase(String prefix) {
        try {
            Path path = Files.createTempDirectory("gpu-queue-" + prefix).resolve("gpu-queue");
            return "jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/');
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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
