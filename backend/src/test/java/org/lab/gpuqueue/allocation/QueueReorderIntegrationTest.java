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
import java.util.ArrayList;
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
@Import(QueueReorderIntegrationTest.ClockConfig.class)
class QueueReorderIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID MEMBER_C = UUID.randomUUID();
    private static final UUID MEMBER_D = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-27T02:00:00.000000000Z");
    private static final String JDBC_URL = newDatabase("queue-reorder-integration-");

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
        seedAccount(jdbc, MEMBER_C, "carol", "MEMBER", passwordHash);
        seedAccount(jdbc, MEMBER_D, "dave", "MEMBER", passwordHash);
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void reorderOverridesBasePriorityMatchesPublicAndAllocationNotifiesAffectedAndReplaysAfterAnchorLeaves() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var carol = login("carol");
        var dave = login("dave");
        var admin = login("admin");

        // Occupy GPU 1 with alice.
        var aliceReq = submit(alice, List.of("1"), "48", 201);
        String aliceAllocId = aliceReq.path("queueItems").get(0).path("allocationId").asText();

        // Bob, Carol, Dave queue on GPU 1.
        var bobReq = submit(bob, List.of("1"), "12", 201);
        clock.now = BASE_TIME.plus(Duration.ofMinutes(1));
        var carolReq = submit(carol, List.of("1"), "12", 201);
        clock.now = BASE_TIME.plus(Duration.ofMinutes(2));
        var daveReq = submit(dave, List.of("1"), "12", 201);

        String bobQi = bobReq.path("queueItems").get(0).path("id").asText();
        String carolQi = carolReq.path("queueItems").get(0).path("id").asText();
        String daveQi = daveReq.path("queueItems").get(0).path("id").asText();

        // Advance >72h so Bob is wait-boosted, then admin moves Dave to position 1 (overriding >72h boost).
        clock.now = BASE_TIME.plus(Duration.ofHours(73));
        var adminQueues = getJson(admin, "/api/admin/queues", 200);
        List<String> seen1 = queueItemIds(adminQueues.get(0));
        assertThat(seen1).containsExactly(bobQi, carolQi, daveQi);

        // No-op move (Dave already at position 3) -> changed=false, zero audit/messages.
        int auditsBefore = count("audit_logs");
        int msgsBefore = count("inbox_messages");
        var noop = reorder(admin, "1", daveQi, 3, seen1, "未改变位置", 200);
        assertThat(noop.path("changed").asBoolean()).isFalse();
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore);

        // Move Dave to position 1 -> [dave, bob, carol], all 3 affected members notified once, 1 audit row.
        var moved = reorder(admin, "1", daveQi, 1, seen1, "紧急演示优先协调", 200);
        assertThat(moved.path("changed").asBoolean()).isTrue();
        assertThat(queueItemIds(moved)).containsExactly(daveQi, bobQi, carolQi);
        assertThat(count("audit_logs")).isEqualTo(auditsBefore + 1);
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore + 3);

        // Public queue matches reordered sequence and still exposes only position & memberName.
        var publicGpu1 = getJson(bob, "/api/queues", 200).get(0);
        assertThat(waitingNames(publicGpu1)).containsExactly("dave测试成员", "bob测试成员", "carol测试成员");
        for (JsonNode entry : publicGpu1.path("waiting")) {
            List<String> fields = new ArrayList<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("position", "memberName");
        }

        // Stale seenQueueItemIds (using old seen1) -> 409 Conflict!
        reorder(admin, "1", carolQi, 1, seen1, "过期快照", 409);

        // Alice returns GPU 1 -> Dave receives GPU 1 (matching reordered queue!), leaving [bob, carol].
        postEmpty(alice, "/api/allocations/" + aliceAllocId + "/return", 200);
        assertThat(getJson(dave, "/api/allocations", 200).size()).isEqualTo(1);
        assertThat(waitingNames(getJson(bob, "/api/queues", 200).get(0)))
                .containsExactly("bob测试成员", "carol测试成员");

        // Dave submits a NEW request on GPU 1 -> does NOT inherit old reorder rule, lands at end [bob, carol, dave].
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        submit(dave, List.of("1"), "6", 201);
        assertThat(waitingNames(getJson(bob, "/api/queues", 200).get(0)))
                .containsExactly("bob测试成员", "carol测试成员", "dave测试成员");
    }

    @Test
    void securityAndValidationRejectUnauthorizedOrInvalidReorder() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        submit(alice, List.of("1"), "48", 201);
        var bobReq = submit(bob, List.of("1"), "12", 201);
        String bobQi = bobReq.path("queueItems").get(0).path("id").asText();

        mvc.perform(get("/api/admin/queues")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/queues").session(bob)).andExpect(status().isForbidden());
        reorder(bob, "1", bobQi, 1, List.of(bobQi), "越权调序", 403);
        reorder(admin, "1", bobQi, 1, List.of(bobQi), "   ", 400);
        reorder(admin, "1", bobQi, 0, List.of(bobQi), "非法位置", 400);
        reorder(admin, "1", bobQi, 2, List.of(bobQi), "越界位置", 400);
        reorder(admin, "99", bobQi, 1, List.of(bobQi), "不存在的GPU", 404);
    }

    private List<String> queueItemIds(JsonNode gpuQueue) {
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : gpuQueue.path("waiting")) {
            ids.add(entry.path("queueItemId").asText());
        }
        return ids;
    }

    private List<String> waitingNames(JsonNode gpuQueue) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : gpuQueue.path("waiting")) {
            names.add(entry.path("memberName").asText());
        }
        return names;
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

    private JsonNode reorder(MockHttpSession session, String gpuId, String targetQueueItemId, int targetPosition,
                             List<String> seenQueueItemIds, String reason, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post("/api/admin/queues/" + gpuId + "/reorder")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("targetQueueItemId", targetQueueItemId)
                        .param("targetPosition", Integer.toString(targetPosition))
                        .param("seenQueueItemIds", seenQueueItemIds.toArray(String[]::new))
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
