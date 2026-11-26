# Story 2.2 实现与验证记录

日期：2026-11-26（Asia/Shanghai）。基于已批准规格 `spec-2-2-allocate-ordinary-requests.md` 完成实现、构建、26 项独立临时 H2 文件库集成测试、本机浏览器与停服重启补查实际验证，以及三层独立代码审阅与主线全量复核。证据不含口令、散列、CSRF 或 Session 值。

## 实现范围与环境

- 基线：`4515b8df0f3cf9a177752897de3cebbbafd64804`，分支 `codex/story-2-2`。保留本地未跟踪课程材料，未访问或修改 `.local-data/dev`、`.local-data/demo`。本轮普通开发不属于课程实验 A。
- 实施前完整读取 `spec-2-2-allocate-ordinary-requests.md`、`AGENTS.md`、`epic-2-context.md`、`HANDOFF.md`、`sprint-status.yaml`、`SPEC.md` 及其全部配套文档，并逐项对照 `epics.md` Story 2.2 十组验收标准。
- `V4__allocations.sql`：在不修改 `V1`–`V3` 的前提下，通过新建具名约束表 `queue_items_v4`、全量迁移 `V3` 数据、删除旧表并重命名为 `queue_items`，无损扩展状态为 `'WAITING' | 'ALLOCATED'` 并补充 `UNIQUE (id, gpu_id)`；建立 `allocations`、`current_allocations`（`gpu_id` 主键、`allocation_id` 唯一及 `(allocation_id, gpu_id)` 同卡复合外键）、`audit_logs`（`transition_seq` H2 identity 主键）和 `inbox_messages`（`transition_seq` 外键及唯一约束、`allocation_id` 唯一约束及同卡复合外键）。
- `QueueOrderingPolicy`：无 HTTP 依赖，按严格超过 72 小时（`submitted_at + 72h < evaluationTime`，恰好 72 小时不提升）与普通等待项分组，组内按原 `submitted_at` 升序、`receive_seq` 升序选择唯一合法队首；不按同人合并或加减权重。
- `AllocationService`：唯一名额写入口（`@Transactional(rollbackFor = Exception.class)`），在普通申请入队后、每 60 秒定时扫描及 `ApplicationReadyEvent` 启动补查时执行单卡空闲判断与同事务写入（更新 `queue_items`、插入 `allocations`、`current_allocations`、`audit_logs` 和 `inbox_messages`）；任一必需写入或受检异常失败均整体回滚。`GpuQueueApplication` 在 `init-admin`（`WebApplicationType.NONE`）显式关闭扫描，`serve` 保持启动补查与 60 秒定时扫描。
- `AllocationController`、`RequestController`、`SecurityConfiguration` 与前端 `api.ts`、`OrdinaryRequests.vue`：向 `MEMBER` 开放 `/api/allocations`、`/api/allocations/*`、`/api/messages`、`/api/messages/*`，`QueueItemView` 对 `WAITING` 省略未发生名额字段，前端更新受理回执与“我的申请”逐卡展示并新增“我的名额”与“站内消息”面板（上海时区、使用权边界提示、独立错误重试及加载期提交禁用）。
- 实际运行工具：Java 21.0.2、Maven 3.9.6、Node.js v24.18.0、npm 11.16.0；Spring Boot 3.3.13、Spring Security 6.3.10、H2 2.2.224、Flyway 10.10.0、Vue 3.5.45、Vite 8.3.8、TypeScript 6.0.3。

## 构建与集成测试：实际通过

实际顺序执行：

```powershell
npm.cmd --prefix frontend run build
mvn.cmd -B -ntp -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify
```

- 前端构建：`vue-tsc --build` 与 `vite build` 零错误通过（23 个模块，修正复核产物 CSS `2.70 kB`，JS `84.97 kB`）。
- 后端集成测试与打包：Maven `verify` 零错误通过，生成包含最新前端静态资源的同源 `backend/target/gpu-queue.jar`。
  - `RequestIntegrationTest`：18 tests，0 failures，0 errors，0 skipped（4.372 s）。
  - `AllocationIntegrationTest`：8 tests，0 failures，0 errors，0 skipped。
  - 合计：26 tests，0 failures，0 errors，0 skipped（报告位于 `backend/target/surefire-reports/`）。
