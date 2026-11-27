# Story 3.2 验收证据：管理员调整当前等待项的顺序

## 实现摘要
- **数据库迁移**：
  - `V11__queue_reorders.sql`：新增 `queue_reorders` 表（同卡复合外键、防自锚定、至少一个前后锚点约束），扩展 `audit_logs` 与 `inbox_messages` 支持 `QUEUE_REORDERED`，并将 `inbox_messages` 唯一约束更新为 `(transition_seq, recipient_id)` 以支持同一次调序向目标成员及受影响在排成员各发送一条通知。
- **后端（AD-7 移动重放算法）**：
  - `QueueOrderingPolicy.java`：新增 `order(candidates, rules, evaluationTime)`，先建基础排序（`>72h` 提升 > 已批准紧急 > 普通），再按 `queue_reorders.id ASC` 重放（原后继仍在则移至其紧前，否则移至原前驱紧后，两者均不在则不动；离队目标不参与重放）；公开队列与实际分配共享同一重放结果。
  - `AllocationService.java` & `AdminAllocationController.java` & `SecurityConfiguration.java`：新增 `GET /api/admin/queues` 与 `POST /api/admin/queues/{gpuId}/reorder`（仅 `ADMIN` + CSRF + 非空理由），校验所见队列快照 `seenQueueItemIds`（变化返回 `409`），同位置无操作不落库/审计/消息，有效移动同事务写 `queue_reorders`、前后顺序 `audit_logs` 并向目标成员与受影响在排成员发送 `QUEUE_REORDERED` 通知。
- **前端**：
  - `frontend/src/api.ts`、`AdminQueueReorder.vue`、`App.vue`：管理员端新增各 GPU 等待队列调序面板，展示 `queueItemId`/序号/时长/提升或紧急标记，支持填写目标位置与理由提交并在 `409` 时自动刷新。

## 自动化验证
- **前端构建**：`npm.cmd --prefix frontend run build` 通过（`vue-tsc --build` + `vite build`，0 errors）。
- **后端全量集成测试**：`mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` 通过（**44/44** 用例全部通过，0 Failures, 0 Errors, 0 Skipped，`backend/target/gpu-queue.jar` 重新打包成功）。
- **专项测试覆盖**（`QueueReorderIntegrationTest`）：
  - 跨组调序突破 `>72h` 等待提升组，公开队列与归还后实际分配候选完全一致，且公开队列严格仅暴露 `position` 与 `memberName`。
  - 原位置移动返回 `changed: false` 且零审计/零消息；有效移动向全部受影响成员各发送 1 条通知并记录前后顺序审计。
  - 过期 `seenQueueItemIds` 快照返回 `409`；目标获配离队后，同一成员新申请不继承原调序规则。
  - 权限与校验：未登录 `401`、成员 `403`、空理由/非法位置/越界位置 `400`、不存在 GPU `404`。
