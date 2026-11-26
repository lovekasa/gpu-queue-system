---
title: 'Story 2.6 管理员核实异常或逾期后释放名额'
type: 'feature'
epic: 2
story: '2.6'
story_key: 2-6-管理员核实异常或逾期后释放名额
created: '2026-11-26'
status: 'done'
approved: '2026-11-26'
route: 'oneshot'
baseline_commit: 26ef2a8d92de7ab6925d32f3eece42c44ef5d815
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.5 已将满 24h 未开始名额标为异常、已开始到期名额标为逾期并保留映射，但管理员尚无法在核实后带理由释放这些名额并交接给下一位等待成员。

**Approach:** 新增 `V8__admin_verified_release.sql` 扩展审计与消息类型，在 `AllocationService` 提供管理员待核实列表查询与 `adminVerifiedRelease` 唯一事务入口，暴露 `GET /api/admin/allocations/pending-verification` 与 `POST /api/admin/allocations/{id}/verified-release`（仅 `ADMIN`、校验 CSRF、非空理由、核实确认及 `seenStatus` 冲突 `409`），并在管理员页面挂载核实释放面板，落实 `epics.md` Story 2.6 验收（FR-011、013、015、016，NFR-001–003，AD-1–6、8）。

## Boundaries & Constraints

**Always:**
- 仅 `ADMIN` 可访问；普通成员与匿名均拒绝；缺少理由、未勾选核实确认或非法 `seenStatus` 返回 `400`。
- 目标仍有效但当前状态与 `seenStatus` 不符（或不再处于 `ANOMALY`/`OVERDUE`）时返回 `409 Conflict` 要求刷新核实；已结束名额返回 `200`（`alreadyEnded=true`）无副作用。
- 核实释放、移除当前映射、管理员审计（含真实管理员 ID、理由与前后状态）、原持有者通知及合法队首后继分配同事务提交，任一写入失败整体回滚。

**Never:**
- 不修改 `V1`–`V7`；不预建主动撤销（3.6）或代归还（3.5）。

</frozen-after-approval>

## Implementation Notes

- 2026-11-26：完成 `V8__admin_verified_release.sql`、管理员核实释放服务与 `AdminAllocationController`、`AdminVerifiedRelease.vue` 及 `AdminVerifiedReleaseIntegrationTest`（2 项）；`npm run build`（JS `93.31 kB`）与离线 Maven `verify` 合计 38 项测试全通过（0 失败/错误/跳过），详见 [evidence/story-2.6.md](evidence/story-2.6.md)。

## Verification

**Commands:**
- `npm.cmd --prefix frontend run build` -- expected: `vue-tsc --build` 与 Vite 构建零错误通过
- `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` -- expected: 全部集成测试（含 `AdminVerifiedReleaseIntegrationTest`）零失败通过
