# ADR结构与措辞审阅

ADR.md帮助课程评阅者及后续开发者理解已决定的方案、备选取舍及核查边界。使用bmad-review的structure→prose，仅审ADR散文，不改架构约定。

采用Strategic/Context结构：决定表在前，技术证据随后，验证边界最后。读者为人，风格基准为Microsoft Writing Style Guide。保留紧凑技术语言及表格。

词数依据原word_metrics.py（Python执行回退）：全文1083，决策记录节641、技术核查节323、后果与验证96；脚本词元数不等于汉字数。

| Pass | Original Text | Revised Text | Changes |
| --- | --- | --- | --- |
| structure | 决策表及技术核查与版本基线 | PRESERVE | 表内说明选择，后文说明证据及延后核查，两者不重复；预计减少0词 |
| prose | “缓存中 starter-parent 3.3.13 已读取，非现有项目 pom.xml” | “已读取本地缓存中的 starter-parent 3.3.13；当前项目尚无 pom.xml” | 明确“非”所指，防止读成读取了错误POM |

1条保留意见、1条措辞建议；不建议删减，预计减少0词（0%）。无长度目标，无理解信息损失。未修改原文。
