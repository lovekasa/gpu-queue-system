# Story 2.4 实现与验证记录

日期：2026-11-26（Asia/Shanghai）。基于 `spec-2-4-confirm-return.md` 完成实现、前端生产构建与 33 项独立临时 H2 文件库集成测试（0 失败/错误/跳过）。证据不含口令、散列、CSRF 或 Session 值。

## 实现范围

- 基线：`3bffc868186cc9c757605e1c57d7c0041fa915d9`。未修改 `V1`–`V5`，未访问 `.local-data/dev`、`.local-data/demo`。
- `V6__allocation_return.sql`：为 `allocations` 增加 `ended_at` 与 `end_reason`（及一致性约束），扩展 `audit_logs` 的 `audit_logs_action_check` 支持 `'RETURNED'`。
- `AllocationService#confirmReturn`（`@Transactional(rollbackFor = Exception.class)`）：按 `allocation_id` 校验本人归属并锁对应 GPU 行，同事务结束原名额（`RETURNED`）、移除 `current_allocations`、写入 `MEMBER` 角色 `RETURNED` 审计，并在存在合法等待项时立即调用 `allocateGpuIfIdle` 完成原子交接；任一步骤失败整体回滚。重复归还或旧名额重放（含同成员再次持有同卡）返回 `alreadyEnded=true`，保留首次结束时刻且不影响后继名额。
- `AllocationController` 与 `SecurityConfiguration`：开放 `POST /api/allocations/{id}/return` 给 `MEMBER`（保留 CSRF 校验）。
- `frontend/src/api.ts` 与 `frontend/src/OrdinaryRequests.vue`：在“我的名额”为当前有效名额提供逐卡“确认完成并归还”按钮，在“我的申请”保留历史记录并展示 `已归还` 及上海时区结束时间。

## 构建与集成测试：实际通过

```powershell
npm.cmd --prefix frontend run build
mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify
```

- 前端：`vue-tsc --build` 与 `vite build` 零错误通过（23 个模块，CSS `2.70 kB`，JS `88.58 kB`）。
- 后端：Maven `verify` 零错误通过，合计 **33 tests，0 failures，0 errors，0 skipped**（`RequestIntegrationTest` 18 + `AllocationIntegrationTest` 8 + `AllocationStartIntegrationTest` 4 + `AllocationReturnIntegrationTest` 3），生成最新同源 `backend/target/gpu-queue.jar`。
