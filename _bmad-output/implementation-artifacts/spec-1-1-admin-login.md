---
title: 'Story 1.1 初始化应用与受控管理员登录'
type: feature
created: '2026-11-18'
status: draft
route: dispatch
epic: 1
story: '1.1'
story_key: 1-1-初始化应用并以受控管理员身份登录
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

为管理员交付可重复初始化、登录和退出的最小应用，执行 epics.md Story 1.1 的全部七组验收。仅初始化账号与认证基础，后续故事逐项接续。

## Boundaries & Constraints

保持 Java21、Boot3.3.13、Vue3、本地MySQL；工程创建前核实服务端版本，官方create-vue生成前端并锁定依赖。dev/test/demo数据库及账号隔离，应用不得自动改表。受控初始化不公开为HTTP接口，不覆盖已有管理员；Session、CSRF、HttpOnly及正式Secure Cookie必需。不得预建GPU/申请/名额表，不记录凭据。

## I/O & Edge-Case Matrix

| 场景 | 输入/状态 | 结果 | 错误处理 |
| --- | --- | --- | --- |
| 初始化 | 受控命令、有效配置 | 新管理员安全散列落库 | 重复不覆盖、不增权；失败退出非零 |
| 登录 | 正确凭据及CSRF | Session、本人身份首页 | 错误凭据不泄露账号信息 |
| 直接请求 | 未认证或缺CSRF | 401/403，无业务变化 | 合法请求仍可成功 |
| 退出 | 有效Session及CSRF | 会话失效，回登录页 | 原Session不能访问 |
| 迁移/连接 | 无法连接或迁移失败 | 明确失败 | 不自动改表或假成功 |

</frozen-after-approval>

## Code Map

- `AGENTS.md`：跨故事约束；`HANDOFF.md`：三人顺序接续入口。
- `_bmad-output/planning-artifacts/epics.md`：Story1.1七组验收权威；当前没有业务代码可复用。
- `_bmad-output/implementation-artifacts/sprint-status.yaml`：状态跟踪，未验收不得done。

## Tasks & Acceptance

**Execution:**

- [ ] `docs/development/environment.md`：记录实际版本、MySQL只读核验及依赖锁定；当前Java21.0.2/Maven3.9.6/Node24.18.0/npm11.16.0/MySQL服务端8.0.34已实测。
- [ ] `backend/pom.xml`、`frontend/package.json`、`frontend/package-lock.json`：最小Boot工程和create-vue3.24.0脚手架；锁定解析后的依赖。
- [ ] `backend/src/main/resources/db/migration/V1__accounts.sql`、`backend/src/main/java/org/lab/gpuqueue/identity/AccountRepository.java、AdminInitializer.java`：JdbcTemplate持久化、Flyway显式迁移及受控管理员命令，仅账号结构。
- [ ] `backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java`、`backend/src/main/resources/application.yml`：认证、CSRF、Session、配置分离及失败处理。
- [ ] `frontend/src/App.vue`、`frontend/src/api.ts`、`frontend/vite.config.ts`：登录/身份首页/退出和错误反馈，同源代理。
- [ ] `backend/src/test/java/org/lab/gpuqueue/AuthenticationIntegrationTest.java`、`frontend/src/App.spec.ts`及`docs/development/runbook.md`：独立MySQL验证、页面检查、启动/迁移/初始化说明。
- [ ] `_bmad-output/implementation-artifacts/evidence/story-1.1.md`、`HANDOFF.md`：逐条记录七组验收、命令及结果，审阅后同步冲刺状态。

**Acceptance Criteria:**

- Given空工程，When初始化，Then精确版本/锁文件可复核且MySQL服务端有实测证据。
- Given隔离数据库，When显式迁移与重复初始化，Then结构可重复建立、管理员不被覆盖，失败不假成功。
- Given登录页，When合法登录/退出及非法直接请求，ThenSession/CSRF/权限与七组原故事验收一致。
- Given独立测试库，When执行验收，Then保存可复核证据，且不依赖后续故事。

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

选择Spring JDBC便于后续显式行锁；Flyway由独立命令执行，应用启动禁用自动迁移。数据库连接信息从本地配置提供，不入库。已核实`gpu-queue`存在且无表，登录路径使用root管理账号；应用使用独立受限账号，dev/test/demo隔离库尚待创建，不在用户原库运行破坏性测试。三人不预分工，每次中断更新交接入口。

## Verification

计划使用`mvn -f backend/pom.xml verify`及前端构建/测试脚本；命令须在工程实际生成后验证并补齐。独立MySQL未就绪时标记未执行，不用H2替代。

