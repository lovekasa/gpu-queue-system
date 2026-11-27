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
@Import(EmergencyRequestIntegrationTest.ClockConfig.class)
class EmergencyRequestIntegrationTest {
    private static final UUID MEMBER_A = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID MEMBER_C = UUID.randomUUID();
    private static final UUID MEMBER_D = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-27T01:00:00.000000000Z");
    private static final String JDBC_URL = newDatabase("emergency-request-integration-");

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
        seedAccount(jdbc, MEMBER_D, "dave", "MEMBER", passwordHash);
        seedAccount(jdbc, ADMIN, "admin", "ADMIN", passwordHash);
    }

    @Test
    void emergencyApprovalOrdering72hBoundaryPartialAllocationAndIdempotency() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var carol = login("carol");
        var dave = login("dave");
        var admin = login("admin");

        // Occupy GPU 1 with alice (48h).
        var aliceReq = submit(alice, List.of("1"), "48", false, 201);
        String aliceAllocId = aliceReq.path("queueItems").get(0).path("allocationId").asText();

        // Bob submits ordinary at T0, Carol submits ordinary at T0+1h, Dave submits emergency on GPU 1 & 2 at T0+2h.
        // GPU 2 is idle -> Dave gets GPU 2 immediately (partial allocation), GPU 1 waits as PENDING emergency.
        submit(bob, List.of("1"), "12", false, 201);
        clock.now = BASE_TIME.plus(Duration.ofHours(1));
        submit(carol, List.of("1"), "12", false, 201);
        clock.now = BASE_TIME.plus(Duration.ofHours(2));
        var daveReq = submit(dave, List.of("1", "2"), "12", true, 201);
        String daveReqId = daveReq.path("id").asText();
        assertThat(daveReq.path("requestType").asText()).isEqualTo("EMERGENCY");
        assertThat(daveReq.path("emergencyStatus").asText()).isEqualTo("PENDING");
        assertThat(daveReq.path("queueItems").get(0).path("status").asText()).isEqualTo("WAITING");
        assertThat(daveReq.path("queueItems").get(1).path("status").asText()).isEqualTo("ALLOCATED");
        String daveGpu2AllocId = daveReq.path("queueItems").get(1).path("allocationId").asText();

        // Before approval: pending emergency participates as ordinary -> [bob, carol, dave].
        assertThat(waitingNames(getJson(alice, "/api/queues", 200).get(0)))
                .containsExactly("bob测试成员", "carol测试成员", "dave测试成员");

        // Admin sees Dave's pending emergency request and approves with non-empty reason.
        var pending = getJson(admin, "/api/admin/requests/emergency-pending", 200);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).path("id").asText()).isEqualTo(daveReqId);

        int auditsBefore = count("audit_logs");
        int msgsBefore = count("inbox_messages");
        var approved = reviewEmergency(admin, daveReqId, "APPROVE", "论文截稿前急需补实验", 200);
        assertThat(approved.path("alreadyReviewed").asBoolean()).isFalse();
        assertThat(approved.path("request").path("emergencyStatus").asText()).isEqualTo("APPROVED");
        assertThat(count("audit_logs")).isEqualTo(auditsBefore + 1);
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore + 1);

        // Repeat same APPROVE decision -> alreadyReviewed=true, zero side effects; opposite REJECT -> 409.
        var repeatApprove = reviewEmergency(admin, daveReqId, "APPROVE", "重复点击", 200);
        assertThat(repeatApprove.path("alreadyReviewed").asBoolean()).isTrue();
        assertThat(count("audit_logs")).isEqualTo(auditsBefore + 1);
        assertThat(count("inbox_messages")).isEqualTo(msgsBefore + 1);
        reviewEmergency(admin, daveReqId, "REJECT", "试图改判", 409);

        // Already-allocated GPU 2 is untouched.
        var daveAllocs = getJson(dave, "/api/allocations", 200);
        assertThat(daveAllocs.size()).isEqualTo(1);
        assertThat(daveAllocs.get(0).path("id").asText()).isEqualTo(daveGpu2AllocId);

        // At exact T0 + 72h: Bob waited == 72h (NOT > 72h), so approved emergency Dave leads -> [dave, bob, carol].
        clock.now = BASE_TIME.plus(Duration.ofHours(72));
        var queuesAt72h = getJson(alice, "/api/queues", 200).get(0);
        assertThat(waitingNames(queuesAt72h)).containsExactly("dave测试成员", "bob测试成员", "carol测试成员");
        // Strict field isolation: public queue never leaks approval reason.
        for (JsonNode entry : queuesAt72h.path("waiting")) {
            List<String> fields = new ArrayList<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("position", "memberName");
        }

        // At T0 + 72h + 1s: Bob waited strictly > 72h -> [bob, dave, carol].
        clock.now = BASE_TIME.plus(Duration.ofHours(72)).plusSeconds(1);
        assertThat(waitingNames(getJson(alice, "/api/queues", 200).get(0)))
                .containsExactly("bob测试成员", "dave测试成员", "carol测试成员");

        // Alice returns GPU 1 -> Bob (wait-boosted > 72h) receives GPU 1, matching public queue.
        postEmpty(alice, "/api/allocations/" + aliceAllocId + "/return", 200);
        assertThat(getJson(bob, "/api/allocations", 200).size()).isEqualTo(1);
        assertThat(waitingNames(getJson(alice, "/api/queues", 200).get(0)))
                .containsExactly("dave测试成员", "carol测试成员");
    }

    @Test
    void emergencyRejectionPreservesOrdinaryOrderAndSecurityRejectsUnauthorizedOrForgedRequests() throws Exception {
        var alice = login("alice");
        var bob = login("bob");
        var admin = login("admin");

        // Occupy GPU 1 with alice.
        submit(alice, List.of("1"), "48", false, 201);
        // Bob submits emergency on GPU 1 & 2 -> GPU 2 allocated immediately, GPU 1 waits.
        var bobReq = submit(bob, List.of("1", "2"), "12", true, 201);
        String bobReqId = bobReq.path("id").asText();

        // Admin rejects emergency with reason -> GPU 1 remains in ordinary queue, GPU 2 allocation remains active.
        var rejected = reviewEmergency(admin, bobReqId, "REJECT", "非紧急任务，请按普通队列等待", 200);
        assertThat(rejected.path("request").path("emergencyStatus").asText()).isEqualTo("REJECTED");
        assertThat(waitingNames(getJson(alice, "/api/queues", 200).get(0))).containsExactly("bob测试成员");
        assertThat(getJson(bob, "/api/allocations", 200).size()).isEqualTo(1);

        // Bob can read own inbox message with rejection reason.
        var bobMsgs = getJson(bob, "/api/messages", 200);
        assertThat(bobMsgs.get(0).path("type").asText()).isEqualTo("EMERGENCY_REJECTED");
        assertThat(bobMsgs.get(0).path("body").asText()).contains("非紧急任务，请按普通队列等待");

        // Forged self-approval field on /api/requests is rejected with 400.
        var bobToken = getJson(bob, "/api/csrf", 200);
        mvc.perform(post("/api/requests")
                        .session(bob)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(bobToken.path("headerName").asText(), bobToken.path("token").asText())
                        .param("durationHours", "6")
                        .param("gpuIds", "3")
                        .param("emergency", "true")
                        .param("emergencyStatus", "APPROVED"))
                .andExpect(status().isBadRequest());

        // Security & validation checks on admin emergency endpoints.
        mvc.perform(get("/api/admin/requests/emergency-pending")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/requests/emergency-pending").session(bob)).andExpect(status().isForbidden());
        reviewEmergency(bob, bobReqId, "APPROVE", "越权审批", 403);
        reviewEmergency(admin, bobReqId, "APPROVE", "   ", 400);
        reviewEmergency(admin, bobReqId, "INVALID", "理由", 400);
        reviewEmergency(admin, UUID.randomUUID().toString(), "APPROVE", "不存在", 404);
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

    private JsonNode submit(MockHttpSession session, List<String> gpuIds, String hours,
                            boolean emergency, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        var req = post("/api/requests")
                .session(session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("durationHours", hours)
                .param("gpuIds", gpuIds.toArray(String[]::new));
        if (emergency) {
            req = req.param("emergency", "true");
        }
        String body = mvc.perform(req)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private JsonNode reviewEmergency(MockHttpSession session, String requestId, String decision,
                                     String reason, int expectedStatus) throws Exception {
        var token = getJson(session, "/api/csrf", 200);
        String body = mvc.perform(post("/api/admin/requests/" + requestId + "/emergency-review")
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .param("decision", decision)
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
