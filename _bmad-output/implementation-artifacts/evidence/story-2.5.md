# Story 2.5 实现与验证记录

日期：2026-11-26（Asia/Shanghai）。基于 `spec-2-5-expiry-and-anomaly.md` 完成实现、前端生产构建与 36 项独立临时 H2 文件库集成测试（0 失败/错误/跳过）。

## 实现范围

- 基线：`02d5e767268c9811616f78d252220d2346d3b45f`。未修改 `V1`–`V6`。
- `V7__allocation_expiry.sql`：为 `allocations` 增加 `not_started_anomaly_at`、`overdue_notified_expires_at`，扩展 `audit_logs` 支持 `UNSTARTED_EXPIRED`、`MARKED_ANOMALY`、`MARKED_OVERDUE`，并将 `inbox_messages` 唯一约束由 `(allocation_id)` 演进为 `(allocation_id, message_type)`（允许同一名额依次接收获配与到期/异常/逾期消息且同类防重）。
- `AllocationService#processExpiryForAllocation` 与 `AllocationScanner#scanIdleGpus`：统一由每 60 秒定时扫描与启动补查调用，先锁对应 GPU 并按最新状态与批准跨度处理三类分支（不足 24h 未开始到期自动释放并交接后继；>=24h 满 24h 未开始标异常保留映射；已开始到期标逾期保留映射），同事务写审计与站内消息，重复扫描无副作用，异常后确认开始保留历史 `not_started_anomaly_at`。
- 前端 `api.ts` 与 `OrdinaryRequests.vue`：支持 `未开始异常（待核实）`、`已逾期（保留名额）`、`未开始到期已释放` 状态显示，并允许异常名额确认开始。

## 构建与集成测试：实际通过

- 前端：`npm.cmd --prefix frontend run build` 零错误通过（CSS `2.70 kB`，JS `88.75 kB`）。
- 后端：离线 Maven `verify` 合计 **36 tests，0 failures，0 errors，0 skipped**（`RequestIntegrationTest` 18 + `AllocationIntegrationTest` 8 + `AllocationStartIntegrationTest` 4 + `AllocationReturnIntegrationTest` 3 + `AllocationExpiryIntegrationTest` 3），生成最新同源 `backend/target/gpu-queue.jar`。
