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
@Import(AdminVerifiedReleaseIntegrationTest.ClockConfig.class)
class AdminVerifiedReleaseIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T04:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("admin-verified-release-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
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
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void releasesVerifiedAnomalyAndOverdueWithAuditNotificationAndSuccessorHandover() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // GPU 1: 48h unstarted -> ANOMALY at 24h; GPU 2: 6h started -> OVERDUE at 6h. Bob waits on both.
        var req1 = submit(alice, List.of("1"), "48", 201);
        var req2 = submit(alice, List.of("2"), "6", 201);
        String alloc1 = req1.path("queueItems").get(0).path("allocationId").asText();
        String alloc2 = req2.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("1", "2"), "12", 201);
        postEmpty(alice, "/api/allocations/" + alloc2 + "/start", 200);

        clock.now = BASE_TIME.plus(Duration.ofHours(24));
        allocationScanner.scanIdleGpus();

        var pending = getJson(admin, "/api/admin/allocations/pending-verification", 200);
        assertThat(pending.size()).isEqualTo(2);
        assertThat(pending.get(0).path("status").asText()).isEqualTo("ANOMALY");
        assertThat(pending.get(1).path("status").asText()).isEqualTo("OVERDUE");

        // Release GPU 1 (ANOMALY) with reason -> Bob gets GPU 1, Alice gets VERIFIED_RELEASED message, admin audit saved.
        var released1 = verifiedRelease(admin, alloc1, "ANOMALY", "true", "已电话核实成员暂不使用，可释放", 200);
        assertThat(released1.path("alreadyEnded").asBoolean()).isFalse();
        assertThat(released1.path("endReason").asText()).isEqualTo("VERIFIED_RELEASED");

        // Repeat release on already ended alloc1 returns alreadyEnded=true with zero side effects.
        int auditsAfter1 = count("audit_logs");
        var repeat1 = verifiedRelease(admin, alloc1, "ANOMALY", "true", "重复点击", 200);
        assertThat(repeat1.path("alreadyEnded").asBoolean()).isTrue();
        assertThat(count("audit_logs")).isEqualTo(auditsAfter1);

        // Release GPU 2 (OVERDUE) with reason -> Bob gets GPU 2 too.
        var released2 = verifiedRelease(admin, alloc2, "OVERDUE", "true", "已核实训练任务完成并可释放", 200);
        assertThat(released2.path("alreadyEnded").asBoolean()).isFalse();

        assertThat(getJson(bob, "/api/allocations", 200).size()).isEqualTo(2);
        assertThat(getJson(admin, "/api/admin/allocations/pending-verification", 200).size()).isZero();

        // Verify admin audit records carry real admin actor and non-empty reason.
        int adminAuditCount = jdbc.sql("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_type = 'ADMIN' AND actor_account_id = :adminId
                  AND action = 'VERIFIED_RELEASED' AND reason IS NOT NULL
                """).param("adminId", ADMIN).query(Integer.class).single();
        assertThat(adminAuditCount).isEqualTo(2);
        assertThat(getJson(alice, "/api/messages", 200).get(0).path("type").asText()).isEqualTo("VERIFIED_RELEASED");
    }

    @Test
    void returns409WhenSeenStatusChangesAndEnforcesValidationAuthAndRollback() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        var req = submit(alice, List.of("3"), "48", 201);
        String allocId = req.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("3"), "12", 201);

        clock.now = BASE_TIME.plus(Duration.ofHours(24));
        allocationScanner.scanIdleGpus();

        // Auth checks: anonymous -> 401, member -> 403.
        mvc.perform(get("/api/admin/allocations/pending-verification")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/allocations/pending-verification").session(alice)).andExpect(status().isForbidden());
        verifiedReleaseRaw(alice, allocId, "ANOMALY", "true", "成员越权", 403);

        // Validation: blank reason or unverified -> 400.
        verifiedReleaseRaw(admin, allocId, "ANOMALY", "true", "   ", 400);
        verifiedReleaseRaw(admin, allocId, "ANOMALY", "false", "未勾选核实", 400);

        // Member confirms start after anomaly -> status becomes IN_USE, so stale seenStatus=ANOMALY returns 409!
        postEmpty(alice, "/api/allocations/" + allocId + "/start", 200);
        var conflict = verifiedRelease(admin, allocId, "ANOMALY", "true", "基于旧页面状态释放", 409);
        assertThat(conflict.path("code").asText()).isEqualTo("STATUS_CHANGED");
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 3").query(UUID.class).single())
                .isEqualTo(UUID.fromString(allocId));

        // Advance to 48h -> OVERDUE; simulate audit write failure -> 503 rollback, then recover cleanly.
        clock.now = BASE_TIME.plus(Duration.ofHours(48));
        jdbc.sql("ALTER TABLE audit_logs ADD CONSTRAINT test_admin_audit_fail CHECK (action <> 'VERIFIED_RELEASED')")
                .update();
        try {
            assertThat(verifiedReleaseRaw(admin, allocId, "OVERDUE", "true", "测试回滚", 503)).isEmpty();
            assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 3").query(UUID.class).single())
                    .isEqualTo(UUID.fromString(allocId));
            assertThat(getJson(bob, "/api/allocations", 200).size()).isZero();
        } finally {
            jdbc.sql("ALTER TABLE audit_logs DROP CONSTRAINT test_admin_audit_fail").update();
        }

        verifiedRelease(admin, allocId, "OVERDUE", "true", "确认任务已完成", 200);
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

    private void postEmpty(MockHttpSession session, String path, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        mvc.perform(post(path)
                        .session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().is(expectedStatus));
    }

    private String verifiedReleaseRaw(MockHttpSession session, String allocationId, String seenStatus,
                                      String verified, String reason, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        return mvc.perform(post("/api/admin/allocations/" + allocationId + "/verified-release")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("seenStatus", seenStatus)
                        .param("verified", verified)
                        .param("reason", reason))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode verifiedRelease(MockHttpSession session, String allocationId, String seenStatus,
                                     String verified, String reason, int expectedStatus) throws Exception {
        String body = verifiedReleaseRaw(session, allocationId, seenStatus, verified, reason, expectedStatus);
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
