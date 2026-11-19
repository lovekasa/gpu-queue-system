---
title: 'Story 1.1 初始化应用与受控管理员登录'
type: feature
created: '2026-11-18'
updated: '2026-11-19'
status: in-review
baseline_commit: f84c4944eaaa9a893766e9ba926a48fbd0467d59
route: dispatch
review_loop_iteration: 0
epic: 1
story: '1.1'
story_key: 1-1-初始化应用并以受控管理员身份登录
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

在本机完成初始管理员初始化、登录、查看本人身份和退出。采用 Vue 3、Spring Boot 3.3.13、Java 21 和内嵌 H2 文件库。交付账号所需结构，成员建号、GPU 和名额功能留给后续故事。

## Boundaries & Constraints

开发与演示数据目录隔离，测试使用独立临时文件库。启动脚本先运行版本化迁移。初始管理员通过本地命令创建，口令安全散列，重复初始化不覆盖账号或增权。本机同源 HTTP 保留 Session、HttpOnly Cookie 和 CSRF。凭据与数据库文件不入 Git。

</frozen-after-approval>

## Code Map

`epics.md` 的 Story 1.1 定义七组验收，`epic-1-context.md` 提供实施约束。启动脚本使用独立的迁移、管理员初始化和服务进程。前端通过同源 API 获取身份并提交登录与退出。

## Tasks & Acceptance

**Execution:**

- [x] 创建 `backend/pom.xml` 与 `frontend/package.json`，使用官方 create-vue 并生成前端锁文件。
- [x] 在 `backend/src/main/resources/db/migration/V1__accounts.sql` 编写账号迁移。用 Spring JDBC 保存账号，本地初始化模式从交互输入读取口令，执行期间不监听 HTTP。
- [x] 在 `backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java` 配置认证、Session 和 CSRF。在 `frontend/src/App.vue` 和 `frontend/src/api.ts` 实现受保护首页、登录、退出及错误反馈。
- [x] 在 `docs/development/runbook.md` 记录版本、数据目录、Flyway 显式迁移、本地初始化及启动步骤。
- [ ] 运行验收后，在 [Story 1.1 证据](evidence/story-1.1.md) 补充实际结果。

**Acceptance Criteria:**

1. Given 新克隆，When 创建工程，Then 记录实际 Java、Maven、Node/npm 和已解析的 H2 版本，锁定 Vue、H2 及新增依赖。
2. Given 各环境数据目录隔离，When 执行迁移和启动，Then 账号结构只迁移一次，记录实际 H2 文件位置，迁移或连接失败时不继续启动。
3. Given 管理员尚未创建，When 本地初始化及重复执行，Then 创建一位初始管理员并安全散列口令，重复执行保留原账号与权限，网页和 API 不开放初始化。
4. Given 已初始化管理员，When 以正确凭据登录，Then 经合法 CSRF 流程建立会话，身份首页显示本人，Session Cookie 为 HttpOnly。
5. Given 未登录、密码错误或写入缺少有效 CSRF，When 调用对应入口，Then 按实际原因返回拒绝或错误且不泄露敏感信息，合法请求正常。
6. Given 已登录，When 退出后复用原会话访问，Then 会话已失效，页面回到登录状态，日志不含口令或会话值。
7. Given 独立 H2 临时文件库，When 按运行文档走完整流程，Then 记录实际命令与通过或失败的结果，不包含凭据，不依赖后续故事。

## Implementation Notes

启动脚本为所选数据目录先运行 Flyway，再进入初始化或 Web 模式。账号口令用 PBKDF2 散列，登录名去首尾空白后按 `Locale.ROOT` 统一小写。H2 锁定为 2.2.224，前端由 npm 锁文件固定版本。步骤见 [运行手册](../../docs/development/runbook.md)。

## Spec Change Log

## Review Triage Log

| 走查 | 发现 | 判定和处置 |
| --- | --- | --- |
| 独立代码走查 | 外部配置可覆盖应用的数据库路径 | 中。所选 H2 路径由显式启动参数提供。 |
| 独立代码走查 | 验收可误用开发或演示库 | 高。由系统临时目录和本轮验收 ID 构造路径。 |
| 独立代码走查 | 外部配置可让初始化监听 HTTP | 高。初始化使用显式非 Web 启动参数。 |
| 独立代码走查 | 账号数据库故障被显示为口令错误 | 中。认证服务异常返回 503。 |
| 独立代码走查 | 路径含分号时 H2 会解析成连接设置 | 低。H2 文件 URL 的输入限制，当前目录不含分号。 |
| 独立代码走查 | 脚本接受的模式大小写与 Java 不一致 | 低。脚本传递规范化模式。 |
| 独立代码走查 | 交接记录仍为实施前状态 | 低。交接已同步至审阅。 |
| 独立代码走查 | 验收证据链接暂不可用 | 低。已补静态验证记录。 |
| 独立代码走查 | 已登录会话的 CSRF 验收缺失 | 中。运行手册给出拒绝请求及会话保持的判据。 |
| 独立代码走查 | 缺少迁移失败的验收判据 | 中。已补充启动失败的判据，实际执行待验收。 |
| 边界走查 | Windows PowerShell 可能误读脚本编码 | 中。启动脚本以带 BOM 的 UTF-8 保存。 |
| 边界走查 | 含分号目录不能作为 H2 路径 | 低。沿用 H2 文件 URL 的限制。 |
| 边界走查 | 任意验收目录可能重合 | 高。验收目录由临时路径及 ID 决定。 |
| 边界走查 | 数据库异常和口令错误反馈相同 | 中。后端分别返回 503 和 401。 |
| 边界走查 | 验收证据路径缺失 | 低。静态证据文件已就位。 |
| 验收走查 | 缺少实际迁移失败记录 | 中。独立临时库运行证据待补。 |
| 验收走查 | 缺少首次口令散列落库结果 | 中。静态确认编码后写入，实际持久化待核实。 |
| 验收走查 | 登录名大小写和首尾空白未实测 | 低。运行手册纳入等价登录名的检查。 |
| 验收走查 | 启动模式的大小写不一致 | 低。启动参数统一使用小写。 |
| 验收走查 | 运行手册链接的验证页缺失 | 低。现有验证页记录静态和待执行事项。 |

## Verification

2026-11-19 用户确认 Story 1.1 并授权实现。已核对工具版本、依赖锁、启动脚本语法和前后端源码，详见 [验证记录](evidence/story-1.1.md)。本轮仅做静态走查，编译构建、迁移、启动及业务验收待执行。
