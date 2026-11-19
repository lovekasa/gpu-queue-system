---
title: 'Story 1.1 初始化应用与受控管理员登录'
type: feature
created: '2026-11-18'
updated: '2026-11-19'
status: draft
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

`epics.md` 的 Story 1.1 定义七组验收，`epic-1-context.md` 提供实施约束。仓库尚无业务工程。

## Tasks & Acceptance

**Execution:**

- [ ] 创建 `backend/pom.xml` 与 `frontend/package.json`，使用官方 create-vue 并提交前端锁文件。
- [ ] 在 `backend/src/main/resources/db/migration/V1__accounts.sql` 建必需账号结构。用 Spring JDBC 保存账号，本地初始化模式从交互输入读取口令，执行期间不监听 HTTP。
- [ ] 在 `backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java` 配置认证、Session 和 CSRF。在 `frontend/src/App.vue` 和 `frontend/src/api.ts` 实现受保护首页、登录、退出及错误反馈。
- [ ] 在 `docs/development/runbook.md` 记录版本、数据目录、Flyway 显式迁移、本地初始化及启动步骤。在 `_bmad-output/implementation-artifacts/evidence/story-1.1.md` 留存实际验收证据。

**Acceptance Criteria:**

1. Given 新克隆，When 创建工程，Then 记录实际 Java、Maven、Node/npm 和已解析的 H2 版本，锁定 Vue、H2 及新增依赖。
2. Given 各环境数据目录隔离，When 执行迁移和启动，Then 账号结构只迁移一次，记录实际 H2 文件位置，迁移或连接失败时不继续启动。
3. Given 管理员尚未创建，When 本地初始化及重复执行，Then 创建一位初始管理员并安全散列口令，重复执行保留原账号与权限，网页和 API 不开放初始化。
4. Given 已初始化管理员，When 以正确凭据登录，Then 经合法 CSRF 流程建立会话，身份首页显示本人，Session Cookie 为 HttpOnly。
5. Given 未登录、密码错误或写入缺少有效 CSRF，When 调用对应入口，Then 按实际原因返回拒绝或错误且不泄露敏感信息，合法请求正常。
6. Given 已登录，When 退出后复用原会话访问，Then 会话已失效，页面回到登录状态，日志不含口令或会话值。
7. Given 独立 H2 临时文件库，When 按运行文档走完整流程，Then 记录实际命令与通过或失败的结果，不包含凭据，不依赖后续故事。

## Implementation Notes

迁移通过独立步骤执行，初始化和服务模式使用同一数据目录配置。精确 H2 版本、数据库文件路径及运行命令在创建工程后记录。

## Spec Change Log

## Review Triage Log

## Verification

2026-11-19 静态核对七组验收与故事清单，并在本机重新生成 BMAD Build 工作流。当前为待审草案，业务实现、启动和验收均未执行。
