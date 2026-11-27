# Story 3.1 验收证据：成员提交紧急申请并由管理员审核

## 实现摘要
- **数据库迁移**：
  - `V10__emergency_requests.sql`：为 `requests` 新增 `request_type`（`ORDINARY`/`EMERGENCY`）、`emergency_status`（`PENDING`/`APPROVED`/`REJECTED`）、`emergency_reviewed_at`、`emergency_reviewed_by`、`emergency_review_reason` 及一致性约束；允许 `audit_logs` 与 `inbox_messages` 的 `allocation_id`/`gpu_id`/`queue_item_id` 为空以支持申请级紧急审批记录，并扩展 `EMERGENCY_APPROVED`/`EMERGENCY_REJECTED` 类型。
- **后端**：
  - `QueueOrderingPolicy.java`：新增 `approvedEmergency` 优先级组——严格 `> 72h` 等待提升组 > 已批准紧急组 > 普通组（含待审核/已拒绝紧急申请），组内按 `submitted_at ASC, receive_seq ASC`；公开队列与实际分配共享同一策略。
  - `RequestController.java`：允许可选 `emergency=true|false` 表单字段，拒绝任何伪造的 `emergencyStatus` 等越权字段（`400`）。
  - `AllocationService.java` & `AdminAllocationController.java` & `SecurityConfiguration.java`：新增 `GET /api/admin/requests/emergency-pending` 与 `POST /api/admin/requests/{id}/emergency-review`（仅 `ADMIN` + CSRF + 非空理由），同事务更新审核状态、写 `audit_logs` 并发送站内消息；同结论重复审核幂等返回 `alreadyReviewed: true` 且无副作用，反向改判返回 `409`；已获配卡保持原状不受影响。
- **前端**：
  - `frontend/src/api.ts`、`OrdinaryRequests.vue`：申请表单新增紧急申请勾选框，我的申请卡片区分展示 `紧急待审核`/`紧急已批准`/`紧急已拒绝（含理由）`，站内消息兼容无 `gpuId`/`allocationId` 的申请级通知。
  - `frontend/src/AdminEmergencyReview.vue` & `App.vue`：管理员端新增待审核紧急申请面板，支持填写理由后批准或拒绝。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**42/42** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`EmergencyRequestIntegrationTest`）：
  - 待审紧急申请按普通规则排队并支持部分获配；管理员批准后仅影响尚在等待的卡，已获配卡不变。
  - 72 小时严格边界：恰好 72h 不越过已批准紧急项，72h + 1s 越过已批准紧急项，且公开队列顺序与实际归还后分配结果一致，公开队列严格仅含 `position` 与 `memberName` 两字段。
  - 拒绝紧急待遇保留原普通队列顺序与已获配名额，并向申请人发送含理由的站内消息。
  - 重复同结论审核幂等无副作用，反向改判返回 `409`；伪造 `emergencyStatus=APPROVED` 返回 `400`；未登录 `401`、成员越权 `403`、空理由 `400`、不存在申请 `404`。