- 测试覆盖要点：
  - 入队即分配与部分获配：忙碌卡保持 `WAITING`（仅 3 个字段，保留原 `submittedAt`/`receiveSeq`），空闲卡转 `ALLOCATED`（含字符串 `allocationId`、带 `Z` 的 `allocatedAt`/`expiresAt`、`allocationStatus=UNSTARTED`）并同事务写入映射、审计和获配消息。
  - 跨卡独立与多卡持有：忙碌卡等待不阻塞其他空闲卡；已持有其他卡名额的成员可继续获配新空闲卡。
  - 排序与 72h 边界：严格超过 72 小时（`+1ns`）提升而恰好 72 小时不提升，同组按 `submitted_at`、`receive_seq` 排序，同人重复排同卡独立按序获配，占用期间重复触发零副作用。
  - 多卡异时获配独立计时：同一申请两卡在 `t1`、`t2` 获配时各自按分配时刻加 `durationHours` 计算 `expiresAt`。
  - 事务回滚：覆盖多卡入队审计失败回滚、真实受检异常（`IOException` 经未代理 `AllocationRepository` 直达 `AllocationService` Spring 事务代理）导致 `POST /api/requests` 返回空体 `503` 及 `allocateIfIdle` 整体回滚、后台扫描单卡站内消息失败回滚且不影响同轮其他空闲卡分配，以及故障恢复后正常分配一次。
  - 权限隔离与数据库约束：验证匿名 `401`、管理员 `403`、他人/不存在/非法 UUID 空体 `404`、写方法 `403`、数据库故障空体 `503`，以及 `queue_items`、`allocations`、`current_allocations`、`audit_logs`、`inbox_messages` 的同卡复合外键与唯一约束。
  - 升级与恢复：验证 `V3→V4` 无损迁移、`init-admin` 非 Web 模式不扫描、`ApplicationReadyEvent` 启动补查自动分配、重复重启零重复副作用，以及 `@Scheduled` 定时扫描自动补分配。

## 浏览器与独立文件库实际运行（含停服重启补查）：实际通过

使用独立临时 H2 文件库 `.local-data/story22-page-74717502-33fa-4095-8886-80fb51921c20/gpu-queue.mv.db` 启动 `backend/target/gpu-queue.jar`，通过内置浏览器访问 `http://127.0.0.1:8080/` 完成全流程实测，验收后已退出登录并停止测试服务。

| 顺序 | 实际操作 | 页面与系统观察结果 |
| --- | --- | --- |
| 1 | `story22-member-b` 登录并提交 `GPU 2`（`12` 小时） | 初始三面板均为空态；提交后回执、“我的名额”、“我的申请”和“站内消息”一致显示 `GPU 2` `获配未开始`、名额编号 `c8ff3d86-4392-4411-a552-63e103c011fe`、上海时间 `2026/11/25 21:06:14` 至 `2026/11/26 09:06:14` 及使用权边界提示；随后退出登录 |
| 2 | `story22-member-a` 同库登录核对隔离 | “我的名额”、“我的申请”、“站内消息”均为空态，不可见 `story22-member-b` 的任何申请、名额或消息 |
| 3 | `story22-member-a` 提交忙碌 `GPU 2` + 空闲 `GPU 3`（`24` 小时） | 申请 `0d594506-c648-413b-94ea-64e5ff19d2c3`（接收序号 `2`）：`GPU 2：待分配`，`GPU 3：已获配（获配未开始）`（名额 `e0a8ccef-a04a-42ab-b669-40c84bf2dd6a`，`2026/11/25 21:06:51` 至 `2026/11/26 21:06:51`）；页面无整单“全部获配”标志；“我的名额”仅列 `GPU 3`，“站内消息”生成转换序号 `2` 的通知 |
| 4 | 保持页面打开并停服，先点“刷新我的名额”，再点“刷新我的申请”和“刷新站内消息” | 首次点击仅“我的名额”变为错误提示 `无法读取我的名额，请重试。` 与按钮 `重试加载我的名额`，其余两面板保持原内容；随后点击其余两按钮分别独立显示 `无法读取我的申请，请重试。` 与 `无法读取站内消息，请重试。`，均未伪装为空态 |
| 5 | 停服期间在测试库移除 `GPU 2` 当前映射并插入 `GPU 5`（`6` 小时）`WAITING` 申请 `1fea12c6-d929-428f-b988-bae8e488eaa4`，随后重启 `serve` | 刷新页面后旧会话失效返回登录框；重新登录 `story22-member-a` 后，`ApplicationReadyEvent` 启动补查已自动完成 `GPU 2` 与 `GPU 5` 分配：“我的名额”列出 `GPU 5`、`GPU 2`、`GPU 3` 三条有效记录，“站内消息”列出对应三条通知 |
| 6 | 核对同一申请异时获配独立期限（`t1` vs `t2`） | 申请 `0d594506-c648-413b-94ea-64e5ff19d2c3` 下 `GPU 3` 保持 `2026/11/25 21:06:51` → `2026/11/26 21:06:51`，`GPU 2` 独立为 `2026/11/25 21:10:19` → `2026/11/26 21:10:19`，离线等待申请 `GPU 5` 为 `2026/11/25 21:10:19` → `2026/11/26 03:10:19` |
| 7 | `story22-admin` 登录验证角色边界与建号回归 | 管理员页面不挂载“普通申请”、“我的名额”、“我的申请”、“站内消息”；创建成员 `story22-created` 成功，随后退出登录并停止测试 JAR |

