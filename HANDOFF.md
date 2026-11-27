# 开发交接入口

更新时间为 2026-11-26（Asia/Shanghai）。三人顺序接续，不预设固定人员分工。

## 接手顺序

1. 读 `AGENTS.md`、本文件和 `git status`，执行 `git checkout main && git pull --ff-only origin main` 确认同步最新主线。
2. 查看 `_bmad-output/implementation-artifacts/sprint-status.yaml` 和当前待办故事，再按 `_bmad-output/specs/spec-gpu-queue-system/SPEC.md` 及其全部配套文档与 `epics.md` 执行。
3. 接手结束时更新本文件、当前故事证据及冲刺状态，记录真实验证结果、遗留事项与提交情况。

## 当前进度总览（截至 Epic 2 全部合入 `main`，PR #8）

| 模块 / 故事 | 状态 | 已交付内容与证据 |
| --- | --- | --- |
| **Epic 1（Story 1.1–1.3）** | `done` | Spring Boot 3.3.13 + Vue 3 + H2 文件库脚手架、`V1__accounts.sql` 受控管理员初始化与登录退出（PR #2）、管理员网页创建成员账号（Story 1.2）、`V2__gpu_catalog.sql` 10 张固定 RTX 4090 目录与使用约定展示（PR #5）。证据见 `evidence/story-1.1.md`–`story-1.3.md`。 |
| **Story 2.1：普通申请入队与本人受理查询** | `done` | 已由 PR #6（`94e6f4a`）合入 `main`。交付 `V3__requests.sql`（`requests`、`queue_items`）、1–72 整数小时校验、多卡及主动重复申请独立入队、`POST/GET /api/requests` 与前端选卡表单。证据见 [story-2.1.md](_bmad-output/implementation-artifacts/evidence/story-2.1.md)。 |
| **Story 2.2：按卡分配普通申请并查看本人名额** | `done` | 已由 PR #7（`38520dd`）合入 `main`。交付 `V4__allocations.sql`、`QueueOrderingPolicy`（严格 >72h 提升 + `submitted_at` + `receive_seq`）、`AllocationService` 同事务分配、`AllocationScanner`、`GET /api/allocations` 与 `GET /api/messages` 及前端三面板。证据见 [story-2.2.md](_bmad-output/implementation-artifacts/evidence/story-2.2.md)。 |
| **Story 2.3：成员按卡确认开始使用** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `V5__allocation_start.sql`、`AllocationService#confirmStart` 与 `POST /api/allocations/{id}/start`、前端逐卡“确认开始使用”按钮与开始时间展示。证据见 [story-2.3.md](_bmad-output/implementation-artifacts/evidence/story-2.3.md)。 |
| **Story 2.4：成员按卡确认完成并归还名额** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `V6__allocation_return.sql`、`AllocationService#confirmReturn`（同事务归还并立即按序交接给合法队首）、`POST /api/allocations/{id}/return` 及前端逐卡“确认完成并归还”与历史状态展示。证据见 [story-2.4.md](_bmad-output/implementation-artifacts/evidence/story-2.4.md)。 |
| **Story 2.5：自动处理到期与未开始异常** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `V7__allocation_expiry.sql`、`AllocationService#processExpiryForAllocation` 三分支处理（<24h 未开始到期自动释放并交接、>=24h 满 24h 未开始标异常保留、已开始到期标逾期保留）、`AllocationScanner` 扩展与前端状态展示。证据见 [story-2.5.md](_bmad-output/implementation-artifacts/evidence/story-2.5.md)。 |
| **Story 2.6：管理员核实异常或逾期后释放名额** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `V8__admin_verified_release.sql`、`GET /api/admin/allocations/pending-verification` 与 `POST /api/admin/allocations/{id}/verified-release`（仅 `ADMIN`、非空理由、`seenStatus` 冲突 `409`、同事务审计/通知与后继交接）及管理员前端面板 `AdminVerifiedRelease.vue`。证据见 [story-2.6.md](_bmad-output/implementation-artifacts/evidence/story-2.6.md)。 |
| **Story 2.7：成员查看各 GPU 的公开等待队列** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `GET /api/queues`（复用 `QueueOrderingPolicy`，严格仅返回 `position` 与 `memberName` 两字段）及前端 `PublicQueues.vue`。证据见 [story-2.7.md](_bmad-output/implementation-artifacts/evidence/story-2.7.md)。 |
| **Story 2.8：成员查看站内消息并标记已读** | `done` | 已由 PR #8（`2153e74`）合入 `main`。交付 `V9__inbox_read_at.sql`、`POST /api/messages/{id}/read`（仅限本人 `MEMBER` + CSRF，显式标记已读且重复调用幂等保留首次 `read_at`）及前端未读/已读状态与逐条“标记已读”按钮；全量 40 项独立 H2 集成测试通过。证据见 [story-2.8.md](_bmad-output/implementation-artifacts/evidence/story-2.8.md)。 |
| **Story 3.1：成员提交紧急申请并由管理员审核** | `review` | 已在分支 `codex/story-3-1` 完成实现与验证：交付 `V10__emergency_requests.sql`、三层优先级排序（`>72h` > 已批准紧急 > 普通）、`GET /api/admin/requests/emergency-pending`、`POST /api/admin/requests/{id}/emergency-review` 及前端紧急申请表单与 `AdminEmergencyReview.vue`；全量 42 项独立 H2 集成测试通过，待推送合并。证据见 [story-3.1.md](_bmad-output/implementation-artifacts/evidence/story-3.1.md)。 |
| **Story 3.2：管理员调整当前等待项的顺序** | `review` | 已在同分支完成实现与验证：交付 `V11__queue_reorders.sql`、AD-7 移动重放算法（`QueueOrderingPolicy`）、`GET /api/admin/queues`、`POST /api/admin/queues/{gpuId}/reorder`（所见快照 `409` 校验、同事务审计与受影响成员通知）及前端 `AdminQueueReorder.vue`；全量 44 项独立 H2 集成测试通过，待推送合并。证据见 [story-3.2.md](_bmad-output/implementation-artifacts/evidence/story-3.2.md)。 |
| **Story 3.3：成员申请续期并由管理员审核** | `review` | 已在同分支完成实现与验证：交付 `V12__renewal_requests.sql`、`POST /api/allocations/{id}/renewals`、`GET /api/renewals`、`GET /api/admin/renewals/pending`、`POST /api/admin/renewals/{id}/review`（突破 72h 上限、D039 24h 跨度边界、逾期恢复与二次逾期通知、同事务审计与消息）及前端 `AdminRenewalReview.vue`；全量 46 项独立 H2 集成测试通过，待推送合并。证据见 [story-3.3.md](_bmad-output/implementation-artifacts/evidence/story-3.3.md)。 |
| **Epic 3 剩余故事（Story 3.4–3.9）** | `backlog` | 直接延期、代归还、主动撤销、干预历史追溯、备份恢复演练及课程实验 A 准备。 |

