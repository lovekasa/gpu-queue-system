# 架构决策记录

用途为课程提交及后续开发。每条 ADR 对应架构约定 AD，业务决定见 decision-log.md。当前运行基线依据 D047、D048。表中的备选方案记录技术取舍。

| ADR | 决定 / 状态 | 备选方案及未采用理由 | 影响与来源 |
| --- | --- | --- | --- |
| ADR-001 | 前后端分离、模块化单体；已确认 | 多个独立服务：当前规模需要额外部署及跨服务一致性协调 | Vue3＋Spring Boot；D017–018，AD-1 |
| ADR-002 | H2 文件库、普通事务及唯一当前名额 | 独立 MySQL 服务及专用锁协议会增加本地演示准备；仅以应用查询判断唯一缺少数据库约束 | 当前映射主键、独立名额唯一及同卡外键；D019、D047、D048，AD-2 |
| ADR-003 | 每轮独立 allocation_id，按目标当前状态处理重复操作 | 按 GPU 或成员身份释放无法区分同卡多轮分配；通用命令结果表超出顺序演示范围 | 旧操作不会影响后继，重复无变化不通知；D020、D048，AD-3 |
| ADR-004 | 按处理顺序读取最新状态和期限，使用可注入 UTC 时钟 | 固定续期优先或固定到期优先会忽略已保存的状态 | 先续期使用新期限，先释放则审批失效；D021、D039、D048，AD-4 |
| ADR-005 | 业务、审计、站内消息同一事务；已确认 | 先提交业务再另写审计：崩溃会丢失追溯；提交前远程通知：可能通知已回滚结果 | 原子成功或失败；D019，AD-5 |
| ADR-006 | 数据库每 60 秒扫描及启动补查，复用应用入口 | 仅内存定时器会在重启后丢失候选；消息中间件增加本地配置 | 覆盖到期、异常与空闲再分配；D022、D044、D048，AD-6 |
| ADR-007 | 本机 HTTP 同源访问、Session＋CSRF | 长期 JWT 增加凭据存储和会话退出的实现；正式部署超出演示范围 | 后端鉴权、HttpOnly Cookie 及字段裁剪；D022、D048，AD-8 |
| ADR-008 | 保存当前等待项的移动和锚点，按移动记录 ID 重放 | 永久成员权重违反仅当前项有效；位置数字受列表变化影响；永久双锚点可能互相矛盾 | 后继优先、前驱备选、均无锚点则不移，目标离队后失效；D015、D048，AD-7 |

## 技术核查与版本基线

- 确认 Vue 3、Spring Boot 3.3.13、Java 21 和 H2 文件模式。工程创建时核实工具及项目依赖，以实际解析版本为准。[Spring Boot 3.3 系统要求](https://docs.spring.io/spring-boot/3.3/system-requirements.html)
- [Vue 官方快速开始](https://vuejs.org/guide/quick-start.html)使用 create-vue。未执行脚手架，Vue及构建工具补丁、Node版本在工程创建前核实，不能声称已安装。
- H2 文件随应用持久化，应用启动前运行版本化迁移。H2 使用 TIMESTAMP WITH TIME ZONE 表达时刻，Java 持久化映射见 AD-4 和时间约定。[H2 数据类型](https://www.h2database.com/html/datatypes.html#timestamp_with_time_zone_type)
- [Spring事务注解说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)核查代理与回滚机制；该网页为当前版本，落地时按Boot BOM所带版本复核，禁止照搬跨大版本默认行为。架构显式要求所有必需写入失败回滚。
- 具体调度、认证和 CSRF 配置以项目实际解析的 Spring 依赖为准。[Spring 调度](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) · [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

## 后果与验证

D039 规定按当前已批准期限与原分配时刻的总跨度判断短时，24 小时异常阈值保持从原分配时刻计算，见 AC-012-4 和 AD-4。

事务内只保存本地业务及必需审计、站内消息。验收包含顺序状态变化、代表性回滚、权限和停机备份恢复，见 verification-plan.md。
