---
title: 'Story 2.2 按卡分配普通申请并查看本人名额'
type: 'feature'
epic: 2
story: '2.2'
story_key: 2-2-按卡分配普通申请并查看本人名额
created: '2026-11-25'
status: 'done'
approved: '2026-11-25'
route: 'dispatch'
baseline_commit: 4515b8df0f3cf9a177752897de3cebbbafd64804
review_loop_iteration: 0
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.1 已交付普通申请入队与本人等待记录查看，但空闲 GPU 尚不能按队列规则自动分配，成员也无法区分待分配与已获配名额、查看各卡独立到期时间及获配站内消息。

**Approach:** 通过唯一名额应用服务在普通申请入队后、每 60 秒定时扫描及应用启动补查时，按单卡空闲映射与统一普通队列排序策略执行同事务名额分配（含等待项状态更新、分配历史、唯一当前映射、持久化转换序号审计与本人获配站内消息），并提供本人名额与站内消息只读 API 及前端展示，落实 `epics.md` Story 2.2 全部十组验收（FR-002、004、006–008、015–017，NFR-001、002，AD-1–6、8）。

## Boundaries & Constraints

**Always:**
- 所有分配写入必须经过 Spring 事务代理的 `AllocationService`（`@Transactional(rollbackFor = Exception.class)`），等待项转 `ALLOCATED`、分配历史、唯一当前映射、审计（带持久化递增 `transition_seq`）及获配站内消息同事务提交，任一必需写入或受检异常失败时整体回滚。
- `current_allocations` 以 `gpu_id` 为主键、`allocation_id` 唯一，并以 `(allocation_id, gpu_id)` 外键匹配同卡 `allocations(id, gpu_id)`；`allocations` 以 `(queue_item_id, gpu_id)` 外键匹配 `queue_items(id, gpu_id)`，保证指定卡一致与同卡唯一有效名额。
- 排序策略与评价时间统一：依次为等待严格超过 72 小时（`submitted_at + 72h < evaluationTime`，恰好 72 小时不提升）及其余普通等待项，组内按原 `submitted_at` 升序、`receive_seq` 升序；部分获配不重置其余等待卡原时间与序号，不按同人合并或加减权重，忙碌卡队首不阻塞其他空闲卡。
- 每张获配卡独立使用本次分配时刻计算 `expires_at = allocated_at + duration_hours`；API 标识与序号均为字符串，时间采用 UTC ISO-8601 带 `Z`，前端按 `Asia/Shanghai` 显示，整单状态仅按卡展示，不设整单全获配标志。
- 后端严格校验 Session、`MEMBER` 角色、本人归属及写入 CSRF；非本人、非法 UUID 或不存在的单条详情统一返回空体 `404`；数据库/事务失败记录不含凭据或会话的服务端日志并返回空体 `503`，前端显式提示错误与重试，不伪装为空态或虚构名额。