## 下一步（供接续组员直接开始）

1. **同步与建分支**：在 `main` 拉取最新代码后新建分支（例如 `git checkout -b codex/story-3-1`），推进 **Epic 3 / Story 3.1：成员提交紧急申请并由管理员审核**（冲刺键 `3-1-成员提交紧急申请并由管理员审核`）。
2. **Epic 3 / Story 3.1 接续要点**：
   - 先完整阅读 `AGENTS.md`、`_bmad-output/specs/spec-gpu-queue-system/SPEC.md` 及其配套文档与 `_bmad-output/planning-artifacts/epics.md` 中 Epic 3 / Story 3.1 的验收标准。
   - **数据库迁移（从 `V10` 起）**：切勿修改已合入 `main` 的 `V1`–`V9` 迁移脚本；新增字段或表请从 `V10__*.sql` 开始增量迁移。
   - **事务与权限边界**：继续保持 `AllocationService` 唯一事务写入入口与 Spring Security + CSRF 防护，管理员审核与干预操作须记录不可篡改的 `audit_logs` 并向相关成员发送站内消息。
3. **本地构建、测试与环境注意事项**：
   - 构建与全量测试命令：`npm.cmd --prefix frontend run build` 后执行 `mvn.cmd -f backend/pom.xml verify`（若本机全局 Maven 缓存或镜像受限，可按 [运行手册](docs/development/runbook.md) 在忽略目录 `.local-data/` 生成独立 `verification-maven-settings.xml` 运行）。
   - 切勿提交 `.local-data/`、数据库文件、凭据或本地未跟踪课程材料（如 `_bmad-output/course-source/`）。
   - **课程实验 A 提醒**：D047、D048 已确定本地 H2 顺序验收；若后续选定某对难度相当的故事作为实验 A 的 X/Y 对照（见 Story 3.9），必须在该故事开始实现前完成实验 A 对照方案准备，不将日常普通开发充作干净会话实验结果。

## 交接历史

