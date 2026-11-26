---
title: 'Story 2.8 成员查看站内消息并标记已读'
type: 'feature'
epic: 2
story: '2.8'
story_key: 2-8-成员查看站内消息并标记已读
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

**Problem:** 成员目前能查看站内消息，但尚不支持未读/已读状态持久化及显式点击标记单条已读。

**Approach:** 新增 `V9__inbox_read_at.sql` 为 `inbox_messages` 增加 `read_at`，提供 `POST /api/messages/{id}/read`（仅 `MEMBER`、校验 CSRF 与本人归属，查询列表不自动标记已读，重复标记保留首次 `read_at`），并在前端“站内消息”面板展示未读/已读状态与单条“标记已读”按钮，落实 `epics.md` Story 2.8 与 `inbox-behavior.md` 验收（FR-015、AC-015-3、NFR-002、AD-3、5、8）。

</frozen-after-approval>

## Implementation Notes