**Never:**
- 不修改已交付的 `V1`、`V2`、`V3` 迁移脚本，不访问或改动 `.local-data/dev`、`.local-data/demo` 数据目录，不将临时库、测试夹具或本地课程材料纳入 Git。
- 不在本故事预建紧急审批、人工调序、续期、开始/归还/到期释放、消息标记已读或通用命令幂等表；`init-admin` 非 Web 模式不触发后台分配扫描；页面不将获配名额描述为硬件实际空闲或训练任务已启动。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 入队即分配与部分获配 | `POST /api/requests` 选择忙碌 GPU 2 与空闲 GPU 3（24h） | `201`：GPU 3 为 `ALLOCATED`（含字符串 `allocationId`、带 `Z` 的 `allocatedAt`/`expiresAt`、`allocationStatus=UNSTARTED`），GPU 2 保持 `WAITING` 且保留原 `submittedAt`/`receiveSeq`，同事务写入 1 条映射、审计和获配消息 | 任一必需写入或受检异常失败则整单回滚并返回空体 `503` |
| 跨卡独立与多卡持有 | 甲排忙碌 GPU 2，乙排空闲 GPU 3；或已有 GPU 1 名额成员再申空闲 GPU 4 | 乙正常获配 GPU 3，不被甲阻塞；已有其他卡名额成员正常获配 GPU 4 | N/A |
| 排序、并列、同人重复与 72h 边界 | 同卡多条 `WAITING`：相同 `submittedAt`、同人重复排同卡、等待恰好 72h 与严格超过 72h | 严格超过 72h 优先，恰好 72h 仍按普通组；组内按 `submittedAt`、`receiveSeq` 选唯一合法首项获配，其余继续 `WAITING` | N/A |
| 多卡异时获配独立计时 | 同申请 GPU 2 在 `t2` 获配、GPU 3 在 `t1` 获配 | 两卡各自保存 `t1`/`t2` 分配时刻并分别加 `durationHours` 计算 `expiresAt`，互不共用起点 | N/A |
| 重复触发、定时扫描与启动补查 | 已有名额卡重复扫描；或停机前已提交 `WAITING`，重启后 `ApplicationReadyEvent` 补查 / 60s 扫描 | 已占用卡返回无变化，不产生第二份名额、审计或消息；空闲有等待项的卡自动为合法首项分配一次 | 单卡后台扫描异常记录日志并回滚该卡事务，不留半成品映射 |
| 本人名额与消息查询及隔离 | `GET /api/allocations`、`/api/allocations/{id}`、`/api/messages`、`/api/messages/{id}` | 仅返回当前登录 `MEMBER` 本人记录；单条详情对他人、不存在或非法 UUID 均返回空体 `404` | 匿名 `401`、管理员 `403`、数据库故障返回空体 `503` |

</frozen-after-approval>

## Code Map

