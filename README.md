# 组内 GPU 使用名额排队与分配系统

面向实验室成员和算力管理员，管理 GPU 使用申请、排队、使用权分配和归还记录。成员获得名额后自行登录服务器运行任务。

## 当前状态

已完成 BMAD Method 6.12.1 环境安装、项目初始化和多轮业务需求确认；产品简报与 PRD 均于 2026-11-18 完成文档收尾。PRD 已定稿，可供后续 UX、架构及史诗/故事拆分使用；尚未实现业务代码，当前没有可运行的业务系统。

产品简报：[brief.md](_bmad-output/planning-artifacts/briefs/brief-gpu-queue-system-2026-11-11/brief.md)。补充需求与课程要求见同目录 `addendum.md`。

PRD：[prd.md](_bmad-output/planning-artifacts/prds/prd-gpu-queue-system-2026-11-18/prd.md)，包含18条功能需求、46条验收标准、3条可测试非功能需求；D039补充续期后短时判断，审阅收尾及修订说明见同目录 `closeout.md`。

架构：[ARCHITECTURE-SPINE.md](_bmad-output/planning-artifacts/architecture/architecture-gpu-queue-system-2026-11-18/ARCHITECTURE-SPINE.md)，配套 `ADR.md` 与 `verification-plan.md`。采用 Vue 3、Spring Boot 3.3.13、Java 21、本地 MySQL 的模块化单体；以固定 GPU 行锁、唯一当前名额及独立名额 ID 处理分配与事件一致性。

配套规格：[SPEC.md](_bmad-output/specs/spec-gpu-queue-system/SPEC.md)，含8项能力；其 frontmatter 中4份 adopted companions 均为必读契约，保留原FR/AC/NFR/AD编号。

史诗与故事：[epics.md](_bmad-output/planning-artifacts/epics.md)。3个史诗共20条故事已获批，最终规格验证已通过，故事清单已定稿。课程实验和试用评价优先使用现有文档与简单表格。

## 已确认的范围

- 管理 GPU 使用名额；不自动提交或执行训练任务。
- 不包含 SSH 控制、强制终止进程或 GPU 实际占用监测。
- 分配记录不等于硬件真实运行状态。
- 课程实验 A 保留，实验 B 不做。

## 需求基线与后续事项

已确认 30 名成员、10 张 4090、按指定 GPU 排队及部分分配、超过 3 天的等待提升、按卡独立计时、管理员干预、续期和站内通知。PRD 已细化排序、重复同卡、归还和到期规则；账号由管理员创建，公开队列显示姓名与顺序，调序仅对当前等待项有效。公平性观察基线与实验 A 指标定义、人员分工，在试用评价和实验设计前分别复核，见 PRD Q09–Q10。

## 课程约束

至少 3 个史诗、12 个用户故事、2 条可测试的非功能需求，以及一个并发或一致性风险点。架构文档至少包含 5 条 ADR（含被否决方案与理由）；项目上下文至少包含 8 条实现规则。保留测试证据、冲刺状态历史和真实人机决策记录。

## 目录

- `_bmad/`：BMAD 配置和工作流。
- `.agents/skills/`：Codex 项目技能。
- `_bmad-output/`：规划及实施产物，须纳入版本控制。
- `decision-log.md`：真实决策记录。

## 下一步

实施前整理项目上下文，再进行冲刺规划。工程创建前核实并锁定精确依赖与数据库服务端版本。当前不开始代码实现。

## 团队与 AI 使用

成员和分工：待填写。使用 Codex 与 BMAD 辅助需求分析、文档和后续开发；人工负责需求确认、方案取舍、审查与验证。后续按实际使用情况补充说明，不虚构访谈、实验或测试结果。

## 运行和测试

待实现后补充准确的环境准备、启动、测试步骤和演示方式。
