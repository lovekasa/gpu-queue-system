# 组内 GPU 使用名额排队与分配系统

面向实验室成员和算力管理员，管理 GPU 使用申请、排队、使用权分配和归还记录。成员获得名额后自行登录服务器运行任务。

## 当前状态

本地课程演示采用 H2 文件库。Story 1.1「初始化应用并以受控管理员身份登录」的前后端工程及启动脚本见 [运行手册](docs/development/runbook.md)。当前故事状态及验证结果见 [HANDOFF.md](HANDOFF.md)。

产品简报：[brief.md](_bmad-output/planning-artifacts/briefs/brief-gpu-queue-system-2026-11-11/brief.md)。补充需求与课程要求见同目录 `addendum.md`。

产品需求见 [PRD](_bmad-output/planning-artifacts/prds/prd-gpu-queue-system-2026-11-18/prd.md)，包含 18 条功能需求、46 条验收标准和 3 条非功能需求。

采用 Vue 3、Spring Boot 3.3.13、Java 21 和内嵌 H2 文件库。后端以单实例运行，名额服务使用普通事务、唯一当前映射和独立名额身份。详见 [架构](_bmad-output/planning-artifacts/architecture/architecture-gpu-queue-system-2026-11-18/ARCHITECTURE-SPINE.md)。

[配套规格](_bmad-output/specs/spec-gpu-queue-system/SPEC.md) 含 8 项能力，frontmatter 列出的全部配套文档是开发依据。

[正式故事清单](_bmad-output/planning-artifacts/epics.md) 包含 3 个史诗和 20 条故事。候选故事、审阅记录和此前的规格收尾保留作历史参考。

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

## 团队与 AI 使用

成员和分工：待填写。使用 Codex 与 BMAD 辅助需求分析、文档和后续开发；人工负责需求确认、方案取舍、审查与验证。后续按实际使用情况补充说明，不虚构访谈、实验或测试结果。

## 运行和测试

开发、演示和验收各用独立的 H2 数据目录。迁移、初始化、启动和顺序验收的命令见 [运行手册](docs/development/runbook.md)。
