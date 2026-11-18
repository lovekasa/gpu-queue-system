# 架构决策记录

日期：2026-11-18。用途：课程提交及后续开发；每条对应架构约定 AD。以下“未采用”描述备选方案的技术取舍，不计为用户否决 AI 输出。业务决定来源于 decision-log.md，技术细化来源于本目录 .memlog.md。

| ADR | 决定 / 状态 | 备选方案及未采用理由 | 影响与来源 |
| --- | --- | --- | --- |
| ADR-001 | 前后端分离、模块化单体；已确认 | 多个独立服务：当前规模需要额外部署及跨服务一致性协调 | Vue3＋Spring Boot；D017–018，AD-1 |
| ADR-002 | 固定 GPU 行锁＋唯一当前名额＋事务；已确认 | 仅Java锁：跨进程无效；仅先查后写：存在竞态；只锁当前名额：空闲时无行可锁 | 同卡串行，不同卡并行；D019，AD-2 |
| ADR-003 | 每轮独立 allocation_id；已确认 | 仅凭GPU或成员身份释放：无法区分同卡多轮分配 | 旧事件无副作用，重复不生成业务消息；D020，AD-3 |
| ADR-004 | 到期与续期共锁，成功提交先后决定；已确认 | 永远续期优先或永远到期优先：与已确认成功记录顺序及最新状态规则不一致 | 旧期限重读、终态不可复活；D021，AD-4 |
| ADR-005 | 业务、审计、站内消息同一事务；已确认 | 先提交业务再另写审计：崩溃会丢失追溯；提交前远程通知：可能通知已回滚结果 | 原子成功或失败；D019，AD-5 |
| ADR-006 | 数据库定时扫描＋启动补查；已确认 | 仅内存定时器：重启丢任务；消息中间件：本期增加运维且仍需幂等 | 扫描重读，覆盖空闲再分配；D022，AD-6 |
| ADR-007 | Session＋同源API＋CSRF；已确认 | 浏览器自管长期JWT：增加存储、撤销和刷新复杂度，当前没有跨服务认证需求 | 后端鉴权与字段裁剪；D022，AD-8 |
| ADR-008 | 使用当前项的移动及锚点表达调序；架构细化 | 永久成员权重：违反仅当前项有效；只保存页面位置整数：队列变化后含义不清；永久双锚点约束：连续移动可成环 | 基础排序后逐次重放，后继优先、前驱备选，无锚点不移动，后操作优先，离队清理；D015，AD-7，尚无用户对具体锚点机制的单独选择 |

## 技术核查与版本基线

- 用户选择 Vue 3、Spring Boot、本地 MySQL；缓存中 starter-parent 3.3.13 已读取，非现有项目 pom.xml；Java21 由用户接受，运行时尚未验证。[Boot 3.3.13 系统要求](https://docs.spring.io/spring-boot/3.3/system-requirements.html)支持 Java17–23、Maven3.6.3以上。该版本并非本日最新版本；创建工程时必须再核对维护状态及依赖，升级通过新记录处理。
- [Vue 官方快速开始](https://vuejs.org/guide/quick-start.html)使用 create-vue。未执行脚手架，Vue及构建工具补丁、Node版本在工程创建前核实，不能声称已安装。
- 客户端 mysql.exe --version 输出8.0.34；服务端未连接，用户估计8.0。[MySQL8.0锁读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)与[隔离级别](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)支持事务行锁及READ COMMITTED。事务必须实际开启；不能以普通读取代替锁后读取。
- [Spring事务注解说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)核查代理与回滚机制；该网页为当前版本，落地时按Boot BOM所带版本复核，禁止照搬跨大版本默认行为。架构显式要求所有必需写入失败回滚。
- [Spring调度说明](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)核查定时能力；[Spring Security CSRF说明](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)核查CSRF保护。具体配置采用Boot匹配文档并通过测试，不在本轮生成配置。

## 后果与验证

D039补充ADR-004的时间分支：续期后按当前已批准期限与原分配时刻的总跨度判断短时，24小时异常阈值不重置；见PRD的AC-012-4及AD-4。此为后续故事审阅时真实新增确认，原ADR编号不变，不冒称初次定稿时已经展开该边界。

事务持锁期间不作远程IO；数据量虽小，死锁、超时、崩溃与重放仍须覆盖。锁只管理名额记录，撤销不代表原训练已停止。第一版不依赖消息队列保证持久化，站内消息是数据库记录。验证计划见 verification-plan.md；当前没有运行结果。
