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
@Import(AllocationReturnIntegrationTest.ClockConfig.class)
class AllocationReturnIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-26T02:00:00.123456789Z");
    private static final String JDBC_URL = newDatabase("allocation-return-integration-");

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
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void returnsSingleCardWithoutAffectingOtherCardsAndLeavesIdleWhenNoWaiters() throws Exception {
        var alice = login("alice");
        var created = submit(alice, List.of("2", "3"), "24", 201);
        String requestId = created.path("id").asText();
        String gpu2Alloc = created.path("queueItems").get(0).path("allocationId").asText();
        String gpu3Alloc = created.path("queueItems").get(1).path("allocationId").asText();

        Instant startTime = BASE_TIME.plus(Duration.ofHours(1));
        clock.now = startTime;
        postAction(alice, "/api/allocations/" + gpu2Alloc + "/start", 200);

        Instant returnTime = BASE_TIME.plus(Duration.ofHours(4));
        clock.now = returnTime;
        int messagesBefore = count("inbox_messages");
        var returned = postAction(alice, "/api/allocations/" + gpu2Alloc + "/return", 200);

        assertThat(returned.path("alreadyEnded").asBoolean()).isFalse();
        assertThat(returned.path("allocation").path("status").asText()).isEqualTo("ENDED");
        assertThat(returned.path("allocation").path("endReason").asText()).isEqualTo("RETURNED");
        assertThat(returned.path("allocation").path("endedAt").asText()).isEqualTo(returnTime.toString());
        assertThat(returned.path("allocation").path("startedAt").asText()).isEqualTo(startTime.toString());
        assertThat(count("inbox_messages")).isEqualTo(messagesBefore);

        // GPU 2 is idle; GPU 3 remains active for Alice.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM current_allocations WHERE gpu_id = 2").query(Integer.class).single()).isZero();
        var activeAllocations = getJson(alice, "/api/allocations", 200);
        assertThat(activeAllocations.size()).isEqualTo(1);
        assertThat(activeAllocations.get(0).path("id").asText()).isEqualTo(gpu3Alloc);

        // Request history preserves original allocation, start, and return facts on GPU 2.
        var reqDetail = getJson(alice, "/api/requests/" + requestId, 200);
        var gpu2Item = reqDetail.path("queueItems").get(0);
        assertThat(gpu2Item.path("allocationStatus").asText()).isEqualTo("ENDED");
        assertThat(gpu2Item.path("endReason").asText()).isEqualTo("RETURNED");
        assertThat(gpu2Item.path("startedAt").asText()).isEqualTo(startTime.toString());
        assertThat(gpu2Item.path("endedAt").asText()).isEqualTo(returnTime.toString());
        assertThat(reqDetail.path("queueItems").get(1).path("allocationStatus").asText()).isEqualTo("UNSTARTED");
    }

    @Test
    void handsOverAtomicallyToNextEligibleWaiterAndIsolatesReplayedOldReturnEvenForSameMember() throws Exception {
        var alice = login("alice");
        var bob = login("bob");

        // Round 1: Alice holds GPU 1 (A101). Alice queues again on GPU 1 first, then Bob queues on GPU 1.
        var a101Req = submit(alice, List.of("1"), "12", 201);
        String a101 = a101Req.path("queueItems").get(0).path("allocationId").asText();
        clock.now = BASE_TIME.plus(Duration.ofMinutes(10));
        submit(alice, List.of("1"), "24", 201);
        clock.now = BASE_TIME.plus(Duration.ofMinutes(20));
        submit(bob, List.of("1"), "6", 201);

        // Alice returns A101 -> Alice's second waiting item wins A102 with a brand-new allocation_id.
        Instant tReturn1 = BASE_TIME.plus(Duration.ofHours(2));
        clock.now = tReturn1;
        var res1 = postAction(alice, "/api/allocations/" + a101 + "/return", 200);
        assertThat(res1.path("alreadyEnded").asBoolean()).isFalse();

        var aliceActive = getJson(alice, "/api/allocations", 200);
        assertThat(aliceActive.size()).isEqualTo(1);
        String a102 = aliceActive.get(0).path("id").asText();
        assertThat(a102).isNotEqualTo(a101);
        assertThat(aliceActive.get(0).path("allocatedAt").asText()).isEqualTo(tReturn1.toString());
        assertThat(aliceActive.get(0).path("expiresAt").asText()).isEqualTo(tReturn1.plus(Duration.ofHours(24)).toString());
        assertThat(getJson(alice, "/api/messages", 200).size()).isEqualTo(2);

        // Replay return on old A101 (even though Alice holds A102 on the same GPU!): A102 is untouched.
        int auditsBeforeReplay = count("audit_logs");
        int messagesBeforeReplay = count("inbox_messages");
        clock.now = tReturn1.plus(Duration.ofHours(1));
        var replay = postAction(alice, "/api/allocations/" + a101 + "/return", 200);
        assertThat(replay.path("alreadyEnded").asBoolean()).isTrue();
        assertThat(replay.path("allocation").path("endedAt").asText()).isEqualTo(tReturn1.toString());
        assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 1").query(UUID.class).single())
                .isEqualTo(UUID.fromString(a102));
        assertThat(count("audit_logs")).isEqualTo(auditsBeforeReplay);
        assertThat(count("inbox_messages")).isEqualTo(messagesBeforeReplay);

        // Now Alice returns A102 -> Bob receives A103 and 1 allocation message.
        Instant tReturn2 = tReturn1.plus(Duration.ofHours(2));
        clock.now = tReturn2;
        postAction(alice, "/api/allocations/" + a102 + "/return", 200);
        var bobActive = getJson(bob, "/api/allocations", 200);
        assertThat(bobActive.size()).isEqualTo(1);
        assertThat(bobActive.get(0).path("gpuId").asText()).isEqualTo("1");
        assertThat(bobActive.get(0).path("allocatedAt").asText()).isEqualTo(tReturn2.toString());
        assertThat(bobActive.get(0).path("expiresAt").asText()).isEqualTo(tReturn2.plus(Duration.ofHours(6)).toString());
        assertThat(getJson(bob, "/api/messages", 200).size()).isEqualTo(1);
    }

    @Test
    void rollsBackBothReturnAndHandoverWhenSuccessorWriteFailsAndEnforcesAuth() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        var a1 = submit(alice, List.of("5"), "12", 201);
        String a1Id = a1.path("queueItems").get(0).path("allocationId").asText();
        submit(bob, List.of("5"), "12", 201);

        // Auth & CSRF checks
        mvc.perform(post("/api/allocations/" + a1Id + "/return")).andExpect(status().isForbidden());
        assertThat(postActionRaw(bob, "/api/allocations/" + a1Id + "/return", 404)).isEmpty();
        postActionRaw(admin, "/api/allocations/" + a1Id + "/return", 403);
        assertThat(postActionRaw(alice, "/api/allocations/not-a-uuid/return", 404)).isEmpty();

        // Fail successor's inbox message insert -> entire return + handover rolls back!
        jdbc.sql("ALTER TABLE inbox_messages ADD CONSTRAINT test_handover_fail CHECK (recipient_id <> '" + MEMBER_B + "')")
                .update();
        try {
            assertThat(postActionRaw(alice, "/api/allocations/" + a1Id + "/return", 503)).isEmpty();
            assertThat(jdbc.sql("SELECT allocation_id FROM current_allocations WHERE gpu_id = 5").query(UUID.class).single())
                    .isEqualTo(UUID.fromString(a1Id));
            assertThat(getJson(bob, "/api/allocations", 200).size()).isZero();
        } finally {
            jdbc.sql("ALTER TABLE inbox_messages DROP CONSTRAINT test_handover_fail").update();
        }

        // Retry succeeds cleanly once.
        var recovered = postAction(alice, "/api/allocations/" + a1Id + "/return", 200);
        assertThat(recovered.path("alreadyEnded").asBoolean()).isFalse();
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

    private String postActionRaw(MockHttpSession session, String path, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        return mvc.perform(post(path)
                        .session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode postAction(MockHttpSession session, String path, int expectedStatus) throws Exception {
        String body = postActionRaw(session, path, expectedStatus);
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
