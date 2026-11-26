# Story 2.3 实现与验证记录

日期：2026-11-26（Asia/Shanghai）。基于 `spec-2-3-confirm-start.md` 完成实现、前端生产构建与 30 项独立临时 H2 文件库集成测试（0 失败/错误/跳过）。证据不含口令、散列、CSRF 或 Session 值。

## 实现范围

- 基线：`38520dd91ec9ff66d67950d9b375900852d2930f`，分支 `codex/story-2-3`。未修改 `V1`–`V4`，未访问 `.local-data/dev`、`.local-data/demo`。
- `V5__allocation_start.sql`：为 `allocations` 增加 `started_at TIMESTAMP(9) WITH TIME ZONE`，并扩展 `audit_logs` 的 `audit_logs_action_check` 支持 `'STARTED'`（保留 `audit_logs_allocation_action_uk` 防止重复 `STARTED` 审计）。
- `AllocationService#confirmStart`（`@Transactional(rollbackFor = Exception.class)`）：先校验归属并锁对应 GPU 行，确认 `current_allocations` 仍指向目标 `allocation_id`，同事务保存首次 UTC `started_at` 与 `MEMBER` 角色 `STARTED` 审计记录；不重置 `expires_at` 或 `allocation_id`；重复确认返回 `alreadyStarted=true` 且保留首次 `started_at` 零额外审计/消息；旧已结束名额返回 `409 Conflict`（`ALLOCATION_ENDED`）不影响后继名额。
- `AllocationController` 与 `SecurityConfiguration`：开放 `POST /api/allocations/{id}/start` 给 `MEMBER`（保留 CSRF 校验），匿名返回 `401`，缺/错 CSRF 或管理员返回 `403`，他人名额、等待项 ID、不存在或非法 UUID 返回空体 `404`，数据库/事务失败返回空体 `503`。
- `frontend/src/api.ts` 与 `frontend/src/OrdinaryRequests.vue`：支持 `IN_USE`（`已开始使用`）与 `startedAt`（`Asia/Shanghai`）展示，在“我的名额”为未开始名额提供逐卡“确认开始使用”按钮与明确反馈，在“我的申请”同步显示已开始卡的开始时间。

## 构建与集成测试：实际通过

```powershell
npm.cmd --prefix frontend run build
mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify
```

- 前端：`vue-tsc --build` 与 `vite build` 零错误通过（23 个模块，CSS `2.70 kB`，JS `87.04 kB`）。
- 后端：Maven `verify` 零错误通过，合计 **30 tests，0 failures，0 errors，0 skipped**（`RequestIntegrationTest` 18 + `AllocationIntegrationTest` 8 + `AllocationStartIntegrationTest` 4），并生成包含最新前端资源的 `backend/target/gpu-queue.jar`。

## 七组验收跟踪（`epics.md` Story 2.3）

| `epics.md` Story 2.3 验收组 | 关联编号 | 实际结果 |
| --- | --- | --- |
| 1. 本人有效未开始名额确认开始并同事务落审计 | FR-009 / AC-009-1；AD-1、2、5 | `AllocationStartIntegrationTest#confirmsStartPerCardWithoutResettingExpiryOrAffectingOtherCards` 与审计失败回滚用例通过 |
| 2. 多卡隔离，单卡开始不影响同申请其他卡或等待项 | FR-009 / AC-009-2；FR-017 | 确认 GPU 2 开始后 GPU 3 仍 `UNSTARTED`、GPU 4 仍 `WAITING`；对等待项 ID 调用 `/start` 返回空体 `404` |
| 3. `started_at` 使用 UTC 时钟且不重置到期时间或 `allocation_id` | FR-008；AD-4 | 推进时钟 2 小时后确认开始，`expiresAt` 与 `allocationId` 保持不变，API 带 `Z`、页面上海时区 |
| 4. 重复确认已开始名额保留首次 `started_at` 且无重复副作用 | AD-3、5 | 再次推进时钟重复确认返回 `200`（`alreadyStarted=true`），首次 `startedAt` 保持，审计/消息数不变 |
| 5. 旧已结束名额（含同人两轮）迟到请求返回冲突且不影响后继 | NFR-001；AD-3 | A101 移除当前映射并由同成员获配 A102 后，A101 `/start` 返回 `409`，A102 状态/期限不变、无新增审计 |
| 6. 匿名、CSRF、管理员与他人名额越权拒绝 | FR-001；NFR-002；AD-8 | `enforcesAuthCsrfRoleOwnershipAndRollsBackOnAuditFailure` 全覆盖通过 |
| 7. 独立 H2 文件库与 `V4→V5` 升级验证 | AR-16、19 | `upgradesFromV4PreservingExistingAllocationsAndAllowsConfirmingStart` 通过；本轮完成自动化集成验证，手动浏览器演示可按 `runbook.md` 复现 |
