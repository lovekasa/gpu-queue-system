# Story 2.1 实现与验证记录

日期：2026-11-25（Asia/Shanghai）。规格已经用户批准；实现、运行验证、页面检查及三个独立层代码审阅完成。局部修正后主线全量复核通过，待用户接收及合并。

## 实现范围与环境

- 基线：`f6f4feef12e94af0917dc1cfb99d936ce864382c`，分支 `codex/story-2-1`。保留原有未跟踪课程材料。本轮不属于实验 A。
- 读取规格 frontmatter 全部 context、AGENTS.md、HANDOFF.md、冲刺状态、SPEC 全部 companions，并核对 epics.md Story 2.1 七组验收。
- V3 增加申请和逐卡等待项，使用 UUID、UTC `TIMESTAMP(9) WITH TIME ZONE`、H2 identity 接收序号及 1–72 时长约束；申请内 GPU 唯一，保留账号/GPU 外键。V1/V2 未改写。
- `AllocationService` 是申请唯一写入口，整单事务包含申请及所有等待项。所有查询按 Session 成员 ID 过滤，伪造申请人字段拒绝，非本人和不存在的详情统一 404。只交付 WAITING，无分配、通知或期限副作用。
- 成员页面包含选卡、时长、提交禁用、独立成功回执和本人倒序列表，并区分加载、空态、提交失败及读取失败/重试。时间明确按 Asia/Shanghai 显示。
- 实际工具：Java 21.0.2、Maven 3.9.6、Node 24.18.0、npm 11.16.0；Spring Boot 3.3.13、H2 2.2.224、Flyway 10.10.0、Vue 3.5.45。新增测试依赖由 Boot BOM 管理：starter-test 3.3.13、security-test 6.3.10。

## 构建与集成测试：实际通过

实际执行：

```powershell
npm.cmd --prefix frontend ci --registry https://registry.npmjs.org --cache .local-data/npm-cache
npm.cmd --prefix frontend run build
mvn.cmd -gs .local-data/verification-maven-settings.xml -s .local-data/verification-maven-settings.xml -f backend/pom.xml verify
```

- npm ci 成功；`vue-tsc --build` 和 Vite 构建成功。产物为 23 个转换模块，JS 79.43 kB，CSS 2.42 kB。
- Maven `verify` 成功，`RequestIntegrationTest`：18 tests、0 failures、0 errors、0 skipped，测试执行 9.311 秒。报告：`backend/target/surefire-reports/org.lab.gpuqueue.allocation.RequestIntegrationTest.txt`。
- 测试使用独立临时 H2 文件库和真实 Spring Security 过滤链，通过真实 CSRF 获取及登录建立 Session；无测试外层事务掩盖提交/回滚。可注入时钟验证并列提交时间和递增序号。
- 覆盖合法 1/24/72、单卡/多卡、缺失/重复时长、0/负数/非整数/73/空/非法/溢出时长，无卡/不存在/重复/格式错误 GPU，主动重复申请，归属过滤、匿名/管理员/伪造身份/缺失及无效 CSRF，查询失败 503，后续卡保存失败整单回滚，数据库约束，V2→V3 迁移及应用上下文关闭后重启重读和继续递增序号。
- 默认全局 Maven 缓存不可写，原 Aliyun 镜像 TLS 失败；使用项目忽略目录内缓存及独立 settings 访问 Maven Central 后完成验证，未更改全局设置。npm 获取依赖和本机页面服务使用了工具批准的提升权限。
- 首轮前后端构建并行导致 JAR 尚无首页；前端完成后重新执行 `-DskipTests package` 成功，将当前静态页面打入同源 JAR，之后完成下方实际页面验收。没有将首轮 404 计作页面通过。

## 页面与独立文件库：实际通过

使用 Microsoft Edge 原生界面通过 CUA 操作本机 `http://127.0.0.1:8080/`，读取页面可访问树并检查截图。原窗口被最小化后改用独立窗口，最后已关闭该验收窗口并退出管理员会话。测试服务已停止。

