---
title: 'Story 2.1 普通申请与本人受理结果'
type: feature
epic: 2
story: '2.1'
story_key: 2-1-成员提交指定-gpu-普通申请并查看受理结果
created: '2026-11-25'
status: done
approved: '2026-11-25'
route: dispatch
baseline_commit: f6f4feef12e94af0917dc1cfb99d936ce864382c
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

成员选择指定GPU和1–72整数小时，免审批入队并查看本人逐卡等待记录，落实epics.md Story2.1全部七组验收。

## Boundaries & Constraints

H2文件库、普通事务、顺序验收；身份来自Session，写入校验CSRF。有意重复申请各自保留。只建申请和等待项，不交付分配、期限、通知、公开队列或命令幂等表。

## I/O & Edge-Case Matrix

| 场景 | 输入 | 结果 |
| --- | --- | --- |
| 提交 | POST /api/requests，表单gpuIds（可多值）、durationHours | 201：申请ID、UTC提交时刻、接收序号及逐卡WAITING |
| 校验 | 无卡/不存在卡/单次重复卡，时长空或非1–72整数 | 400指出字段，无部分写入；跨申请重复卡合法 |
| 重复 | 同一成员再次主动提交 | 新ID、时间和递增接收序号，不合并 |
| 本人查询 | GET /api/requests 及 /api/requests/{id} | 仅本人；详情不存在或非本人统一404；加载失败不显示空列表 |
| 权限 | 匿名、管理员或伪造申请人字段、缺CSRF | 按认证/角色/输入拒绝，无越权数据；合法成员可操作 |
| 写入失败 | 多卡保存中途出错 | 全事务回滚，503且不显示成功 |

标识及接收序号返回字符串，时间带Z，页面上海时区；列表按接收序号倒序。每张卡一条等待项，提交与查看均无获配副作用。

</frozen-after-approval>

## Code Map

- `frontend/src/api.ts`复用表单POST前取CSRF；`frontend/src/App.vue`按角色挂载页面。
- `backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java`当前默认拒绝未列出的API，必须显式授权。
- `scripts/start.ps1`先迁移后启动；V1账号、V2GPU及原验收不改写。

## Tasks & Acceptance

- [x] `backend/src/main/resources/db/migration/V3__requests.sql`：申请UUID、成员外键、UTC时间、持久化接收序号、时长约束；等待项UUID及申请/GPU外键，申请内GPU唯一。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/RequestRepository.java`、`backend/src/main/java/org/lab/gpuqueue/allocation/AllocationService.java`：本人查询及唯一写入口，整单事务回滚。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/RequestController.java`、`backend/src/main/java/org/lab/gpuqueue/security/AccountPrincipal.java`、`backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java`：显式取认证ID、成员授权、校验及响应裁剪。
- [x] `backend/src/main/java/org/lab/gpuqueue/TimeConfiguration.java`：可注入UTC时钟，测试时间并列及重启持久化。
- [x] `frontend/src/OrdinaryRequests.vue`、`frontend/src/api.ts`、`frontend/src/App.vue`：选卡/时长、提交中禁用、明确待分配、本人列表及加载/空态/错误/重试。
- [x] `backend/pom.xml`、`backend/src/test/java/org/lab/gpuqueue/allocation/RequestIntegrationTest.java`：Boot管理的测试依赖；临时H2文件、真实事务和安全过滤链覆盖矩阵及回滚、重读。
- [x] `docs/development/runbook.md`、`_bmad-output/implementation-artifacts/evidence/story-2.1.md`、`HANDOFF.md`：运行及页面证据、遗留和下一步；据实际结果更新冲刺状态。

Given单/多卡有效输入，When提交并刷新，Then本人可见完整逐卡等待记录；重启后仍在。

Given非法输入、他人ID或写入失败，When调用页面/API，Then按矩阵拒绝或整体回滚，其他申请不变。

## Design Notes

接收序号用H2 identity，允许回滚空洞。时间以Clock产生Instant，经OffsetDateTime写入TIMESTAMP WITH TIME ZONE。重复GPU仅指一次提交内重复，不影响多次排同一卡。新迁移先在临时文件库验证；不触碰用户数据库。