停服只读 SQL 与页面上海时间逐笔对照：

| 归属成员 | 申请 ID / 接收序号 | GPU | 名额 ID / 转换序号 | H2 `allocated_at` → `expires_at`（UTC） | 页面显示分配 → 到期（Asia/Shanghai） |
| --- | --- | --- | --- | --- | --- |
| `story22-member-b` | `313e16d2-624e-4cd7-bff8-50403524bc52` / `1` | GPU 2 | `c8ff3d86-4392-4411-a552-63e103c011fe` / `1` | `2026-11-25 13:06:14.9297039+00` → `2026-11-26 01:06:14.9297039+00` | `2026/11/25 21:06:14` → `2026/11/26 09:06:14` |
| `story22-member-a` | `0d594506-c648-413b-94ea-64e5ff19d2c3` / `2` | GPU 3 | `e0a8ccef-a04a-42ab-b669-40c84bf2dd6a` / `2` | `2026-11-25 13:06:51.6802539+00` → `2026-11-26 13:06:51.6802539+00` | `2026/11/25 21:06:51` → `2026/11/26 21:06:51` |
| `story22-member-a` | `0d594506-c648-413b-94ea-64e5ff19d2c3` / `2` | GPU 2 | `6924ffad-81e5-42b5-a0be-ac5c267251a5` / `33` | `2026-11-25 13:10:19.2722004+00` → `2026-11-26 13:10:19.2722004+00` | `2026/11/25 21:10:19` → `2026/11/26 21:10:19` |
| `story22-member-a` | `1fea12c6-d929-428f-b988-bae8e488eaa4` / `33` | GPU 5 | `94150cf9-1f39-4805-bd6c-47650ee41d2c` / `34` | `2026-11-25 13:10:19.3154218+00` → `2026-11-25 19:10:19.3154218+00` | `2026/11/25 21:10:19` → `2026/11/26 03:10:19` |

（注：H2 `GENERATED ALWAYS AS IDENTITY` 在进程重启后跨越预分配缓存段，`33`/`34` 体现跨重启严格递增且允许空洞。）

## 十组验收跟踪（`epics.md` Story 2.2）

