# Story 3.7 验收证据：管理员追溯申请与名额的干预历史

## 实现摘要
- **后端**：
  - `SecurityConfiguration.java`、`AdminAllocationController.java`、`AllocationService.java`、`AllocationRepository.java`：新增 `GET /api/admin/audit-logs`（仅 `ADMIN`），支持按 `requestId` 与 `allocationId` 可选筛选，按 `transition_seq ASC` 返回完整持久化审计记录（含 `occurredAt`、`actorType`、`actorAccountId`、`actorDisplayName`、`action`、`requestId`、`queueItemId`、`allocationId`、`gpuId`、`reason`、`beforeState`、`afterState`）。
  - 查询严格只读，不产生任何新记录；非法 UUID 返回 `400`，无匹配返回空列表。
- **前端**：
  - `frontend/src/api.ts`、`AdminAuditHistory.vue`、`App.vue`：管理员端新增干预历史追溯面板，支持按申请 ID 或名额 ID 筛选并展示每条变更的操作者、时间、理由与前后状态。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**50/50** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`AdminAuditHistoryIntegrationTest`）：
  - 同一名额先后经历 `ADMIN_EXTENDED` 与 `ADMIN_REVOKED`，按 `allocationId` 查询严格按提交顺序返回 3 条记录（含 `ALLOCATED`），且与同成员后继新名额完全隔离。
  - 按 `requestId` 查询返回一致记录，查询前后 `audit_logs` 总数不变；未知 UUID 返回空列表；匿名 `401`、成员 `403`、非法 UUID `400`。