- `backend/src/main/resources/db/migration/V3__requests.sql` -- 既有 `requests` 与 `queue_items`（含未命名内联 `CHECK (status IN ('WAITING'))`，需在 `V4` 重建表保留原数据并扩展状态为 `'WAITING', 'ALLOCATED'` 及增加 `UNIQUE (id, gpu_id)`，禁止改动 `V3`）。
- `backend/src/main/java/org/lab/gpuqueue/GpuQueueApplication.java` -- `init-admin`（`WebApplicationType.NONE`）与 `serve` 启动入口；`init-admin` 应禁用扫描，保持 `serve` 启动补查与 60 秒扫描生效。
- `backend/src/main/java/org/lab/gpuqueue/allocation/AllocationService.java` -- 唯一名额写入口；扩展入队后按卡分配触发、单卡空闲分配事务及本人名额/消息只读查询。
- `backend/src/main/java/org/lab/gpuqueue/allocation/RequestRepository.java`、`RequestController.java` -- 本人申请读写；扩展 `QueueItemView` 在 `ALLOCATED` 时带出同卡名额与期限（`WAITING` 时不带空字段），复用服务端 `503` 异常日志模式。
- `backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java` -- 默认拒绝未声明 `/api/**`，需显式向 `MEMBER` 开放 `/api/allocations`、`/api/allocations/*`、`/api/messages`、`/api/messages/*`。
- `frontend/src/api.ts`、`frontend/src/OrdinaryRequests.vue`、`frontend/src/App.vue` -- 扩展类型定义与 API 调用，修正受理回执与申请列表中的逐卡状态展示，新增“我的名额”与“站内消息”区域（含各自加载/空态/错误重试及防重叠提交禁用）。
- `backend/src/test/java/org/lab/gpuqueue/allocation/RequestIntegrationTest.java` -- 既有 18 项测试入口；清理顺序需先删 `V4` 子表，并更新空闲卡入队即获配与忙碌卡保持 `WAITING` 的断言，不破坏原有校验、权限、回滚和重启覆盖。

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V4__allocations.sql` -- 无损升级 `queue_items` 支持 `'WAITING'`/`'ALLOCATED'` 与 `UNIQUE (id, gpu_id)`，新建 `allocations`、`current_allocations`（`gpu_id` 主键、`allocation_id` 唯一及同卡外键）、`audit_logs`（`transition_seq` identity 主键）与 `inbox_messages`（关联转换序号与名额唯一约束） -- 落实同卡唯一、指定卡一致及必需记录持久化约束。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/QueueOrderingPolicy.java` -- 实现无 HTTP 依赖的普通队列排序策略（严格超过 72 小时提升、原提交时间与接收序号排序） -- 供分配与后续公开队列共享同一规则与评价时间。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/AllocationRepository.java`、`backend/src/main/java/org/lab/gpuqueue/allocation/RequestRepository.java`、`backend/src/main/java/org/lab/gpuqueue/allocation/AllocationService.java` -- 实现空闲卡候选发现、单卡同事务分配（更新等待项、写历史/当前映射/审计/站内消息）、入队后触发及本人申请、名额、消息查询 -- 保证唯一写入口与完整回滚语义。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/AllocationScanner.java`、`backend/src/main/java/org/lab/gpuqueue/allocation/SchedulingConfiguration.java`、`backend/src/main/java/org/lab/gpuqueue/GpuQueueApplication.java` -- 配置每 60 秒定时扫描与 `ApplicationReadyEvent` 启动补查，并在 `init-admin` 模式关闭扫描 -- 覆盖停机恢复与空闲补分配且不影响受控初始化。
- [x] `backend/src/main/java/org/lab/gpuqueue/allocation/AllocationController.java`、`backend/src/main/java/org/lab/gpuqueue/security/SecurityConfiguration.java` -- 暴露本人名额与站内消息只读接口并配置 `MEMBER` 鉴权与 `404`/`503` 处理 -- 落实查询隔离与故障透明反馈。
- [x] `frontend/src/api.ts`、`frontend/src/OrdinaryRequests.vue` -- 更新逐卡状态回执与“我的申请”展示，新增“我的名额”和“站内消息”面板（上海时区、使用权边界提示、独立错误重试与加载期防重叠保护） -- 让成员直观区分待分配与已获配名额及各自期限。
- [x] `backend/src/test/java/org/lab/gpuqueue/allocation/RequestIntegrationTest.java`、`backend/src/test/java/org/lab/gpuqueue/allocation/AllocationIntegrationTest.java` -- 适配 `V4` 清理顺序，新增覆盖矩阵全部场景（含受检异常与审计/消息写入失败回滚、数据库同卡外键/唯一约束、`V3→V4` 升级及停机提交后重启补查）的独立 H2 文件库测试 -- 以真实事务与安全过滤链验证十组验收。
- [x] `docs/development/runbook.md`、`_bmad-output/implementation-artifacts/evidence/story-2.2.md`、`HANDOFF.md` -- 记录构建、集成测试、独立测试库浏览器实际运行及停服重启补查证据，同步交接说明 -- 保持可复核交付记录。

**Acceptance Criteria:**
- Given 空闲与占用混合的多卡及跨卡普通申请，when 提交、刷新或经定时/启动补查处理，then 仅空闲卡合法首项获配唯一有效名额并生成同事务审计与站内消息，忙碌卡保持原时间与序号继续等待，且各获配卡独立计算到期时间。
- Given 分配事务中发生受检异常、审计或消息写入失败，或对已获配卡重复扫描/重启补查，when 核对数据库与接口，then 失败时整体回滚且恢复后可正常分配一次，已获配卡重复处理零副作用。
- Given 成员、其他成员、管理员及匿名请求访问本人名额与消息接口或页面，when 执行正常查看、越权读取及故障重试，then 严格隔离本人数据且页面清晰呈现上海时间、名额编号与使用权说明。

## Implementation Notes

