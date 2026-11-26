---
title: 'Story 2.3 成员按卡确认开始使用'
type: 'feature'
epic: 2
story: '2.3'
story_key: 2-3-成员按卡确认开始使用
created: '2026-11-26'
status: 'done'
approved: '2026-11-26'
route: 'oneshot'
baseline_commit: 38520dd91ec9ff66d67950d9b375900852d2930f
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.2 已交付按卡自动分配与本人名额查看，但成员尚不能对已获配的指定 GPU 在网页逐卡确认开始使用，无法记录首次 `started_at` 与成员使用声明。

**Approach:** 新增 `V5__allocation_start.sql` 为 `allocations` 增加 `started_at` 并扩展 `audit_logs` 动作枚举，通过 `AllocationService` 唯一事务入口提供 `POST /api/allocations/{id}/start`（校验 Session、`MEMBER` 角色、CSRF、本人归属与 `current_allocations` 当前有效映射），并在前端“我的名额”与“我的申请”展示逐卡确认按钮和开始时间，落实 `epics.md` Story 2.3 全部 7 组验收（FR-008、FR-009、FR-017、NFR-001、NFR-002，AD-1–5、8）。

## Boundaries & Constraints

**Always:**
- 所有确认开始写入必须经 `AllocationService`（`@Transactional(rollbackFor = Exception.class)`），先锁对应 GPU 行，校验本人归属与 `current_allocations` 当前映射指向原 `allocation_id`，同事务保存首次 `started_at`（可注入 UTC `Clock`）与 `audit_logs`（`actor_type='MEMBER'`、`action='STARTED'`），失败整体回滚。
- 确认开始不重置 `expires_at`、不延期、不更换 `allocation_id`，单卡确认不影响同申请其他获配卡或等待卡。
- 重复确认已开始的有效名额返回 `200`（`alreadyStarted=true`），保留首次 `started_at`，不重复生成审计或消息；已结束/不在 `current_allocations` 的旧名额（含同人两轮获配）返回 `409 Conflict`，不恢复旧名额、不影响当前后继名额。
- 匿名返回 `401`，缺/错 CSRF 或管理员返回 `403`，他人名额、等待项 ID、不存在或非法 UUID 统一返回空体 `404`。

**Never:**
- 不修改 `V1`–`V4` 迁移；不预建归还（2.4）、到期/24h 异常（2.5）、核实释放（2.6）或远程任务控制功能。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 首次按卡确认开始 | `POST /api/allocations/{id}/start`（本人当前有效未开始名额） | `200`：`alreadyStarted=false`，保存 UTC `startedAt`，`status=IN_USE`，`expiresAt` 与同申请其他卡不变，写入 1 条 `STARTED` 审计 | 审计或数据库失败整体回滚并返回空体 `503` |
| 重复确认开始 | 对已开始的有效名额再次 `POST /api/allocations/{id}/start` | `200`：`alreadyStarted=true`，保留首次 `startedAt`，无新增审计或消息 | N/A |
| 旧名额隔离 | A101 已不在 `current_allocations`，同卡已分配 A102（含同人两轮） | `409 Conflict`，不恢复 A101，A102 身份/开始时间/期限不变，无新增审计 | 返回明确业务冲突 JSON |
| 权限、CSRF 与归属隔离 | 匿名、缺/错 CSRF、管理员、他人名额、等待项 ID、非法 UUID | 匿名 `401`、CSRF/管理员 `403`、他人/等待项/不存在/非法 UUID 空体 `404` | 不泄露他人名额存在性 |

</frozen-after-approval>

## Implementation Notes

- 2026-11-26：完成 `V5__allocation_start.sql`、`AllocationService#confirmStart`、`POST /api/allocations/{id}/start`、安全过滤链授权、前端逐卡确认开始按钮与 `startedAt` 展示，以及 `AllocationStartIntegrationTest`（4 项）。`npm run build`（JS `87.04 kB`）与离线 Maven `verify` 合计 30 项测试全部通过（0 失败/错误/跳过），生成最新 `gpu-queue.jar`，证据见 [evidence/story-2.3.md](evidence/story-2.3.md)。

## Verification

**Commands:**
- `npm.cmd --prefix frontend run build` -- expected: `vue-tsc --build` 与 Vite 构建零错误通过
- `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` -- expected: `RequestIntegrationTest`、`AllocationIntegrationTest` 与 `AllocationStartIntegrationTest` 全部通过