| `epics.md` Story 2.2 验收组 | 关联 FR / AC / NFR / AD（本故事交付边界） | 验证证据 |
| --- | --- | --- |
| 1. 空闲卡按原提交时间与接收序号免审批分配 | FR-002 / AC-002-1，FR-003 / AC-003-1，FR-007 / AC-007-1；AD-1、2、6 | `RequestIntegrationTest`、`AllocationIntegrationTest` 及浏览器实测通过 |
| 2. 跨卡独立排队，忙碌卡不阻塞其他空闲卡 | FR-006 / AC-006-1；AD-2、6 | `AllocationIntegrationTest#doesNotBlockOtherIdleCardsAndAllowsMemberToHoldMultipleCards` 通过 |
| 3. 多卡部分获配、保留等待年龄及多卡同时持有 | FR-004 / AC-004-1，FR-006 / AC-006-2，FR-007 / AC-007-2，FR-017 / AC-017-1；AD-2、6 | 集成测试与浏览器部分获配核对通过；整单不设全部获配标志 |
| 4. 同人重复同卡、时间并列与严格超过 72 小时提升 | FR-004 / AC-004-2，FR-006 / AC-006-3，FR-007 / AC-007-2、AC-007-3；AD-3、6 | `AllocationIntegrationTest#ordersByStrict72HourBoostOriginalSubmissionTimeAndReceiveSeqWithoutSameMemberBias` 通过（紧急与人工调序留待 Epic 3） |
| 5. 多卡异时获配各自独立计算到期时间与时区展示 | FR-008 / AC-008-1，FR-017 / AC-017-2（未开始状态部分）；AD-4、8 | 集成测试与停服重启浏览器 `t1`/`t2` 逐笔对照通过 |
| 6. 同卡唯一映射、复合外键与唯一获配消息 | FR-015 / AC-015-2（获配部分），FR-016 / AC-016-2；NFR-001（初次分配部分）；AD-2、3、5 | `databaseConstraintsEnforceSameCardCompositeForeignKeysAndUniqueCurrentMapping` 通过 |
| 7. 受检异常及审计/消息写入失败整体回滚并可恢复 | FR-015、FR-016；AD-1、5 | `rollsBackWholeTransactionWhenAuditOrInboxOrCheckedExceptionFailsAndRecoversCleanly` 通过 |
| 8. 已有名额卡重复触发/扫描无副作用 | NFR-001；AD-3、5、6 | 集成测试重复触发及二次重启核对零新增记录 |
| 9. 本人名额/消息隔离、越权拒绝、错误重试与边界说明 | FR-001 / AC-001-4，FR-002 / AC-002-1，FR-015、FR-017；NFR-002（本故事新增接口）；AD-8 | `enforcesMemberIsolation404EmptyBodiesRoleChecksAnd503Failures` 及浏览器同库隔离、停服独立报错重试通过（消息标记已读留待 Story 2.8） |
| 10. 停机前等待项重启启动补查与 60s 定时扫描恢复 | AD-6；AR-11、AR-18 | `upgradesFromV3SkipsNonWebScanAndRecoversWaitingItemsOnStartupAndScheduledScan` 及 JAR 停服写入等待项后重启补查实测通过 |

## 独立代码审阅与主线全量复核

- 三个独立审阅层（`blind-hunter`、`edge-case-hunter`、`verification-gap`）共返回 22 条发现，逐条裁决记录于 `spec-2-2-allocate-ordinary-requests.md` 的 `Review Triage Log`；无 `intent_gap`、`bad_spec` 或 `defer`。
- 已落实的局部加固：
  - `AllocationService#submitOrdinaryRequest` 在取时与入队前先按 `gpuId` 升序执行 `lockGpu` 校验与加锁，`allocateGpuIfIdle` 回查强校验非空。
  - `V4__allocations.sql` 将 `queue_items_gpu_status_idx` 调整为 `(status, gpu_id)`，同时覆盖空闲扫描前缀过滤与单卡候选查询。
  - `RequestRepository` 与 `AllocationRepository` 将缺失记录/主键的不变量检查改为抛 `DataIntegrityViolationException`，收敛 `AllocationController` 具体泛型并限制 `UndeclaredThrowableException` 仅将受检异常映射为空体 `503`。
  - 提取 `GpuQueueApplication.startMode` 供 `main` 与测试共用；`OrdinaryRequests.vue` 修正状态/非法日期兜底、显式区分 `ALLOCATED`/`WAITING`/异常分支并在“站内消息”补充申请编号。
  - `RequestIntegrationTest` 与 `AllocationIntegrationTest` 增加长 `initial-delay-ms` 隔离背景触发，并补齐历史失效名额排除与 `404`、`audit_logs` 全列与 `transition_seq` 关联、站内消息全字段与多条倒序、`inbox_messages`/`audit_logs` 独立唯一与同卡外键约束、`QueueOrderingPolicy.order` 直接排序，以及 `init-admin`、`serve` 与 `SERVLET` 显式 `--queue.scanner.enabled=false` 启动接线断言。
- 2026-11-26 09:22 主线重新执行 `npm.cmd --prefix frontend run build` 与离线 Maven `verify`：26 tests，0 failures，0 errors，0 skipped，重新生成含最新静态资源的 `backend/target/gpu-queue.jar`；`sprint_plan.py validate` 校验 `valid=true`。

## 遗留与边界说明

- 本故事未预建开始（Story 2.3）、归还（Story 2.4）、到期/24h 未开始异常（Story 2.5）、管理员核实释放（Story 2.6）、公开队列（Story 2.7）、消息已读（Story 2.8）或 Epic 3 紧急审批/调序/续期功能。
- 测试服务已停止；临时页面夹具与测试库均位于 `.local-data/` 忽略目录，未纳入 Git。课程实验 A 仍按计划在选定故事实现前准备。