## Verification

`mvn.cmd -f backend/pom.xml verify`：执行新增集成验收；`npm.cmd --prefix frontend run build`：类型及构建通过。页面手动验证成员提交、刷新、失败反馈及管理建号回归；结果逐项记录，未执行不算通过。

## Implementation Notes

- 2026-11-25：用户批准规格并继续实现；保留批准时的frozen块及基线SHA，进入实施。
- 2026-11-25：完成 V3、唯一名额服务入队事务、本人 API、安全过滤链授权及成员页面；18 项独立 H2 文件集成测试通过，Vue 类型检查/构建通过。Edge 实际单/多卡、主动重复、失败反馈、刷新、进程重启重读及管理员网页建号回归通过，详见 evidence/story-2.1.md。实现进入 review，待独立审阅；未提交或推送。
- 2026-11-25：完成三个独立审阅层及逐项裁决；B1/E1、B5、B6和B8证据部分已局部修正。主线重新运行前端完整构建及离线Maven verify：18项、零失败/错误/跳过，8.774秒；最新同源JAR打包成功。构建规格标记done，冲刺维持review等待接收/合并，无defer条目。

## Spec Change Log

## Review Triage Log

2026-11-25：三个独立层均已返回；下列逐项裁决先于合并处理。同根因B1/E1合并为一个局部修正，其余分别处理。

| 发现 | 裁决 | 核对证据与处理 |
| --- | --- | --- |
| B1 首次列表响应覆盖提交后的列表 | medium | onMounted启动列表，表单原来仅等目录；目录先完成时可提交，旧列表能晚到。patch：列表加载时禁用提交并在submit入口检查，避免两个列表读取重叠。 |
| B2 会话失效后没有登录恢复路径 | false | App仍显示退出按钮；submitLogout接收401后清除身份，整页刷新也由/api/me的401返回登录表单。专门组件事件并非唯一恢复路径。 |
| B3 本人列表N+1查询 | low | 确实每份申请查询等待项；本地H2、小型顺序演示无网络往返或性能承诺。批量查询需要额外组装分支，拒绝为本故事增加复杂度。 |
| B4 本人历史未分页 | low | 当前读取全部本人记录属实；课程演示规模及规格未定义分页，增加游标公共接口不是直接修正，拒绝本轮扩展。 |
| B5 数据库/事务异常缺少服务端原因日志 | low | Controller捕获后只返回503，确实丢失此操作的异常上下文。patch：增加不含凭据或会话的服务端异常日志，外部仍为空503。 |
| B6 V2升级测试未先放旧账号 | low | 现有测试在V3后才seed，不能证明V2数据保留。patch：在V2阶段seed，迁移后核对原账号与凭据并继续申请。 |
| B7 测试临时文件没有删除 | low | 测试确实创建专用临时文件，进程结束后连接关闭；为本机少量测试加入跨Spring缓存生命周期的删除逻辑复杂，拒绝本轮增加清理框架。 |
| B8 编号追溯不足 | low | 正式epics/PRD及context引用保留原编号，未丢失需求；按审阅规则拒绝修改本构建规格的建议，直接在证据补FR/AC/NFR/AD来源及部分交付边界。 |
| B9 前端关键交互缺自动回归 | low | 前端无自动测试属实，但实际Edge操作已覆盖提交禁用、独立重复和错误状态；新增浏览器测试基础设施超出直接修正，保留现有实际页面证据。 |
| E1 首次列表延迟/失败覆盖新列表 | medium | 与B1同根因，合并同一patch；禁止列表加载时提交即可消除当前唯一重叠入口，刷新按钮已有loading/submitting禁用。 |
| V1 多GPU前端序列化没有自动测试 | low | 按预核实报告确认自动覆盖缺口；实际页面多卡提交及SQL已对照两卡，当前append实现正确。无需为假设未来替换成set而新增前端测试框架。 |
| V2 列表失败页面没有自动测试 | low | 按预核实报告确认缺口；已有真实停服点击刷新、错误及非空态检查，后端503测试通过。增加自动框架不是直接修正，保留手动验收边界。 |
