package org.lab.gpuqueue.allocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.lab.gpuqueue.GpuQueueApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BackupAndRestoreIntegrationTest {

    @Test
    void persistsAcrossRestartAndRestoresIdenticalStateFromIndependentBackupCopy() throws Exception {
        Path demoDir = Files.createTempDirectory("gpu-queue-story-3-8-demo-");
        Path restoreDir = Files.createTempDirectory("gpu-queue-story-3-8-restore-");

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID allocId;
        int auditCountAfterWrite;
        int messageCountAfterWrite;

        // 1. Versioned Flyway migration (V1..V15) + fresh start in demoDir, seed accounts, submit requests & start.
        String demoUrl = "jdbc:h2:file:" + demoDir.resolve("gpu-queue").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure().dataSource(demoUrl, "sa", "").load().migrate();
        try (var ctx = GpuQueueApplication.startMode(
                "serve", demoDir, "--server.port=0", "--queue.scanner.enabled=false")) {
            var jdbc = ctx.getBean(JdbcClient.class);
            var encoder = ctx.getBean(PasswordEncoder.class);
            var service = ctx.getBean(AllocationService.class);
            String hash = encoder.encode("demo-pass");

            seedAccount(jdbc, aliceId, "alice", "MEMBER", hash);
            seedAccount(jdbc, bobId, "bob", "MEMBER", hash);
            seedAccount(jdbc, adminId, "admin", "ADMIN", hash);

            var r1 = service.submitOrdinaryRequest(aliceId, List.of("1"), "24", false);
            allocId = UUID.fromString(r1.queueItems().get(0).allocationId());
            service.submitOrdinaryRequest(bobId, List.of("1"), "12", true);
            service.confirmStart(aliceId, allocId);

            auditCountAfterWrite = jdbc.sql("SELECT COUNT(*) FROM audit_logs").query(Integer.class).single();
            messageCountAfterWrite = jdbc.sql("SELECT COUNT(*) FROM inbox_messages").query(Integer.class).single();
            assertThat(auditCountAfterWrite).isGreaterThanOrEqualTo(2);
            assertThat(messageCountAfterWrite).isGreaterThanOrEqualTo(1);
        }

        // 2. Restart in demoDir with scanner catch-up enabled: existing records reload, catch-up creates zero duplicates.
        try (var ctx = GpuQueueApplication.startMode(
                "serve", demoDir, "--server.port=0", "--queue.scanner.initial-delay-ms=86400000")) {
            var jdbc = ctx.getBean(JdbcClient.class);
            var service = ctx.getBean(AllocationService.class);
            var scanner = ctx.getBean(AllocationScanner.class);
            scanner.scanIdleGpus();

            assertThat(service.findOwnAllocation(aliceId, allocId)).isPresent();
            assertThat(service.findOwnAllocation(aliceId, allocId).orElseThrow().status()).isEqualTo("IN_USE");
            assertThat(service.listPublicQueues().get(0).waiting()).hasSize(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs").query(Integer.class).single())
                    .isEqualTo(auditCountAfterWrite);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inbox_messages").query(Integer.class).single())
                    .isEqualTo(messageCountAfterWrite);
        }

        // 3. Offline backup: copy H2 database file from demoDir to independent restoreDir.
        Path sourceDb = demoDir.resolve("gpu-queue.mv.db");
        Path backupDb = restoreDir.resolve("gpu-queue.mv.db");
        assertThat(Files.exists(sourceDb)).isTrue();
        Files.copy(sourceDb, backupDb, StandardCopyOption.REPLACE_EXISTING);

        // 4. Start restored instance against restoreDir: all allocations, queues, audits, and messages match identically.
        try (var restoredCtx = GpuQueueApplication.startMode(
                "serve", restoreDir, "--server.port=0", "--queue.scanner.enabled=false")) {
            var jdbc = restoredCtx.getBean(JdbcClient.class);
            var service = restoredCtx.getBean(AllocationService.class);

            var restoredAlloc = service.findOwnAllocation(aliceId, allocId).orElseThrow();
            assertThat(restoredAlloc.status()).isEqualTo("IN_USE");
            assertThat(service.listPublicQueues().get(0).waiting()).hasSize(1);
            assertThat(service.listPendingEmergencyRequests()).hasSize(1);
            assertThat(service.listAuditLogs(null, allocId)).hasSize(2);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs").query(Integer.class).single())
                    .isEqualTo(auditCountAfterWrite);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inbox_messages").query(Integer.class).single())
                    .isEqualTo(messageCountAfterWrite);
        }

        // Original demoDir database file remains intact.
        assertThat(Files.exists(sourceDb)).isTrue();
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
}
