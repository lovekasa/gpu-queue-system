# Story 3.5 验收证据：管理员带理由代成员归还名额

## 实现摘要
- **数据库迁移**：
  - `V14__admin_proxy_return.sql`：扩展 `audit_logs` 与 `inbox_messages` 支持 `ADMIN_PROXY_RETURNED`。
- **后端**：
  - `SecurityConfiguration.java`、`AdminAllocationController.java`、`AllocationService.java`：新增 `POST /api/admin/allocations/{id}/proxy-return`（仅 `ADMIN` + CSRF + `verified=true` + 非空理由）。
  - 支持任意有效名额（不限于异常或逾期）；核对 `seenStatus`（变化返回 `409`）；结束原名额并记录 `end_reason = RETURNED`，同时以 `actor_type = ADMIN`、`action = ADMIN_PROXY_RETURNED` 明确真实操作者与理由，不伪造成成员本人操作；同事务发送站内通知并立即按序交接给合法队首；已结束名额重复提交幂等返回 `alreadyEnded: true` 且不影响后继名额。
- **前端**：
  - `frontend/src/api.ts`、`AdminDirectExtend.vue`：在当前有效名额列表中增加“已线下核实完成/可释放”勾选与“代成员归还”按钮（支持 `409` 冲突自动刷新）。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**48/48** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`AdminProxyReturnIntegrationTest`）：
  - 正常 `IN_USE` 名额代归还后落 `RETURNED` + `ADMIN_PROXY_RETURNED` 审计/通知，同成员其他卡不受影响，同成员在排第二份申请以新 `allocationId` 获配；重放旧 `allocationId` 幂等返回 `alreadyEnded: true` 且不影响新名额。
  - 缺核实/空理由 `400`、`seenStatus` 过期 `409`、成员越权 `403`。
