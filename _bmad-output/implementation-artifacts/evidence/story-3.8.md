# Story 3.8 验收证据：试用前验证服务运行、备份和恢复

## 验证摘要
- **自动化端到端启停、重启补查与独立备份副本恢复验证**（`BackupAndRestoreIntegrationTest#persistsAcrossRestartAndRestoresIdenticalStateFromIndependentBackupCopy`）：
  1. **独立演示目录初始化与持久化**：在全新独立临时目录执行 `V1`–`V15` 全量 Flyway 版本化迁移并启动单实例 `serve` 模式，写入成员/管理员账号、普通申请、紧急申请、确认开始使用，生成 `allocations`、`current_allocations`、`queue_items`、`audit_logs` 与 `inbox_messages` 记录。
  2. **停服重启与扫描补查**：停止应用后在同目录重启并启用 `AllocationScanner` 补查，确认原有 `IN_USE` 名额、等待队列、审计与站内消息完整重读，且重复扫描不产生任何额外名额、审计或通知。
  3. **停服离线备份与独立副本恢复**：停止应用后复制 `gpu-queue.mv.db` 至独立恢复目录 `restoreDir`，原演示目录文件保持完整未改动；在 `restoreDir` 启动恢复实例，核对当前名额映射、状态、等待队列、待审紧急申请、审计历史与站内消息数量与原库完全一致。
- **全量构建与回归**：
  - `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**51/51** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
