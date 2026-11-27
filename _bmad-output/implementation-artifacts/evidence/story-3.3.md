# Story 3.3 验收证据：成员申请续期并由管理员审核

## 实现摘要
- **数据库迁移**：
  - `V12__renewal_requests.sql`：新增 `renewal_requests` 表（同卡复合外键 `(allocation_id, gpu_id)`、支持绝对到期时间与审批结论），扩展 `audit_logs` 与 `inbox_messages` 支持 `RENEWAL_APPROVED` / `RENEWAL_REJECTED`，并移除旧的 `audit_logs_allocation_action_uk` 与 `inbox_messages_allocation_type_uk` 以支持延期后二次逾期通知及同卡多次续期审批。
- **后端**：
  - `SecurityConfiguration.java`：新增 `POST /api/allocations/*/renewals`、`GET /api/renewals`（`MEMBER`）与 `GET /api/admin/renewals/pending`、`POST /api/admin/renewals/*/review`（`ADMIN`）。
  - `AllocationService.java`、`AllocationController.java`、`AdminAllocationController.java`、`AllocationRepository.java`：
    - 成员按 `allocation_id` 提交绝对新到期时间（须晚于当前到期时间且晚于当前时间，不设 72 小时上限），待审核不改变现有 `expires_at`。
    - 管理员带非空理由批准/拒绝：批准更新 `allocations.expires_at`（保留原 `allocated_at`/`started_at`/`not_started_anomaly_at`），同事务写 `audit_logs` 并发送站内消息；同结论重复审核幂等无副作用，反向改判或 `seenExpiresAt` 过期或已释放名额返回 `409`。
    - D039 边界：未开始短时判断始终以 `expires_at - allocated_at < 24h` 计算，24 小时阈值从原 `allocated_at` 计算；逾期名额批准续期至未来后恢复 `IN_USE`，新期限再次到期可触发新的 `OVERDUE` 通知。
- **前端**：
  - `frontend/src/api.ts`、`AdminRenewalReview.vue`、`App.vue`：管理员端新增待审核续期申请面板，支持调整/确认批准到期时间并填写理由批准或拒绝。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**46/46** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`RenewalIntegrationTest`）：
  - 逾期名额申请续期至 `+120h`（突破 72h 上限），批准后恢复 `IN_USE`，到达 `+120h` 再次产生新的 `OVERDUE` 通知；重复批准幂等、反向拒绝 `409`。
  - D039 边界：`6h -> 12h` 未开始在 12h 自动释放交接；`6h -> 24h` 未开始在 24h 标 `ANOMALY` 保留；未批准续期不暂停 6h 自动释放，释放后再批准返回 `409`。
  - 权限与隔离：他人名额申请续期 `404`、成员越权审批 `403`。
