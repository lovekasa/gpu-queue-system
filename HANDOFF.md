# 开发交接入口

更新时间为 2026-11-25（Asia/Shanghai）。三人顺序接续，不预设固定人员分工。

## 接手顺序

1. 读 AGENTS.md、本文件和 git status，确认他人改动。
2. 查看 _bmad-output/implementation-artifacts/sprint-status.yaml 和当前故事，再按规格及其配套文档执行。
3. 接手结束时更新本文件、当前故事及冲刺状态。记录实际验证、遗留事项与提交情况。

## 当前进度

- Story 2.1 [实现规格](_bmad-output/implementation-artifacts/spec-2-1-submit-request.md)已由 PR #6（`94e6f4a`）合入 `main`，冲刺状态标记为 `done`。已交付 V3、普通申请事务及本人查询、成员选卡/时长表单和逐卡等待列表；[运行证据](_bmad-output/implementation-artifacts/evidence/story-2.1.md)记录全部七组验收和修正复核。
- Story 2.1 实际通过 18 项临时 H2 文件库集成测试（0失败/错误/跳过）、Vue 类型检查及构建、Edge 单/多卡和有意重复申请、刷新、真实写入失败反馈及整体回滚、查询失败重试、JAR进程重启后重新登录重读、管理员网页建号回归。缓存/临时库在忽略目录`.local-data/`，开发/演示库未访问。
- D047、D048 已确认本地 H2 文件库及顺序验收。PRD、架构与 ADR、验证计划、epics.md、SPEC 及配套契约是现行依据。课程实验 A 仍待准备，候选故事与旧审阅记录留作历史。
- Story 1.1 已通过 macOS 构建、独立 H2 临时库、HTTP 和 Safari 验收，已标记 `done`。实现已由 PR #2 合入 `main`。
- 初始管理员创建、登录及退出、CSRF 拒绝、重复初始化和迁移失败均有 [运行证据](_bmad-output/implementation-artifacts/evidence/story-1.1.md)。[运行手册](docs/development/runbook.md) 已补充 macOS 复现步骤。
- Story 1.2 已验收并标记 `done`。本轮重新构建并通过六组 HTTP 与数据库复核，页面采用 2026-11-22 的 Safari 记录。见 [运行证据](_bmad-output/implementation-artifacts/evidence/story-1.2.md)。验收提交 `dd2b2e3` 已由用户手动推送到 GitHub。
- Story 1.3 使用独立 H2 文件库通过 Safari 27.0.1 验收，覆盖网页建号、成员登录、10 张 GPU 的固定目录和使用约定、刷新、断网重试及访问控制。应用进程重启后，旧会话失效，原成员重新登录成功，目录响应与重启前逐字节一致。Chrome 补充验证通过，测试服务已停止。Story 1.3 和 Epic 1 均为 `done`。实现提交为 `e67f570`。见 [验收记录](_bmad-output/implementation-artifacts/evidence/story-1.3.md#safari-页面与应用进程验收)。

## 下一步

基于 `main`（`94e6f4a`）在 `codex/story-2-2` 接续 Story 2.2（按卡分配普通申请并查看本人名额）；不要重做 Story 1.1–2.1。复现入口为 `backend/src/test/java/org/lab/gpuqueue/allocation/RequestIntegrationTest.java` 及 `docs/development/runbook.md` 的 Story 2.1 章节；本机 Maven 使用 `.local-data/verification-maven-settings.xml` 和项目内缓存，未改全局设置。现有课程材料保留本地。实验 A 仍须在选定 X/Y 故事实现前准备，不将本次普通开发充作实验结果。

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
