---
title: 'Story 2.7 成员查看各 GPU 的公开等待队列'
type: 'feature'
epic: 2
story: '2.7'
story_key: 2-7-成员查看各-gpu-的公开等待队列
created: '2026-11-26'
status: 'done'
approved: '2026-11-26'
route: 'oneshot'
baseline_commit: 3e50a14720a64ac1af479d195224d21fea5ef1b2
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 成员目前只能看到自己的申请和名额，尚不能查看各 GPU 当前在排人员的公开姓名与顺序。

**Approach:** 提供 `GET /api/queues`（已登录成员与管理员可读，匿名 `401`），与分配共用 `QueueOrderingPolicy` 与可注入 UTC 时钟，按卡返回严格仅含 `position` 与 `memberName` 的公开等待列表，并在前端挂载 `PublicQueues.vue`，落实 `epics.md` Story 2.7 验收（FR-018、AC-018-1–2、NFR-002、AD-6、8）。

</frozen-after-approval>

## Implementation Notes
