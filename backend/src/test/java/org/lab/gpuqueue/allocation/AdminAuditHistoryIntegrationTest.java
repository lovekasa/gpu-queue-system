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
@Import(AdminAuditHistoryIntegrationTest.ClockConfig.class)
class AdminAuditHistoryIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-27T07:00:00.000000000Z");
    private static final String JDBC_URL = newDatabase("admin-audit-history-integration-");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
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
    void auditHistoryTracesAllocationAndRequestInterventionsChronologicallyAndEnforcesAdminOnly() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // Alice gets GPU 1 (24h). Alice also submits a second request on GPU 1 so she is her own successor.
        var r1 = submit(alice, List.of("1"), "24", 201);
        String alloc1 = r1.path("queueItems").get(0).path("allocationId").asText();
        String req1 = r1.path("id").asText();
        submit(alice, List.of("1"), "12", 201);

        // Admin directly extends alloc1, then revokes alloc1 -> Alice gets alloc2 as successor.
        var token1 = getJson(admin, "/api/csrf", 200);
        mvc.perform(post("/api/admin/allocations/" + alloc1 + "/extend")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token1.path("headerName").asText(), token1.path("token").asText())
                        .param("seenStatus", "UNSTARTED")
                        .param("seenExpiresAt", BASE_TIME.plus(Duration.ofHours(24)).toString())
                        .param("newExpiresAt", BASE_TIME.plus(Duration.ofHours(48)).toString())
                        .param("reason", "直接延期至 48h"))
                .andExpect(status().isOk());

        var token2 = getJson(admin, "/api/csrf", 200);
        mvc.perform(post("/api/admin/allocations/" + alloc1 + "/revoke")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token2.path("headerName").asText(), token2.path("token").asText())
                        .param("seenStatus", "UNSTARTED")
                        .param("reason", "主动撤销腾出"))
                .andExpect(status().isOk());

        // Query by allocationId=alloc1 -> ALLOCATED, ADMIN_EXTENDED, ADMIN_REVOKED in chronological order;
        // does NOT include successor alloc2!
        int totalBeforeQuery = count("audit_logs");
        var byAlloc = getJson(admin, "/api/admin/audit-logs?allocationId=" + alloc1, 200);
        assertThat(byAlloc.size()).isEqualTo(3);
        assertThat(byAlloc.get(0).path("action").asText()).isEqualTo("ALLOCATED");
        assertThat(byAlloc.get(1).path("action").asText()).isEqualTo("ADMIN_EXTENDED");
        assertThat(byAlloc.get(1).path("reason").asText()).isEqualTo("直接延期至 48h");
        assertThat(byAlloc.get(1).path("actorDisplayName").asText()).isEqualTo("admin测试成员");
        assertThat(byAlloc.get(2).path("action").asText()).isEqualTo("ADMIN_REVOKED");
        assertThat(byAlloc.get(2).path("reason").asText()).isEqualTo("主动撤销腾出");

        // Query by requestId=req1 returns same 3 entries; querying is strictly read-only.
        var byReq = getJson(admin, "/api/admin/audit-logs?requestId=" + req1, 200);
        assertThat(byReq.size()).isEqualTo(3);
        assertThat(count("audit_logs")).isEqualTo(totalBeforeQuery);

        // Unknown requestId -> empty list.
        assertThat(getJson(admin, "/api/admin/audit-logs?requestId=" + UUID.randomUUID(), 200).size()).isZero();

        // Auth isolation: anonymous 401, member 403, invalid UUID 400.
        mvc.perform(get("/api/admin/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit-logs?requestId=" + req1).session(bob)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit-logs?requestId=not-a-uuid").session(admin)).andExpect(status().isBadRequest());
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
