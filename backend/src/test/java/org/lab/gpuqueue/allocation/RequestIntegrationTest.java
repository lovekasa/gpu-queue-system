package org.lab.gpuqueue.allocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lab.gpuqueue.GpuQueueApplication;
import org.lab.gpuqueue.account.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RequestIntegrationTest.ClockConfiguration.class)
class RequestIntegrationTest {
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OTHER_MEMBER = UUID.randomUUID();
    private static final Instant BASE_TIME = Instant.parse("2026-11-25T03:04:05.123456789Z");
    private static final String JDBC_URL = newDatabase("requests-integration-");
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
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM queue_items").update();
        jdbc.sql("DELETE FROM requests").update();
        jdbc.sql("DELETE FROM accounts").update();
        clock.now = BASE_TIME;
        if (passwordHash == null) passwordHash = passwords.encode("integration-password");
        seedAccount(jdbc, MEMBER, "member", "MEMBER", passwordHash);
        seedAccount(jdbc, OTHER_MEMBER, "other", "MEMBER", passwordHash);
        seedAccount(jdbc, UUID.randomUUID(), "admin", "ADMIN", passwordHash);
    }

    @Test
    void acceptsSingleAndMultipleCardsWithBoundaryDurationsAndNoAllocationSideEffects() throws Exception {
        var session = login("member");
        var single = submit(session, List.of("2"), "1", 201);
        var multi = submit(session, List.of("3", "1"), "24", 201);
        var maximum = submit(session, List.of("10"), "72", 201);
        assertThat(single.path("durationHours").asInt()).isEqualTo(1);
        assertThat(maximum.path("durationHours").asInt()).isEqualTo(72);
        assertThat(multi.path("id").isTextual()).isTrue();
        assertThat(multi.path("receiveSeq").isTextual()).isTrue();
        assertThat(multi.path("submittedAt").asText()).isEqualTo(BASE_TIME.toString());
        assertThat(multi.path("durationHours").asInt()).isEqualTo(24);
        assertThat(multi.path("queueItems").size()).isEqualTo(2);
        assertThat(multi.path("queueItems").get(0).path("gpuId").asText()).isEqualTo("1");
        assertThat(multi.path("queueItems").get(1).path("gpuId").asText()).isEqualTo("3");
        multi.path("queueItems").forEach(item -> {
            assertThat(item.path("id").isTextual()).isTrue();
            assertThat(item.path("gpuId").isTextual()).isTrue();
            assertThat(item.path("status").asText()).isEqualTo("WAITING");
            assertThat(item.size()).isEqualTo(3);
        });
        assertThat(multi.size()).isEqualTo(5);
        assertThat(count("requests")).isEqualTo(3);
        assertThat(count("queue_items")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT member_id FROM requests WHERE id = :id")
                .param("id", UUID.fromString(multi.path("id").asText())).query(UUID.class).single()).isEqualTo(MEMBER);
        var savedTime = jdbc.sql("SELECT submitted_at FROM requests WHERE id = :id")
                .param("id", UUID.fromString(multi.path("id").asText())).query(OffsetDateTime.class).single();
        assertThat(savedTime.toInstant()).isEqualTo(BASE_TIME);
        assertThat(savedTime.getOffset()).isEqualTo(ZoneOffset.UTC);
        var refreshed = read(mvc.perform(get("/api/requests").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(refreshed.get(0)).isEqualTo(maximum);
        assertThat(refreshed.get(1)).isEqualTo(multi);
        assertThat(refreshed.get(2)).isEqualTo(single);
        assertThat(read(mvc.perform(get("/api/requests/" + multi.path("id").asText()).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEqualTo(multi);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "73", "", "abc", "9999999999999999999999", "1e1"})
    void rejectsInvalidDurationWithoutPartialRows(String duration) throws Exception {
        var result = submit(login("member"), List.of("1", "2"), duration, 400);
        assertThat(result.path("field").asText()).isEqualTo("durationHours");
        assertThat(count("requests")).isZero();
        assertThat(count("queue_items")).isZero();
    }

    @Test
    void rejectsMissingOrRepeatedDuration() throws Exception {
        var session = login("member");
        mvc.perform(authorizedPost(session).param("gpuIds", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(authorizedPost(session).param("gpuIds", "1").param("durationHours", "1", "24"))
                .andExpect(status().isBadRequest());
        assertThat(count("requests")).isZero();
    }

    @Test
    void rejectsMissingUnknownInvalidAndDuplicateCardsBeforeAnyWrite() throws Exception {
        var session = login("member");
        for (var cards : List.of(List.<String>of(), List.of("1", "11"), List.of("1", "1"),
                List.of("1", "01"), List.of(""), List.of("no-card"), List.of("2147483648"))) {
            var result = submit(session, cards, "24", 400);
            assertThat(result.path("field").asText()).isEqualTo("gpuIds");
            assertThat(count("requests")).isZero();
            assertThat(count("queue_items")).isZero();
        }
    }

    @Test
    void keepsIntentionalRepeatedRequestsSeparateAndUsesSequenceForTiedTimes() throws Exception {
        var session = login("member");
        var first = submit(session, List.of("1", "2"), "24", 201);
        var tied = submit(session, List.of("1"), "24", 201);
        clock.now = BASE_TIME.plusSeconds(60);
        var later = submit(session, List.of("1"), "24", 201);
        assertThat(tied.path("id")).isNotEqualTo(first.path("id"));
        assertThat(tied.path("queueItems").get(0).path("id"))
                .isNotEqualTo(first.path("queueItems").get(0).path("id"));
        assertThat(tied.path("submittedAt")).isEqualTo(first.path("submittedAt"));
        assertThat(later.path("submittedAt").asText()).isEqualTo(clock.now.toString());
        assertThat(Long.parseLong(tied.path("receiveSeq").asText()))
                .isGreaterThan(Long.parseLong(first.path("receiveSeq").asText()));
        assertThat(Long.parseLong(later.path("receiveSeq").asText()))
                .isGreaterThan(Long.parseLong(tied.path("receiveSeq").asText()));
        assertThat(count("requests")).isEqualTo(3);
        assertThat(count("queue_items")).isEqualTo(4);
        var list = read(mvc.perform(get("/api/requests").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get(0)).isEqualTo(later);
        assertThat(list.get(1)).isEqualTo(tied);
        assertThat(list.get(2)).isEqualTo(first);
    }

    @Test
    void onlyReturnsOwnRequestsAndHidesMissingAndForeignDetailsWithTheSame404() throws Exception {
        var owner = login("member");
        var other = login("other");
        var own = submit(owner, List.of("1"), "24", 201);
        var foreign = submit(other, List.of("2"), "24", 201);
        var list = read(mvc.perform(get("/api/requests").session(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0)).isEqualTo(own);
        String hiddenBody = mvc.perform(get("/api/requests/" + foreign.path("id").asText()).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(get("/api/requests/" + UUID.randomUUID()).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(hiddenBody).isEqualTo(missingBody).isEmpty();
        mvc.perform(get("/api/requests/not-a-uuid").session(owner)).andExpect(status().isNotFound());
    }

    @Test
    void enforcesSessionsMemberRoleCsrfAndRejectsForgedApplicantFields() throws Exception {
        mvc.perform(get("/api/requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/requests/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/requests").param("gpuIds", "1").param("durationHours", "24"))
                .andExpect(status().isForbidden());
        var anonymousToken = mvc.perform(get("/api/csrf")).andReturn();
        var anonymousSession = (MockHttpSession) anonymousToken.getRequest().getSession(false);
        var token = read(anonymousToken.getResponse().getContentAsString());
        mvc.perform(post("/api/requests").session(anonymousSession)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("gpuIds", "1").param("durationHours", "24"))
                .andExpect(status().isUnauthorized());
        var admin = login("admin");
        submit(admin, List.of("1"), "24", 403);
        mvc.perform(get("/api/requests").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/requests/" + UUID.randomUUID()).session(admin)).andExpect(status().isForbidden());
        var member = login("member");
        mvc.perform(post("/api/requests").session(member).param("gpuIds", "1").param("durationHours", "24"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/requests").session(member).header("X-CSRF-TOKEN", "invalid")
                .param("gpuIds", "1").param("durationHours", "24")).andExpect(status().isForbidden());
        for (String field : List.of("memberId", "applicantId", "accountId", "role")) {
            mvc.perform(authorizedPost(member).param("gpuIds", "1").param("durationHours", "24")
                    .param(field, OTHER_MEMBER.toString())).andExpect(status().isBadRequest());
        }
        assertThat(count("requests")).isZero();
        submit(member, List.of("1"), "24", 201);
    }

    @Test
    void rollsBackTheHeaderAndEarlierCardsWhenALaterCardWriteFails() throws Exception {
        var session = login("member");
        var existing = submit(session, List.of("3"), "24", 201);
        jdbc.sql("ALTER TABLE queue_items ADD CONSTRAINT test_write_failure CHECK (gpu_id <> 2)").update();
        try {
            submit(session, List.of("1", "2"), "24", 503);
            assertThat(count("requests")).isEqualTo(1);
            assertThat(count("queue_items")).isEqualTo(1);
            assertThat(read(mvc.perform(get("/api/requests/" + existing.path("id").asText()).session(session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEqualTo(existing);
        } finally {
            jdbc.sql("ALTER TABLE queue_items DROP CONSTRAINT test_write_failure").update();
        }
        submit(session, List.of("1", "2"), "24", 201);
        assertThat(count("requests")).isEqualTo(2);
        assertThat(count("queue_items")).isEqualTo(3);
    }

    @Test
    void queryFailureReturns503InsteadOfAnEmptyList() throws Exception {
        var session = login("member");
        jdbc.sql("ALTER TABLE requests RENAME TO requests_unavailable").update();
        try {
            mvc.perform(get("/api/requests").session(session)).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/requests/" + UUID.randomUUID()).session(session))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            jdbc.sql("ALTER TABLE requests_unavailable RENAME TO requests").update();
        }
    }

    @Test
    void databaseConstraintsRejectInvalidDurationDuplicateCardAndMissingForeignKeys() throws Exception {
        var saved = submit(login("member"), List.of("1"), "24", 201);
        UUID id = UUID.fromString(saved.path("id").asText());
        assertThatThrownBy(() -> jdbc.sql("UPDATE requests SET duration_hours = 0 WHERE id = :id")
                .param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO queue_items (id, request_id, gpu_id, status) VALUES (:id, :requestId, 1, 'WAITING')
                """).param("id", UUID.randomUUID()).param("requestId", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO queue_items (id, request_id, gpu_id, status) VALUES (:id, :requestId, 999, 'WAITING')
                """).param("id", UUID.randomUUID()).param("requestId", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO queue_items (id, request_id, gpu_id, status) VALUES (:id, :requestId, 2, 'WAITING')
                """).param("id", UUID.randomUUID()).param("requestId", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("requests")).isEqualTo(1);
        assertThat(count("queue_items")).isEqualTo(1);
    }

    @Test
    void upgradeAndApplicationRestartKeepRecordsAndReceivingSequence() {
        String url = newDatabase("requests-restart-");
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        String originalPasswordHash = passwords.encode("restart-password");
        var v2Jdbc = JdbcClient.create(new DriverManagerDataSource(url, "sa", ""));
        seedAccount(v2Jdbc, MEMBER, "restart-member", "MEMBER", originalPasswordHash);
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        RequestRepository.RequestView before;
        try (var first = application(url)) {
            var originalAccount = first.getBean(AccountRepository.class).findByLoginName("restart-member").orElseThrow();
            assertThat(originalAccount.id()).isEqualTo(MEMBER);
            assertThat(originalAccount.displayName()).isEqualTo("restart-member测试成员");
            assertThat(originalAccount.role().name()).isEqualTo("MEMBER");
            assertThat(originalAccount.passwordHash()).isEqualTo(originalPasswordHash);
            assertThat(first.getBean(PasswordEncoder.class).matches("restart-password", originalAccount.passwordHash())).isTrue();
            before = first.getBean(AllocationService.class)
                    .submitOrdinaryRequest(originalAccount.id(), List.of("1", "2"), "72");
        }
        try (var second = application(url)) {
            var service = second.getBean(AllocationService.class);
            assertThat(service.findOwnRequest(MEMBER, UUID.fromString(before.id()))).contains(before);
            assertThat(service.listOwnRequests(MEMBER)).containsExactly(before);
            var after = service.submitOrdinaryRequest(MEMBER, List.of("1"), "1");
            assertThat(Long.parseLong(after.receiveSeq())).isGreaterThan(Long.parseLong(before.receiveSeq()));
            assertThat(service.listOwnRequests(MEMBER)).containsExactly(after, before);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext application(String url) {
        return new SpringApplicationBuilder(GpuQueueApplication.class).web(WebApplicationType.NONE)
                .run("--queue.jdbc-url=" + url + ";IFEXISTS=TRUE");
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
                """).param("id", id).param("loginName", loginName).param("displayName", loginName + "测试成员")
                .param("hash", hash).param("role", role).update();
    }

    private MockHttpSession login(String username) throws Exception {
        var csrfResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var token = read(csrfResult.getResponse().getContentAsString());
        var result = mvc.perform(post("/api/login").session(session)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .param("username", username).param("password", "integration-password"))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder authorizedPost(MockHttpSession session) throws Exception {
        var token = read(mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return post("/api/requests").session(session).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(token.path("headerName").asText(), token.path("token").asText());
    }

    private JsonNode submit(MockHttpSession session, List<String> gpuIds, String hours, int expected) throws Exception {
        var request = authorizedPost(session).param("durationHours", hours);
        if (!gpuIds.isEmpty()) request.param("gpuIds", gpuIds.toArray(String[]::new));
        String body = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? json.nullNode() : read(body);
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        Instant now = BASE_TIME;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
