# Story 2.8 验收证据：成员查看站内消息并标记已读

## 实现摘要
- **数据库迁移**：
  - `V9__inbox_read_at.sql`：为 `inbox_messages` 新增 `read_at TIMESTAMP(9) WITH TIME ZONE`（可空，默认未读）。
- **后端**：
  - `SecurityConfiguration.java`：新增 `POST /api/messages/*/read`（仅限 `MEMBER` + CSRF）。
  - `AllocationRepository.java`：`InboxMessageView` 新增 `@JsonInclude(JsonInclude.Include.NON_NULL)` 的 `readAt` 字段（保持未读 JSON 结构向后兼容）；新增 `markMessageRead(UUID memberId, UUID messageId, Instant readAt)`。
  - `AllocationService.java`：新增 `markMessageRead(UUID memberId, UUID messageId)` 事务方法，显式标记已读且重复调用幂等保留首次 `read_at`。
  - `AllocationController.java`：新增 `POST /api/messages/{id}/read`。
- **前端**：
  - `frontend/src/api.ts`：`InboxMessage` 新增可选 `readAt`，新增 `MarkMessageReadResult` 与 `markInboxMessageRead(messageId)`。
  - `frontend/src/OrdinaryRequests.vue`：站内消息列表展示 `未读` / `已读（北京时间）` 状态，并为未读消息提供显式 `标记已读` 按钮。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**40/40** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`PublicQueueAndInboxReadIntegrationTest#inboxMessagesStayUnreadUntilExplicitMarkReadAndPreserveFirstReadTimestamp`）：
  - `GET /api/messages` 与 `GET /api/messages/{id}` 不会自动标记已读（`readAt` 缺失）。
  - 首次 `POST /api/messages/{id}/read` 写入 UTC `readAt` 并返回 `alreadyRead: false`，不修改审计日志或其他实体。
  - 推进时钟后再次调用返回 `alreadyRead: true` 并保留首次 `readAt`。
  - 权限与隔离：匿名（带 CSRF）`401`、缺 CSRF `403`、`ADMIN` `403`、他人消息/不存在 UUID/非法 UUID 均 `404`。
