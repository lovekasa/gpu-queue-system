# 开发交接入口

更新时间为 2026-11-26（Asia/Shanghai）。三人顺序接续，不预设固定人员分工。

## 接手顺序

1. 读 `AGENTS.md`、本文件和 `git status`，执行 `git checkout main && git pull --ff-only origin main` 确认同步最新主线。
2. 查看 `_bmad-output/implementation-artifacts/sprint-status.yaml` 和当前待办故事，再按 `_bmad-output/specs/spec-gpu-queue-system/SPEC.md` 及其全部配套文档与 `epics.md` 执行。
3. 接手结束时更新本文件、当前故事证据及冲刺状态，记录真实验证结果、遗留事项与提交情况。

## 当前进度总览（截至 Story 2.2 完成）

| 模块 / 故事 | 状态 | 已交付内容与证据 |
| --- | --- | --- |
| **Epic 1（Story 1.1–1.3）** | `done` | Spring Boot 3.3.13 + Vue 3 + H2 文件库脚手架、`V1__accounts.sql` 受控管理员初始化与登录退出（PR #2）、管理员网页创建成员账号（Story 1.2）、`V2__gpu_catalog.sql` 10 张固定 RTX 4090 目录与使用约定展示（PR #5）。证据见 `evidence/story-1.1.md`–`story-1.3.md`。 |
| **Story 2.1：普通申请入队与本人受理查询** | `done` | 已由 PR #6（`94e6f4a`）合入 `main`。交付 `V3__requests.sql`（`requests`、`queue_items`）、1–72 整数小时校验、多卡及主动重复申请独立入队、`POST/GET /api/requests` 与前端选卡表单。证据见 [story-2.1.md](_bmad-output/implementation-artifacts/evidence/story-2.1.md)。 |
| **Story 2.2：按卡分配普通申请并查看本人名额** | `done` | 已完成实现、26 项独立临时 H2 集成测试（`RequestIntegrationTest` 18 项 + `AllocationIntegrationTest` 8 项，0 失败/错误/跳过）、本机浏览器部分获配/异时期限/停服报错重试/重启补查验收及三层独立代码审阅收敛（提交 `6b647cf`）。交付 `V4__allocations.sql`（无损扩展 `queue_items`，新增 `allocations`、`current_allocations`、`audit_logs`、`inbox_messages` 及同卡复合外键/唯一约束）、`QueueOrderingPolicy`（严格 >72h 提升 + `submitted_at` + `receive_seq`）、`AllocationService` 同事务分配、`AllocationScanner`（60s 定时扫描 + `ApplicationReadyEvent` 启动补查，`init-admin` 禁扫）、`GET /api/allocations` 与 `GET /api/messages` 本人只读接口，以及前端“我的名额”/“我的申请”/“站内消息”三面板。规格见 [spec-2-2-allocate-ordinary-requests.md](_bmad-output/implementation-artifacts/spec-2-2-allocate-ordinary-requests.md)，证据见 [story-2.2.md](_bmad-output/implementation-artifacts/evidence/story-2.2.md)。 |
| **Story 2.3：成员按卡确认开始使用** | `review` | 已在分支 `codex/story-2-3` 完成实现与验证：交付 `V5__allocation_start.sql`（`allocations.started_at` 与 `audit_logs` `STARTED` 动作）、`AllocationService#confirmStart` 唯一事务入口与 `POST /api/allocations/{id}/start`、前端逐卡“确认开始使用”按钮与开始时间展示；前端构建与 30 项独立 H2 集成测试（`AllocationStartIntegrationTest` 4 项 + 既有 26 项）全部通过，待推送合并。证据见 [story-2.3.md](_bmad-output/implementation-artifacts/evidence/story-2.3.md)。 |
| **Epic 2 剩余故事（Story 2.4–2.8）** | `backlog` | 依次为：2.4 按卡确认完成并归还（含同事务后继分配） → 2.5 自动到期与 24h 未开始异常 → 2.6 管理员核实释放 → 2.7 公开等待队列 → 2.8 站内消息单条标记已读。 |
| **Epic 3（Story 3.1–3.9）** | `backlog` | 管理员紧急审核、人工调序、续期审核、直接延期、代归还、主动撤销、干预历史追溯、备份恢复演练及课程实验 A 准备。 |

## 下一步（供接续组员直接开始）

1. **同步与建分支**：在 `main` 拉取最新代码后新建分支（例如 `git checkout -b codex/story-2-3`），使用 BMAD 流程推进 **Story 2.3：成员按卡确认开始使用**（冲刺键 `2-3-成员按卡确认开始使用`）。
2. **Story 2.3 核心范围与接续要点**：
   - 先完整阅读 `AGENTS.md`、`_bmad-output/implementation-artifacts/epic-2-context.md`、`_bmad-output/specs/spec-gpu-queue-system/SPEC.md`、`lifecycle-commands.md` 及 `_bmad-output/planning-artifacts/epics.md` 中 Story 2.3 的 7 组验收标准。
   - **数据库迁移（`V5`）**：切勿修改已交付的 `V1`–`V4`。当前 `allocations` 仅含 `id, queue_item_id, gpu_id, allocated_at, expires_at`；Story 2.3 可新增 `V5` 为 `allocations` 增加 `started_at TIMESTAMP(9) WITH TIME ZONE`，并按需更新 `audit_logs` 的 `action` / `actor_type` 约束以记录成员确认开始审计。
   - **业务与一致性边界**：写入必须走 `AllocationService` 唯一事务入口；按 `allocation_id` 校验当前登录 `MEMBER` 本人归属及 `current_allocations` 当前有效映射；确认开始使用可注入 UTC `Clock` 记录首次 `started_at`，**不得重置 `expires_at` 或更换 `allocation_id`**，单卡确认不影响同申请其他卡；对已开始的有效名额重复提交返回已开始/已处理且保留首次 `started_at`（不重复写成功审计/消息）；对已不在 `current_allocations` 的旧名额（含同一成员两轮获配）返回失效/已处理且不恢复旧名额、不影响当前后继名额。
   - **前端与名额状态映射**：更新 `AllocationRepository`/`RequestRepository` 与 `OrdinaryRequests.vue` 的名额状态推导（`started_at` 非空时显示已开始及开始时间，未开始名额提供逐卡“确认开始”按钮与 CSRF 表单提交）。
3. **本地构建、测试与环境注意事项**：
   - 构建与全量测试命令见 [运行手册](docs/development/runbook.md) 的 Story 2.2 章节（`npm.cmd --prefix frontend run build` 后执行 `mvn.cmd -f backend/pom.xml verify`；若本机全局 Maven 缓存或镜像受限，可按运行手册在忽略目录 `.local-data/` 生成独立 `verification-maven-settings.xml` 运行）。
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
