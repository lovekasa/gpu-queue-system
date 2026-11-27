# Story 3.6 验收证据：管理员主动撤销名额并协调线下交接

## 实现摘要
- **数据库迁移**：
  - `V15__admin_revoke.sql`：扩展 `audit_logs` 与 `inbox_messages` 支持 `ADMIN_REVOKED`（`allocations.end_reason` 已在 `V6` 中支持 `REVOKED`）。
- **后端**：
  - `SecurityConfiguration.java`、`AdminAllocationController.java`、`AllocationService.java`：新增 `POST /api/admin/allocations/{id}/revoke`（仅 `ADMIN` + CSRF + 非空理由，无需任务完成证明或成员回执）。
  - 校验 `seenStatus`（变化返回 `409`）；结束原名额落 `end_reason = REVOKED`，写 `ADMIN_REVOKED` 审计并向原成员发送撤销及停止使用/线下交接通知，同事务按序交接给合法队首（新 `allocationId` 与独立期限）；重复提交幂等返回 `alreadyEnded: true` 且不影响后继名额。
- **前端**：
  - `frontend/src/api.ts`、`AdminDirectExtend.vue`、`OrdinaryRequests.vue`：增加主动撤销按钮与明确的线下交接提示（说明撤销后可立即重分配、原进程可能继续运行、须线下通知原成员停止使用并协调交接），成员端历史状态展示“管理员主动撤销”。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**49/49** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`AdminRevokeIntegrationTest`）：
  - `IN_USE` 名额主动撤销落 `REVOKED` + `ADMIN_REVOKED` 审计与通知，队首以新 `allocationId` 获配，原成员其他卡不受影响；重复撤销幂等无副作用；空理由 `400`、`seenStatus` 过期 `409`、成员越权 `403`。