- 2026-11-25：完成 `V4__allocations.sql`、`QueueOrderingPolicy`、`AllocationRepository`、`AllocationService`、`AllocationScanner`/`SchedulingConfiguration`/`GpuQueueApplication`、`AllocationController`、安全过滤链授权及前端“我的名额”/“站内消息”/逐卡受理状态更新；`AllocationRepository` 采用无冗余代理的 `@Component` 使受检异常可直达 `AllocationService` 的 `@Transactional(rollbackFor = Exception.class)` 事务切面并完整回滚。
- 2026-11-25：`npm.cmd --prefix frontend run build` 与离线 `mvn.cmd ... verify` 全部通过（`RequestIntegrationTest` 18 项 + `AllocationIntegrationTest` 8 项 = 26 项，0 失败/错误/跳过）；使用独立临时 H2 文件库完成浏览器部分获配、同库隔离、异时获配独立期限、停服独立报错重试、停服写入等待项后重启 `ApplicationReadyEvent` 自动补查及管理员建号回归验证，详见 [evidence/story-2.2.md](evidence/story-2.2.md)。测试服务已停止，未提交或推送。
- 2026-11-26：完成三层独立审阅（22 条逐项裁决，无 `intent_gap`、`bad_spec` 或 `defer`）并落地局部加固：入队前按 `gpuId` 升序加锁、`queue_items(status, gpu_id)` 索引、仓储缺失记录显式抛 `DataIntegrityViolationException` 统一走 `503`、`GpuQueueApplication.startMode` 可测入口、前端状态/时间兜底与站内消息申请编号展示，以及测试补齐失效历史名额 `404` 过滤、审计/消息全字段与倒序、独立唯一/同卡外键约束及 `serve`/`init-admin`/`SERVLET` 禁扫断言。主线重新执行 `npm.cmd --prefix frontend run build`（JS `84.97 kB`、CSS `2.70 kB`）与离线 Maven `verify`（26 项全通过，0 失败/错误/跳过）并重打同源 `gpu-queue.jar`；规格标 `done`、冲刺标 `review`。

## Spec Change Log

## Review Triage Log

2026-11-25：三个独立审阅层（`blind-hunter`、`edge-case-hunter`、`verification-gap`）全部返回，共 22 条发现逐项裁决如下；合并同根因项后均路由至 `patch`，无 `intent_gap`、`bad_spec` 或 `defer`。