| 日期 | 本轮工作及实际核查 |
| --- | --- |
| 2026-11-18 | 建立三人接续入口和冲刺跟踪，形成 Story 1.1 首稿；完成当时环境及 MySQL 只读核对。实施尚未开始。 |
| 2026-11-18 | D047 形成轻量本地演示提案，提交 `b7bae04cdd65f3b559ed451595d59a2716168bdb`。 |
| 2026-11-19 | D048 确认 H2，提交范围为规格和 Story 1.1 草案。基于 `b7bae04cdd65f3b559ed451595d59a2716168bdb` 在 `codex/lightweight-demo` 接续。远端读取和推送权限核对成功。BMAD 渲染在沙箱临时目录报 Windows 650，改在普通临时目录执行成功。 |
| 2026-11-19 | 用户确认 Story 1.1，实现账号迁移、受控初始化及登录会话，完成静态检查。运行验收待执行。 |
| 2026-11-19 | 用户遇到编译器不支持 Java 21。用本机 Java 21.0.9 跳过测试打包成功，补充运行手册的 PowerShell 配置。 |
| 2026-11-22 | 在 macOS 完成 Story 1.1 七组运行验收，更新证据、冲刺状态和 macOS 运行手册。 |
| 2026-11-22 | 实现 Story 1.2，完成 HTTP、H2 写入失败与 Safari 验收，同步冲刺状态为审阅。 |
| 2026-11-24 | 按 Story 1.1 流程重新构建 JAR，在独立 H2 库通过 Story 1.2 的六组复核，结合已有 Safari 证据标记完成。 |
| 2026-11-24 | 用户确认已手动推送 Story 1.2 验收提交。实现 Story 1.3 并通过构建、MockMvc 与 Vue 组件验证，进入审阅。 |
| 2026-11-24 | 使用独立 H2 库完成 Story 1.3 的 Safari 验收和 Chrome 补充验证。重启演示 JAR 后旧会话失效，原成员可重新登录，目录响应一致。Story 1.3 和 Epic 1 关闭。 |
| 2026-11-25 | 核对GitHub同步后的进度；创建codex/story-2-1，编译Epic2上下文，完成Story2.1实现规格及七组待执行验收记录。冲刺文件validate=true，差异格式检查通过；规格处于draft，尚无业务代码或运行验收；本轮未提交或推送。 |
| 2026-11-25 | 用户批准 Story 2.1 后完成普通申请和本人逐卡等待页面；18 项临时 H2 集成测试、前端构建及实际 Edge 页面/失败回滚/进程重启/建号回归通过，证据及运行手册已更新。故事进入 review，待主线独立审阅；测试服务停止，未提交或推送。 |
| 2026-11-25 | 三个独立层审阅及逐条裁决完成，修正列表加载重叠、503诊断、V2旧账号升级验证并补证据编号。主线重新完整构建前端和离线Maven verify（18项全通过），冲刺校验通过；规格done、冲刺review，按BMAD创建本地提交供接收，未推送。 |
| 2026-11-25 | 推送 `codex/story-2-1` 至 GitHub，创建并合并 PR #6（`94e6f4a`），将 Story 2.1 冲刺状态标记为 `done`，建立 `codex/story-2-2` 接续 Story 2.2。 |
| 2026-11-25 | 按已批准 `spec-2-2-allocate-ordinary-requests.md` 实现 V4 迁移、队列排序策略、唯一名额分配事务（含审计转换序号与获配站内消息）、60s 扫描与启动补查、本人名额/消息只读接口及成员页面；通过前端构建、26 项独立临时 H2 集成测试及浏览器部分获配/异时期限/停服重试/重启补查/建号回归验收，更新证据与运行手册，停止测试服务。 |
| 2026-11-26 | 完成 Story 2.2 三层独立代码审阅与 22 条逐项裁决加固，重新通过前端完整构建与离线 Maven `verify`（26 项全通过，提交 `6b647cf`）；标记 Story 2.2 为 `done`，整理组员交接说明并推送 `codex/story-2-2` 创建 PR 合入 `main`，下一待办为 Story 2.3。 |
| 2026-11-26 | 基于 `main`（`38520dd`）在 `codex/story-2-3` 实现 Story 2.3（`V5__allocation_start.sql`、`POST /api/allocations/{id}/start`、前端逐卡确认开始与开始时间展示）；通过前端构建与 30 项独立 H2 集成测试（0 失败/错误/跳过），规格标 `done`、冲刺标 `review`，创建本地提交待推送合并。 |
| 2026-11-26 | 接续完成 Story 2.4（`V6__allocation_return.sql`、`POST /api/allocations/{id}/return` 同事务归还与合法队首交接、旧归还/重复归还隔离及前端逐卡归还与已归还历史展示）；通过前端构建与 33 项独立 H2 集成测试（0 失败/错误/跳过），规格标 `done`、冲刺标 `review`，创建本地提交待推送合并。 |
| 2026-11-26 | 接续完成 Story 2.5（`V7__allocation_expiry.sql`、三分支自动到期/24h 未开始异常/已开始逾期处理、扫描器扩展与前端状态展示）；通过前端构建与 36 项独立 H2 集成测试（0 失败/错误/跳过），规格标 `done`、冲刺标 `review`，创建本地提交待推送合并。 |
| 2026-11-26 | 接续完成 Story 2.6（`V8__admin_verified_release.sql`、管理员待核实列表与核实释放接口、所见状态 `409` 校验、同事务后继交接及 `AdminVerifiedRelease.vue`）；通过前端构建与 38 项独立 H2 集成测试（0 失败/错误/跳过），规格标 `done`、冲刺标 `review`，创建本地提交待推送合并。 |
| 2026-11-26 | 接续完成 Story 2.7（`GET /api/queues` 严格两字段公开等待队列与 `PublicQueues.vue`）与 Story 2.8（`V9__inbox_read_at.sql`、`POST /api/messages/{id}/read` 显式幂等已读与前端逐条标记已读），通过前端构建与 40 项独立 H2 集成测试；推送 `codex/story-2-3` 并通过 PR #8（`2153e74`）合入 `main`，Epic 2 全部 8 个故事完成，下一待办为 Epic 3 / Story 3.1。 |
