# schedule/
> L2 | Parent: ../AGENTS.md

- `DailySummarySourcesTest.java`：验证日报来源顺序、码点重切和输入拒绝。
- `DailySummaryReadBatchesTest.java`：验证静默、令牌和有效条数分批边界；保留空文本占位。
- `DailySummaryPromptTest.java`：验证读批和终稿 JSON、来源端点与缺证据拒绝。
- `DailySummaryPreparationTest.java`：验证业务日期和来源输入上限。
- `DailySummaryGenerationTest.java`：直接核对分发上限、预算超时和作业取消。
- `DailySummaryReportTest.java`：验证成稿引用、选材缺口及闲聊占位。
- `DailySummaryMaterialTest.java`：验证重复来源和重叠选材批被拒绝时保留现有状态。
- `DailySummaryOutputTest.java`：验证选材、匿名截止时间和成文的事实覆盖。
- `DailySummaryReadEditTest.java`：验证紧凑处置、反向事实链接及降级审阅。
- `DailySummaryRecoveryTest.java`：验证读批重排、来源二分及恢复上限。

[PROTOCOL]: Update this header on change, then check AGENTS.md
