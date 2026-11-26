# Story 2.6 实现与验证记录

日期：2026-11-26（Asia/Shanghai）。基于 `spec-2-6-admin-verified-release.md` 完成实现、前端生产构建与 38 项独立临时 H2 文件库集成测试（0 失败/错误/跳过）。

## 实现范围

- 基线：`26ef2a8d92de7ab6925d32f3eece42c44ef5d815`。未修改 `V1`–`V7`。
- `V8__admin_verified_release.sql`：扩展 `audit_logs` 与 `inbox_messages` 支持 `'VERIFIED_RELEASED'`。
- `AllocationService#listPendingVerificationAllocations`、`AllocationService#adminVerifiedRelease` 与 `AdminAllocationController`：提供仅 `ADMIN` 可访问的 `GET /api/admin/allocations/pending-verification` 和 `POST /api/admin/allocations/{id}/verified-release`（校验 CSRF、非空理由、核实勾选与 `seenStatus` 冲突 `409`）；同事务结束原名额、移除当前映射、写入带真实管理员 ID 与理由的审计、通知原持有者并分配给合法队首；任一写入失败整体回滚。
- 前端 `AdminVerifiedRelease.vue`、`App.vue`、`api.ts`、`OrdinaryRequests.vue`：在管理员页面挂载异常/逾期核实释放面板（含核实勾选、理由必填及 `409` 自动刷新提示），并在成员页面支持 `管理员核实已释放` 状态显示。

## 构建与集成测试：实际通过

- 前端：`npm.cmd --prefix frontend run build` 零错误通过（CSS `3.30 kB`，JS `93.31 kB`）。
- 后端：离线 Maven `verify` 合计 **38 tests，0 failures，0 errors，0 skipped**（含 `AdminVerifiedReleaseIntegrationTest` 2 项），生成最新同源 `backend/target/gpu-queue.jar`。