| 发现 | 裁决 | 核对证据与处理 |
| --- | --- | --- |
| B1 `submitOrdinaryRequest` 在插入申请后才取 GPU 行锁 | low | `AllocationService.java:57-61` 先调无锁 `allGpusExist` 与 `requests.create` 再逐卡 `lockGpu`。patch（与 E1 同根因）：在 `clock.instant()` 与 `requests.create` 前按 `gpuId` 升序调用 `allocations.lockGpu` 校验存在并加锁。 |
| B2 `queue_items_gpu_status_idx` 索引列顺序对空闲扫描次优 | low | `V4__allocations.sql:20` 定义 `(gpu_id, status)`，而 `findIdleCandidateGpuIds` 仅按 `status = 'WAITING'` 过滤并按 `gpu_id` 排序。patch：改为 `(status, gpu_id)` 同时覆盖空闲扫描与单卡候选查询。 |
| B3 扫描器缺少启动补查与定时任务重叠保护及多线程竞争测试 | low | 默认 `initialDelay` 为 60000ms，顺序演示下不与启动补查重叠；多线程并发压力测试已由 D047/D048 与 `epic-2-context.md` 明确排除，拒绝新增并发控制与压力测试复杂度。 |
| B4 `allocateGpuIfIdle` 末尾回查未强校验非空 | low | `AllocationService.java:144` 直接返回 `findAllocationForMember(...)` 的 `Optional`，若回查异常缺失会提交写入却返回空。patch：用 `.orElseThrow(() -> new DataIntegrityViolationException(...))` 强制校验后包装返回。 |
| B5 控制器重复异常处理器、泛型擦除且仓储用 `requireNonNull` 抛 `NPE` | medium | `RequestRepository.java:89` 与 `AllocationRepository.java:138` 在记录缺失时抛 `NullPointerException`，会绕过 `503` 处理器（与 E2 同根因），且 `UndeclaredThrowableException` 未限定受检异常范围。patch：改为显式抛 `DataIntegrityViolationException`，收敛 `AllocationController` 具体返回类型并校验 `UndeclaredThrowableException` 原因。 |
| B6 `V4` 审计/消息跨表冗余约束建议及消息唯一约束测试混用 | medium | `audit_logs` 与 `inbox_messages` 仅由唯一服务写入且已具同卡复合外键，无需再叠加四列跨表键；但 `AllocationIntegrationTest.java:549-565` 同时复用已有 `transition_seq` 和 `allocation_id` 属实（与 V4 同根因）。patch：拆分独立断言分别验证 `inbox_messages_allocation_uk`、`inbox_messages_transition_uk`、`inbox_messages_allocation_gpu_fk` 及 `audit_logs` 约束。 |
| B7 前端状态格式化恒等分支、模板缺 `allocationId` 判断与消息缺 `requestId` | medium | `OrdinaryRequests.vue:65` 三元分支相同，`:216/:283` 未检查 `item.allocationId` 且 `v-else` 会把缺字段的非 `WAITING` 项渲染为 `待分配`（与 E3 同根因），`formatTime` 未防非法日期，站内消息未展示 `requestId`。patch：修正状态与时间函数、显式区分 `ALLOCATED`/`WAITING`/异常兜底并在站内消息展示申请编号。 |
| B8 面板刷新未单独拦截 401、回执不随刷新联动及加载禁用输入焦点 | low | `App.vue` 已提供退出与整页刷新 401 恢复路径，加载期禁用提交 `fieldset` 为防读写重叠的既定设计（延续 Story 2.1 B1/E1）；跨子面板增加会话状态机超出直接修正，拒绝本轮扩展。 |
| B9 缺少前端自动化组件测试框架 | low | 仓库按轻量本地演示范围未引入 Vitest/JSDOM，实际浏览器已逐项覆盖三面板加载、部分获配、独立报错重试与重启补查；引入新前端测试框架超出直接修正，拒绝扩展。 |
| B10 集成测试缺少名额/消息全字段断言、`main` 模式入口覆盖及排序策略独立断言 | medium | `AllocationIntegrationTest` 对 `/api/allocations`、`/api/messages` 详情字段及 `QueueOrderingPolicy.order` 独立提升顺序断言不完整（与 V2、V5 部分同根因）。patch：补齐列表与详情全字段断言、策略层直接断言及应用模式入口测试。 |
| B11 `@SpringBootTest` 默认启用 60s 定时任务存在慢机竞态及系统临时库未删除 | medium | 慢速环境下 60s 定时扫描可能在测试中途触发（与 E4 同根因），patch：在 `RequestIntegrationTest` 与 `AllocationIntegrationTest` 设置长 `initial-delay-ms` 隔离背景触发；Windows 下缓存 DataSource 持有 H2 文件句柄，延续 Story 2.1 B7 不引入复杂跨生命周期清理框架。 |
| B12 `sprint-status.yaml` 在审阅阶段仍为 `in-progress` | false | 按 BMAD `step-03`/`step-04`/`step-05` 流程规范，审阅期间冲刺状态保持 `in-progress`（该文件无 `in-review` 枚举），待 `step-05` 收尾时统一更新为 `review`。 |
| E1 并发入队在 `requests.create` 与 `lockGpu` 之间交错 | low | 与 B1 同根因；通过入队前按 `gpuId` 升序执行 `lockGpu` 一并消除。 |
| E2 `ALLOCATED` 等待项缺失关联名额时抛 `NullPointerException` 逃逸 `503` | medium | 与 B5 同根因；`RequestRepository.readQueueItem` 与 `AllocationRepository.insertAuditLog` 改为抛 `DataIntegrityViolationException`，确保统一返回空体 `503`。 |
| E3 `OrdinaryRequests.vue` 对缺字段 `ALLOCATED` 项渲染空编号或误标为 `待分配` | medium | 与 B7 同根因；补充 `item.allocationId` 判断并拆分 `v-else-if="item.status === 'WAITING'"` 与兜底分支。 |
| E4 `AllocationIntegrationTest` 运行超 60 秒时背景定时扫描干扰断言 | medium | 与 B11 同根因；在测试动态属性中设置 `queue.scanner.initial-delay-ms=86400000` 消除非确定性触发。 |
| V1 本人名额查询的 `current_allocations` 有效过滤未对已失效历史名额断言 | medium | 预核实确认：移除 `AllocationRepository.java:176,197` 的 `JOIN current_allocations` 后原 26 项测试仍通过。patch：在 `computesIndependentAllocationAndExpiryTimes...` 删除 GPU 2 当前映射后断言原持有者 `GET /api/allocations` 为空且 `GET /api/allocations/{bobAllocationId}` 返回空体 `404`。 |
| V2 站内消息接口字段完整性与多条消息按 `transitionSeq` 倒序未断言 | medium | 预核实确认：原测试未断言 `requestId`、`title`、`body`、`allocatedAt`、`expiresAt`、`createdAt` 及多消息倒序。patch：在 `AllocationIntegrationTest` 补齐列表与详情全部字段及双消息 `transitionSeq` 倒序断言。 |
| V3 `audit_logs` 持久化字段仅验证行数而未核对内容与转换序号关联 | medium | 预核实确认：原测试仅断言 `COUNT(*)`。patch：在 `AllocationIntegrationTest` 查询持久化 `audit_logs` 记录，逐字段核对 `occurred_at`、`actor_type`、`actor_account_id`、`action`、`request_id`、`queue_item_id`、`allocation_id`、`gpu_id`、`before_state`、`after_state` 及与 `inbox_messages.transition_seq` 的一致性。 |
| V4 `inbox_messages` 约束测试同时混用重复 `transition_seq` 与 `allocation_id` 且漏测同卡外键 | medium | 预核实确认（与 B6 同根因）。patch：为第二条历史名额写入独立 `audit_logs` 获取未用 `transition_seq`，分别独立断言 `inbox_messages_allocation_uk`、`inbox_messages_transition_uk` 与 `inbox_messages_allocation_gpu_fk`。 |
| V5 `GpuQueueApplication` 的 `serve`/`init-admin` 启动参数接线与 `SERVLET` 下 `queue.scanner.enabled=false` 未被测试直调 | medium | 预核实确认（与 B10 部分同根因）。patch：提取包级可测启动方法供 `main` 与集成测试共用，验证 `init-admin` 不注册扫描器、`serve` 默认注册并执行启动补查，以及 `SERVLET` 显式 `--queue.scanner.enabled=false` 时不注册扫描器与调度配置。 |
| V6 前端组件缺少自动化测试运行器 | low | 预核实确认（与 B9 同根因）；轻量本地演示不新增前端测试框架，保留 `vue-tsc` 构建检查与独立测试库实际浏览器验证证据。 |

