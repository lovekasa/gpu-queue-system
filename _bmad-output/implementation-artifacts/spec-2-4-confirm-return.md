---
title: 'Story 2.4 成员按卡确认完成并归还名额'
type: 'feature'
epic: 2
story: '2.4'
story_key: 2-4-成员按卡确认完成并归还名额
created: '2026-11-26'
status: 'done'
approved: '2026-11-26'
route: 'oneshot'
baseline_commit: 3bffc868186cc9c757605e1c57d7c0041fa915d9
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.3 已交付按卡确认开始，但成员完成后尚不能逐卡归还使用权，无法立即释放当前映射并按队列顺序自动交接给下一合法等待项。

**Approach:** 新增 `V6__allocation_return.sql` 为 `allocations` 增加 `ended_at` 与 `end_reason` 并扩展 `audit_logs` 支持 `'RETURNED'`，通过 `AllocationService#confirmReturn` 唯一事务入口提供 `POST /api/allocations/{id}/return`：同事务结束原名额、移除 `current_allocations`、写入归还审计并立即按现有排序策略为该卡合法队首分配新名额（任一步骤失败整体回滚），并在前端“我的名额”提供逐卡归还按钮与历史状态展示，落实 `epics.md` Story 2.4 全部验收（FR-006、FR-011、FR-015–017，NFR-001–002，AD-1–6、8）。

## Boundaries & Constraints

**Always:**
- 归还与存在合法等待项时的后继分配（含新 `allocation_id`、当前映射、审计与获配站内消息）必须在同一个 `@Transactional(rollbackFor = Exception.class)` 事务中提交；任一必需写入失败整体回滚并返回空体 `503`。
- 归还按 `allocation_id` 校验本人归属与当前映射，无需管理员审批；单卡归还不影响同申请其他获配卡或等待卡。
- 对已结束的本人名额重复归还或重放旧归还请求（含同一成员再次获配该卡的情况），返回 `200`（`alreadyEnded=true`），保留首次 `ended_at`，不复活旧名额、不修改后继名额、不产生重复审计/消息/分配。
- 匿名 `401`、缺/错 CSRF 或管理员 `403`、他人名额/等待项/不存在/非法 UUID 空体 `404`。

**Never:**
- 不修改 `V1`–`V5` 迁移；不预建自动到期/24h 异常（2.5）、管理员核实释放（2.6）或远程进程终止能力。

</frozen-after-approval>

## Implementation Notes

- 2026-11-26：完成 `V6__allocation_return.sql`、`AllocationService#confirmReturn`（同事务归还与合法队首交接）、`POST /api/allocations/{id}/return`、前端逐卡归还按钮与已归还历史展示，以及 `AllocationReturnIntegrationTest`（3 项）。`npm run build`（JS `88.58 kB`）与离线 Maven `verify` 合计 33 项测试全部通过（0 失败/错误/跳过），详见 [evidence/story-2.4.md](evidence/story-2.4.md)。

## Verification

**Commands:**
- `npm.cmd --prefix frontend run build` -- expected: `vue-tsc --build` 与 Vite 构建零错误通过
- `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` -- expected: 全部集成测试（含 `AllocationReturnIntegrationTest`）零失败通过
