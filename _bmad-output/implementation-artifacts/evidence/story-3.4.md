# Story 3.4 验收证据：管理员直接延长名额期限

## 实现摘要
- **数据库迁移**：
  - `V13__admin_direct_extend.sql`：扩展 `audit_logs` 与 `inbox_messages` 支持 `ADMIN_EXTENDED`。
- **后端**：
  - `SecurityConfiguration.java`、`AdminAllocationController.java`、`AllocationService.java`、`AllocationRepository.java`：新增 `GET /api/admin/allocations/active` 与 `POST /api/admin/allocations/{id}/extend`（仅 `ADMIN` + CSRF + 非空理由）。
  - 无需成员提前提交续期申请；核对 `seenStatus` 与 `seenExpiresAt`（不符或名额已释放返回 `409`）；相同新到期时间幂等返回 `alreadyExtended: true` 且零副作用；禁止缩短或非延长（`400`），不设 72 小时上限；同事务更新 `expires_at`、写 `ADMIN_EXTENDED` 审计并发送站内通知。
- **前端**：
  - `frontend/src/api.ts`、`AdminDirectExtend.vue`、`App.vue`：管理员端新增直接延长名额期限面板（支持 `409` 冲突自动刷新）。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**47/47** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`AdminDirectExtendIntegrationTest`）：
  - 逾期名额无需成员申请直接延长至 `+100h`（突破 72h 上限），恢复 `IN_USE` 并同事务写审计与站内消息。
  - 重复相同期限幂等无副作用；过期 `seenExpiresAt` 返回 `409`；缩短期限/空理由返回 `400`；匿名 `401`、成员 `403`。