临时独立库：`.local-data/story21-page-9c715dcb-beb8-4165-8f54-bf876ff2a57b/gpu-queue.mv.db`。该目录是本轮专用忽略目录，开发/演示库未使用。沙箱临时目录跨进程无法稳定重开，工作区临时库的沙箱写入受限后，用提升权限完成夹具及服务启动。夹具仅建立测试成员/管理员和 `gpu_id <> 10` 的测试失败约束；未加入正式迁移。

| 顺序 | 实际操作 | 观察结果 |
| --- | --- | --- |
| 1 | 测试成员登录 | 10 张卡可选择；有免审批入队/尚未获配说明；本人列表为空态 |
| 2 | 不选卡直接提交 | 显示“请至少选择一张 GPU。”；无成功回执 |
| 3 | GPU 1、1 小时 | 受理成功，接收序号 1，逐卡待分配 |
| 4 | GPU 2/3、24 小时 | 受理成功，接收序号 2，两张卡独立待分配 |
| 5 | 再次 GPU 1、72 小时 | 新编号、接收序号 3，与第一次 GPU 1 申请独立保留 |
| 6 | GPU 4/10 写入失败 | 显示“保存申请失败，请稍后重试。”；成功回执清除；原三份申请不变 |
| 7 | 刷新整个页面 | 原三份申请按 3/2/1 倒序，完整显示 GPU、时长和上海时间 |
| 8 | 停服、只读核对库 | 仅三份申请、四条 WAITING，无 GPU 4/10 部分写入；结果如下表 |
| 9 | 停服时点击“刷新我的申请” | 显示“无法读取我的申请，请重试。”及“重试加载我的申请”；没有空态 |
| 10 | 重启同一文件库后刷新 | 旧 Session 失效，重新登录后原三份记录完整保留，时间/序号/逐卡状态不变 |
| 11 | 管理员登录并网页建号 | 普通申请模块不挂载；显示“已创建成员账号 story21-created（网页建号回归成员）。” |

停服只读 SQL 与页面时间对照：

| 申请 ID | 接收序号 | UTC 提交时间（H2） | 上海时间（页面） | 时长/等待 GPU |
| --- | --- | --- | --- | --- |
| `950a305a-295d-4972-838b-e3dba4a715a4` | 1 | `2026-11-25 11:46:26.261016+00` | `2026/11/25 19:46:26` | 1 小时；GPU 1 |
| `e06d319e-9449-4cc1-980f-b27376cd1e71` | 2 | `2026-11-25 11:46:37.6378411+00` | `2026/11/25 19:46:37` | 24 小时；GPU 2、3 |
| `194b238f-0b6b-4979-a627-179e5181b84a` | 3 | `2026-11-25 11:46:50.799329+00` | `2026/11/25 19:46:50` | 72 小时；GPU 1 |

页面检查保存为本记录的实际观察及工具调用历史；未另行声称保存截图文件。API 标识字符串、带 Z 时间及 HTTP 201/400/401/403/404/503 由上述集成测试实际断言，页面检查未代替接口安全验收。

## 七组验收跟踪

