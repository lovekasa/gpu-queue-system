# Epic 1 Context: 成员安全进入系统并识别指定资源

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

在本地课程演示中，管理员可初始化、登录并创建成员账号，成员可登录并识别 10 张 GPU 及使用约定。

## Stories

- Story 1.1: 初始化应用并以受控管理员身份登录
- Story 1.2: 管理员创建成员账号
- Story 1.3: 成员查看指定 GPU 与使用约定

## Requirements & Constraints

- 初始管理员通过受控本地命令建立。重复执行不覆盖口令或增权。成员由管理员建号，角色固定为成员。凭据通过系统外渠道交付。
- 登录和建号共用名称规范化。数据库保证登录名唯一，重复建号不覆盖原账号。口令只存安全散列，日志、响应体、Git 及测试证据不包含敏感数据。
- 覆盖合法入口、直接越权、无效凭据、CSRF 和退出后旧会话失效。测试使用独立 H2 临时文件库。
- 资源目录使用 GPU 1 至 GPU 10 的稳定标识，重复种子和重启不产生重复记录。已登录成员及管理员可读。页面展示使用权约定，成员获配后自行运行任务。

## Technical Decisions

- Vue 3（官方 create-vue）、Spring Boot 3.3.13、Java 21，H2 嵌入式文件库。工程创建时记录运行工具版本并锁定实际项目依赖。
- 本机同源 HTTP，开发时 Vue 代理 API。Spring Security 校验认证、角色和 CSRF，Session Cookie 为 HttpOnly。
- 版本化迁移由脚本明确执行，开发、演示及测试数据隔离。目录打开或迁移失败时停止启动并明确报错。
- 按故事建立必需表。1.1 建账号结构，1.3 交付 GPU 目录，申请及分配留给后续史诗。
- JSON 标识为字符串，时间用 ISO 8601 UTC 带 Z，页面显示 Asia/Shanghai。按认证、权限、冲突和输入使用 401、403、409、400。

## UX & Interaction Patterns

- 受控初始化后可登录，查看本人的身份，并退出回到登录页。密码错误的反馈不透露账号敏感信息。
- 管理员建号时填写登录名、显示姓名及初始口令。新成员通过同一入口登录，成员账号不能访问管理建号。
- GPU 目录展示标识、类型和使用约定，加载和失败状态可以辨认。

## Cross-Story Dependencies

- 1.1 独立实现初始化、登录和退出，1.2 复用其身份与账号，1.3 验证成员可查看资源。Epic 2 复用身份、会话及稳定资源标识。
- 实验 A 在选定的 X/Y 故事实现前准备，以实际开发、审阅及验证结果作为证据。
