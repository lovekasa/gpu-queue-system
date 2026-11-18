# 开发交接入口

更新时间：2026-11-18（Asia/Shanghai）。本项目由三人接续使用 agent 开发，不预设固定人员分工。

## 接手顺序

1. 阅读根目录 AGENTS.md、本文件及 git status，保留其他人的未提交改动。
2. 查看 _bmad-output/implementation-artifacts/sprint-status.yaml 和当前故事记录；以实际证据判断进度，不能把规格获批视为实现完成。
3. 从下方“下一步”继续；结束工作前同步本文件、冲刺状态及当前故事的步骤/测试记录。
4. 每次交接记录变更文件、已完成步骤、验证命令与结果、未完成事项、阻塞原因和下一步。记录实际提交 SHA；未提交时明确标注，不编造负责人或测试结果。

## 当前进度

- 最新范围调整（D047）：用户要求免MySQL的本地演示并取消并发工程/压力验收。已形成 _bmad-output/planning-artifacts/sprint-change-proposal-2026-11-18-lightweight-demo.md，拟采用H2文件库；整包方案待确认，正式规格尚未统一修订。接手时先处理该提案，不照旧MySQL/30连接要求继续实现。
- 基线：5168715 已推送 main，包含18条实现规则和架构运行参数修订。
- 当前阶段：冲刺跟踪已建立，Story 1.1（初始化工程、受控管理员登录）实现规格待确认。尚未创建业务工程，尚未执行业务测试。
- 规划源：_bmad-output/planning-artifacts/epics.md，3个史诗、20条已批准故事；story-proposals 是审阅来源，不是已实现成果。
- 冲刺规划核查 PASS；脚本已生成3个史诗、20条故事的 sprint-status.yaml，当前全部故事 backlog，尚无实现验收。核查见 _bmad-output/planning-artifacts/implementation-readiness.md。
- bmad-build 渲染入口已成功，Epic 1 上下文已生成；Story 1.1 实现规格位于 _bmad-output/implementation-artifacts/spec-1-1-admin-login.md，状态 draft，等待实现规格检查点确认。数据库连接已核验，尚未创建工程。
- uv 0.13.9 已安装到本机 Python 用户环境；原 shell 找不到 uv，后续须使用实际安装目录补充当前进程 PATH。
- 已实测 Java 21.0.2、Maven 3.9.6、Node 24.18.0、npm 11.16.0；尚未锁定前端依赖。MySQL 客户端8.0.34，不代表服务端版本。
- npm元数据核实create-vue3.24.0，Node要求^22.18.0或>=24.12.0，与本机相容；尚未运行脚手架。冲刺文件validate结果valid=true。
- 已通过用户配置的 `gpu-queue` 登录路径只读连接，实测 MySQL 服务端8.0.34，`gpu-queue` 存在且当前无表。
- 登录路径使用 root 管理账号；后续应用须创建并使用分库受限账号，不能直接复用 root。候选隔离库 gpu_queue_dev/test/demo 尚不存在；尚未执行建库、建号或迁移。
- 数据库连接阻塞已解除；凭据留在本机登录路径，不写入聊天、日志、仓库。
- 未跟踪的 _bmad-output/course-source/ 为原有课程截图，尚未提交。

## 下一步

1. 先确认并应用D047对应的本地演示简化提案，统一更新MySQL/并发相关现行契约；核对sprint-status.yaml，当前跟踪状态不代表代码已完成。
2. 调用 bmad-build 并指定 _bmad-output/implementation-artifacts/spec-1-1-admin-login.md，从 draft 规格继续；按技能入口重新生成当前机器的工作流快照，不依赖上一台机器的 _bmad/render 路径。实现规格尚未批准，本次提交不表示批准实施。
3. 数据库只读核验已完成；实现时准备dev/test/demo隔离库及受限账号，独立测试库准备好后才执行数据库测试。
4. 创建并锁定最小 Vue / Spring Boot 工程；仅建立账号所需数据，逐项验证 Story 1.1，证据齐全后再标记完成。
5. 实验 A 必须先准备再实施所选 X/Y 故事；目前未选故事、未开展实验，不能把本次有规划上下文的开发冒充 Y 组。

## 交接历史

| 日期 | 实际工作 | 验证与遗留 |
| --- | --- | --- |
| 2026-11-18 | 用户明确三人顺序接手，建立本入口；记录环境探测和数据库创建声明 | 尚无代码/业务验收；冲刺文件与 Story 1.1 待继续 |
| 2026-11-18 | 冲刺文件生成及格式验证通过，Epic 1上下文与Story1.1草案完成，MySQL8.0.34只读核验成功；用户要求提交GitHub交接 | 等待实现规格确认；其他成员需在自己机器配置数据库登录路径，凭据不随Git分发；本次提交SHA由git log查看 |
| 2026-11-18 | D047记录用户取消并发及轻量本地演示诉求，按一次汇总模式形成变更提案；用户要求提交GitHub供接续 | H2整包方案仍待确认；没有新代码、数据库改动或验收结果；对应提交SHA见git log，提交提案不表示批准应用 |