| epics.md Story 2.1 验收组 | 原需求及架构编号（本故事覆盖范围） | 实际结果 |
| --- | --- | --- |
| 1. 合法单/多卡申请与本人归属 | FR-003 / AC-003-1；FR-006 的逐卡等待关系；FR-001 本人归属；AD-1、5、8 | 集成测试及页面通过；仅证明普通受理入队，未验收实际分配 |
| 2. 时长及选卡服务端校验 | FR-003 / AC-003-2；FR-002 指定资源身份部分；AD-1、8 | 集成测试覆盖全部矩阵通过；页面验证未选卡提示 |
| 3. 有意重复申请保留新身份/时间/顺序 | FR-004 / AC-004-2 的独立重复入队部分；AD-3、6 | 集成测试含时间并列通过；页面实际重复 GPU 1 申请通过；不宣称后续分配优先级已验收 |
| 4. 本人查询、逐卡等待、重启及时间格式 | FR-017 的本人逐卡等待查看基础；FR-006 的多卡等待关系；AD-3、6、8 | 接口、数据库和页面通过；实际进程重启后重新登录重读通过；FR-017 的部分获配、名额期限和结束历史留待后续故事 |
| 5. Session、CSRF、归属与直接请求 | FR-001 / AC-001-4 的本人申请隔离部分；NFR-002 的本故事接口；AD-8 | 安全过滤链集成测试通过；页面角色模块正确；未覆盖后续管理员干预接口 |
| 6. 同事务完整入队和失败回滚 | FR-003 / AC-003-1；FR-006 的完整逐卡入队部分；AD-1、5 | 自动测试及页面真实后续卡失败、停服只读 SQL 对照通过；不扩展为后续名额交接验收 |
| 7. H2 独立库与页面/API 演示 | FR-003、FR-004、FR-001 / NFR-002、FR-006 / FR-017 的上述部分；AD-1、3、5、6、8 | 已实际执行；不依赖后续分配、归还、续期或审核接口 |

## 审阅修正与定向验证

- 列表加载时禁用整个提交 fieldset，并在 submit 入口拒绝该状态；保留刷新按钮在 loading/submitting 时禁用，避免初始查询和提交后刷新重叠。
- 申请数据库/事务异常记录操作方法、路径及服务端异常原因；不记录请求体、凭据或 Session，外部仍返回空体 503。
- 升级测试在 V2 阶段写入具有真实 PBKDF2 散列的代表性成员；V3 后核对原 ID、显示名、角色及原散列，验证口令匹配，再以原身份创建申请，保留重启重读与接收序号递增断言。
- 定向执行 `RequestIntegrationTest#upgradeAndApplicationRestartKeepRecordsAndReceivingSequence+queryFailureReturns503InsteadOfAnEmptyList+rollsBackTheHeaderAndEarlierCardsWhenALaterCardWriteFails`：3 tests、0 failures、0 errors、0 skipped，6.191 秒；503 测试输出了对应数据库异常原因。沙箱内测试 JVM 在初始化阶段停滞后中止，同样三项在提升权限下完成。
- `npm.cmd --prefix frontend run type-check` 通过；本地临时 SFC/VNode 回归检查 `.local-data/check-request-loading.cjs` 通过，确认初始列表未返回时表单禁用且直接 submit 不写入，加载结束后可提交并保留刷新结果。本轮未重新执行全量构建/测试或浏览器验收，后续由主线全量复核。

## 接续与限制

- 三个独立审阅层共12条发现均已逐项裁决，见实现规格的Review Triage Log；落实列表加载保护、503日志、旧账号升级验证及编号映射，无需修改已批准frozen块，无defer条目。局部修正后主线执行前端完整构建、Maven离线verify：18项、零失败/错误/跳过，8.774秒；同源JAR于20:04打包成功。冲刺为review，待用户接收及合并，尚未推送。
- 主线冲刺脚本复核valid=true、problems=[]；默认Python缺ruamel不代表该脚本不可运行，实际用已配置uv依赖环境通过。
- 自动测试的重启为应用上下文关闭/重新创建；页面另外实际停止并重启 JAR，验证 Session 失效与持久化申请重读。
- 未执行后续故事的分配、通知、续期、公开队列等行为；本故事没有这些数据表或副作用。实验 A 尚未准备，不将普通开发记录充作实验结果。
- Flyway 10.10.0 对 H2 2.2.224 输出支持版本提示；本次 V1–V3 及 V2→V3 的实际迁移通过，保持既定版本锁定。
- 可复现入口见 `docs/development/runbook.md` 的 Story 2.1 章节和 `RequestIntegrationTest.java`。本地页面夹具 `.local-data/PageFixture.java` 及测试数据库不入 Git，证据不包含口令、散列或 Session/CSRF 值。
