# Story 2.7 验收证据：成员查看各 GPU 的公开等待队列

## 实现摘要
- **后端**：
  - `SecurityConfiguration.java`：新增 `GET /api/queues` 允许已登录 `ADMIN` / `MEMBER` 访问，未登录返回 `401`。
  - `AllocationRepository.java`：新增 `listAllGpuIds()`、`findNamedWaitingCandidates(int gpuId)` 与仅含 `position`、`memberName` 的 `PublicQueueEntryView` / `PublicGpuQueueView`。
  - `AllocationService.java`：新增 `listPublicQueues()`，直接复用 `QueueOrderingPolicy.order(candidates, clock.instant())` 生成各卡公开等待顺序。
  - `AllocationController.java`：新增 `GET /api/queues`。
- **前端**：
  - `frontend/src/api.ts`：新增 `PublicQueueEntry`、`PublicGpuQueue`、`getPublicQueues()`。
  - `frontend/src/PublicQueues.vue`：新增各 GPU 公开等待队列视图，仅展示排队位置与成员名称，提供加载态、空队列态与失败重试态。
  - `frontend/src/App.vue`：已登录视图中挂载 `<PublicQueues />`。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**40/40** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`PublicQueueAndInboxReadIntegrationTest#publicQueuesExposeOnlyPositionAndMemberNameAndReflectOrderingAndPartialAllocation`）：
  - 匿名访问 `GET /api/queues` 返回 `401`，`MEMBER` 与 `ADMIN` 均可读取 10 张 GPU 的队列视图。
  - 字段严格隔离：每条 `waiting` 记录仅含 `position` 与 `memberName` 两个键，不暴露任何申请时长、提交时间、请求 ID、成员 ID 或分配 ID。
  - 同一成员重复排队显示为独立条目，部分分配后已获配卡从等待队列移除。
