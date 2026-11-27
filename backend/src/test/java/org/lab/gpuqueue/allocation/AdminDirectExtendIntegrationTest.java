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
@Import(AdminDirectExtendIntegrationTest.ClockConfig.class)
class AdminDirectExtendIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-27T04:00:00.000000000Z");
    private static final String JDBC_URL = newDatabase("admin-direct-extend-integration-");

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
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void directExtendUncappedIdempotentConflictAndSecurity() throws Exception {
        var alice = login("alice");
        var admin = login("admin");

        // Alice gets GPU 1 for 6h and starts it.
        var req = submit(alice, List.of("1"), "6", 201);
        String allocId = req.path("queueItems").get(0).path("allocationId").asText();
        postEmpty(alice, "/api/allocations/" + allocId + "/start", 200);

        // Advance to +6h -> OVERDUE.
        clock.now = BASE_TIME.plus(Duration.ofHours(6));
        scanner.scanIdleGpus();
        var active = getJson(admin, "/api/admin/allocations/active", 200);
        assertThat(active.size()).isEqualTo(1);
        assertThat(active.get(0).path("status").asText()).isEqualTo("OVERDUE");

        // Direct extend to +100h (> 72h, no member renewal needed!) -> recovers to IN_USE.
        Instant targetExp = BASE_TIME.plus(Duration.ofHours(100));
        int auditsBefore = count("audit_logs");
        int msgsBefore = count("inbox_messages");
        var ext = directExtend(admin, allocId, "OVERDUE", BASE_TIME.plus(Duration.ofHours(6)).toString(),
                targetExp.toString(), "管理员直接协调延长至 100h", 200);
        assertThat(ext.path("alreadyExtended").asBoolean()).isFalse();
        assertThat(getJson(alice, "/api/allocations/" + allocId, 200).path("status").asText()).isEqualTo("IN_USE");
        assertThat(count("audit_logs")).isEqualTo(auditsBefore + 1);
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore + 1);

        // Repeat same newExpiresAt -> alreadyExtended=true, zero side effects.
        var repeat = directExtend(admin, allocId, "OVERDUE", BASE_TIME.plus(Duration.ofHours(6)).toString(),
                targetExp.toString(), "重复提交", 200);
        assertThat(repeat.path("alreadyExtended").asBoolean()).isTrue();
        assertThat(count("audit_logs")).isEqualTo(auditsBefore + 1);

        // Stale seenExpiresAt with a newer targetExp -> 409 Conflict!
        directExtend(admin, allocId, "IN_USE", BASE_TIME.plus(Duration.ofHours(6)).toString(),
                targetExp.plus(Duration.ofHours(10)).toString(), "过期页面", 409);

        // Shortening / non-extending / blank reason -> 400.
        directExtend(admin, allocId, "IN_USE", targetExp.toString(),
                BASE_TIME.plus(Duration.ofHours(50)).toString(), "试图缩短", 400);
        directExtend(admin, allocId, "IN_USE", targetExp.toString(),
                targetExp.plus(Duration.ofHours(10)).toString(), "   ", 400);

        // Security: anonymous 401, member 403.
        mvc.perform(get("/api/admin/allocations/active")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/allocations/active").session(alice)).andExpect(status().isForbidden());
        directExtend(alice, allocId, "IN_USE", targetExp.toString(),
                targetExp.plus(Duration.ofHours(10)).toString(), "越权", 403);
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

    private JsonNode directExtend(MockHttpSession session, String allocationId, String seenStatus,
                                  String seenExpiresAt, String newExpiresAt, String reason, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post("/api/admin/allocations/" + allocationId + "/extend")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("seenStatus", seenStatus)
                        .param("seenExpiresAt", seenExpiresAt)
                        .param("newExpiresAt", newExpiresAt)
                        .param("reason", reason))
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
