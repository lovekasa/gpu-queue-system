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
@Import(PublicQueueAndInboxReadIntegrationTest.ClockConfig.class)
class PublicQueueAndInboxReadIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID MEMBER_C = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T05:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("public-queue-and-inbox-read-integration-");

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
    void publicQueuesExposeOnlyPositionAndMemberNameAndReflectOrderingAndPartialAllocation() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var carol = login("carol");
        var admin = login("admin");

        // Anonymous cannot read public queues.
        mvc.perform(get("/api/queues")).andExpect(status().isUnauthorized());

        // Initially all 10 GPUs are present with empty waiting lists.
        var initial = getJson(alice, "/api/queues", 200);
        assertThat(initial.size()).isEqualTo(10);
        assertThat(initial.get(0).path("gpuId").asText()).isEqualTo("1");
        assertThat(initial.get(9).path("gpuId").asText()).isEqualTo("10");
        assertThat(initial.get(0).path("waiting").size()).isZero();

        // Occupy GPU 1 with alice (48h).
        submit(alice, List.of("1"), "48", 201);

        // Bob submits 12h on GPU 1, Carol submits 24h on GPU 1, then Bob submits 6h on GPU 1 and GPU 2.
        // GPU 2 is idle so Bob's second request gets GPU 2 immediately (partial allocation) while GPU 1 stays queued.
        clock.now = BASE_TIME.plusSeconds(10);
        submit(bob, List.of("1"), "12", 201);
        clock.now = BASE_TIME.plusSeconds(20);
        submit(carol, List.of("1"), "24", 201);
        clock.now = BASE_TIME.plusSeconds(30);
        submit(bob, List.of("1", "2"), "6", 201);

        // GPU 1 waiting order: bob, carol, bob (same member appears twice at actual positions); GPU 2 empty.
        var queues = getJson(carol, "/api/queues", 200);
        var gpu1 = queues.get(0);
        var gpu2 = queues.get(1);
        assertThat(gpu1.path("gpuId").asText()).isEqualTo("1");
        assertThat(gpu1.path("waiting").size()).isEqualTo(3);
        assertThat(waitingNames(gpu1)).containsExactly("bob测试成员", "carol测试成员", "bob测试成员");
        assertThat(gpu1.path("waiting").get(0).path("position").asInt()).isEqualTo(1);
        assertThat(gpu1.path("waiting").get(1).path("position").asInt()).isEqualTo(2);
        assertThat(gpu1.path("waiting").get(2).path("position").asInt()).isEqualTo(3);
        assertThat(gpu2.path("waiting").size()).isZero();

        // Strict field isolation: each waiting entry has ONLY position and memberName.
        for (JsonNode entry : gpu1.path("waiting")) {
            List<String> fields = new ArrayList<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("position", "memberName");
        }

        // Admin can also read the same public queue view.
        var adminQueues = getJson(admin, "/api/queues", 200);
        assertThat(waitingNames(adminQueues.get(0))).containsExactly("bob测试成员", "carol测试成员", "bob测试成员");
    }

    @Test
    void inboxMessagesStayUnreadUntilExplicitMarkReadAndPreserveFirstReadTimestamp() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // Trigger an inbox message for alice by allocating GPU 1 immediately.
        submit(alice, List.of("1"), "12", 201);

        // Reading list and detail must NOT auto-mark readAt.
        var listBefore = getJson(alice, "/api/messages", 200);
        assertThat(listBefore.size()).isEqualTo(1);
        JsonNode msg = listBefore.get(0);
        String messageId = msg.path("id").asText();
        assertThat(msg.has("readAt")).isFalse();

        var detailBefore = getJson(alice, "/api/messages/" + messageId, 200);
        assertThat(detailBefore.has("readAt")).isFalse();

        // Mark as read explicitly at BASE_TIME + 5m.
        clock.now = BASE_TIME.plus(Duration.ofMinutes(5));
        int auditsBefore = count("audit_logs");
        var firstRead = postEmpty(alice, "/api/messages/" + messageId + "/read", 200);
        assertThat(firstRead.path("alreadyRead").asBoolean()).isFalse();
        String firstReadAt = firstRead.path("message").path("readAt").asText();
        assertThat(firstReadAt).endsWith("Z");
        assertThat(Instant.parse(firstReadAt)).isEqualTo(clock.now);
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);

        // Subsequent list & detail include readAt.
        var listAfter = getJson(alice, "/api/messages", 200);
        assertThat(listAfter.get(0).path("readAt").asText()).isEqualTo(firstReadAt);
        var detailAfter = getJson(alice, "/api/messages/" + messageId, 200);
        assertThat(detailAfter.path("readAt").asText()).isEqualTo(firstReadAt);

        // Repeat mark-read later is idempotent and preserves first readAt.
        clock.now = BASE_TIME.plus(Duration.ofHours(2));
        var secondRead = postEmpty(alice, "/api/messages/" + messageId + "/read", 200);
        assertThat(secondRead.path("alreadyRead").asBoolean()).isTrue();
        assertThat(secondRead.path("message").path("readAt").asText()).isEqualTo(firstReadAt);

        // Security, CSRF, role, and member isolation checks.
        var anonCsrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var anonSession = (MockHttpSession) anonCsrfResult.getRequest().getSession(false);
        var anonToken = json.readTree(anonCsrfResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post("/api/messages/" + messageId + "/read")
                        .session(anonSession)
                        .header(anonToken.path("headerName").asText(), anonToken.path("token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/messages/" + messageId + "/read").session(alice))
                .andExpect(status().isForbidden());
        postEmpty(admin, "/api/messages/" + messageId + "/read", 403);
        postEmpty(bob, "/api/messages/" + messageId + "/read", 404);
        postEmpty(alice, "/api/messages/" + UUID.randomUUID() + "/read", 404);
        postEmpty(alice, "/api/messages/not-a-uuid/read", 404);
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

    private JsonNode postEmpty(MockHttpSession session, String path, int expectedStatus) throws Exception {
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
