---
title: 'Story 2.5 自动处理到期与未开始异常'
type: 'feature'
epic: 2
story: '2.5'
story_key: 2-5-自动处理到期与未开始异常
created: '2026-11-26'
status: 'done'
approved: '2026-11-26'
route: 'oneshot'
baseline_commit: 02d5e767268c9811616f78d252220d2346d3b45f
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 现有定时扫描与启动补查仅发现空闲卡分配，尚不能自动处理短时未开始到期释放、满 24 小时未开始异常保留及已开始到期逾期保留三类时间分支并发送站内通知。

**Approach:** 新增 `V7__allocation_expiry.sql`（增加 `not_started_anomaly_at`、`overdue_notified_expires_at` 并扩展审计与站内消息类型），在 `AllocationService#processExpiryForAllocation` 内按可注入 UTC 时钟与当前映射统一处理三类时间分支，并由每 60 秒扫描与启动补查复用该事务入口，落实 `epics.md` Story 2.5 验收（FR-010、FR-011、FR-015–017，NFR-001，AD-2–6）。

## Boundaries & Constraints

**Always:**
- 按当前批准跨度 `expires_at - allocated_at` 判定短时：未开始且严格不足 24 小时到期自动释放（`UNSTARTED_EXPIRED`）并同事务分配合法队首；跨度 >= 24 小时（含恰好 24 小时）且原分配满 24 小时仍未开始标 `not_started_anomaly_at` 并保留当前映射；已开始到期标逾期（`OVERDUE`）并保留当前映射。
- 所有状态变更、审计与站内消息同事务提交，失败整体回滚；重复扫描无重复审计或消息；迟到/旧到期候选不影响已结束名额或后继名额。
- 标异常后若成员确认开始，保留历史 `not_started_anomaly_at` 不清除。

**Never:**
- 不修改 `V1`–`V6`；不自动释放异常或逾期名额（留待 Story 2.6 管理员核实释放）。

</frozen-after-approval>

## Implementation Notes

- 2026-11-26：完成 `V7__allocation_expiry.sql`、`AllocationService#processExpiryForAllocation`、`AllocationScanner` 到期/异常/逾期扫描与前端状态展示；`npm run build`（JS `88.75 kB`）与离线 Maven `verify` 合计 36 项测试全部通过（0 失败/错误/跳过），详见 [evidence/story-2.5.md](evidence/story-2.5.md)。

## Verification

**Commands:**
- `npm.cmd --prefix frontend run build` -- expected: `vue-tsc --build` 与 Vite 构建零错误通过
- `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` -- expected: 全部集成测试（含 `AllocationExpiryIntegrationTest`）零失败通过