## Design Notes

`V3` 的 `queue_items` 使用了未命名内联 `CHECK (status IN ('WAITING'))`，H2 自动生成约束名无法静态硬编码删除；`V4` 通过新建带具名约束的 `queue_items_v4`、全量拷贝 `V3` 数据、删除旧表并重命名为 `queue_items` 完成无损升级，随后建立 `allocations` 与 `current_allocations` 的同卡复合外键。`QueueItemView` 对 `WAITING` 项通过 `@JsonInclude(JsonInclude.Include.NON_NULL)` 省略未发生的名额字段，既不返回虚构字段也兼容已有等待项结构。

## Verification

**Commands:**
- `npm.cmd --prefix frontend run build` -- expected: `vue-tsc --build` 与 Vite 生产构建零错误通过
- `mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify` -- expected: `RequestIntegrationTest` 与 `AllocationIntegrationTest` 全部通过（0 失败/错误/跳过），生成包含最新前端静态资源的 `backend/target/gpu-queue.jar`

**Manual checks (if no CLI):**
- 使用独立临时 H2 文件库启动同源 JAR，通过浏览器依次验证成员部分获配、不同时刻获配独立期限、本人名额与站内消息显示、他人在同库隔离、读取失败重试，以及停服写入等待项后重启 JAR 自动补查获配。
