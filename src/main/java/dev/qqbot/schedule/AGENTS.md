# schedule/
> L2 | Parent: ../AGENTS.md

- `ActivityScheduleService.java`：按群保存榜单开关、时间、名额与群发选择。
- `ActivityPresentation.java`：榜单昵称脱敏与群发文本分段。
- `DailyActivityService.java`：冻结前一业务日排名，按冷留存读取榜单，独立创建 Outbox 投递。
- `SummaryProfile.java`：日报场景模式（`CASUAL` / `KNOWLEDGE` / `WORK`）与成员署名策略（`KEY_ONLY` / `ANONYMOUS` / `ACTIVE`）值对象。
- `DailySummaryPreprocessor.java`：输入前置清洗（`<@OpenID>` 还原昵称、空表情/附件剥离、分享卡片标题摘要提炼与静默间隔计算）。
- `DailySummaryFacts.java`：共享可信事实和结果类型，提供证据归属和价值字段验证。
- `DailySummaryGeneration.java`：每次模型调用复核代际和预算，记录分发、后台等待及瞬时故障重试。
- `DailySummaryJson.java`：为模型协议编解码，限制 JSON 体积、嵌套、字段和可引用来源。
- `DailySummaryCoverage.java`：编码原有覆盖字段，重试时读取快照与当前场景设置。
- `DailySummaryPreparation.java`：在认领事务内读取和冻结群、代际、原文、昵称与采集覆盖。
- `DailySummaryEditing.java`：验证修订来源、必选内容保留与降级审阅，生成全批更新。
- `DailySummaryPlanner.java`：日报步骤规划与校验失败恢复策略。
- `DailySummaryPlan.java`：规划读批与阶段队列，应用校验修复、重新选材和来源二分。
- `DailySummarySources.java`：校验并冻结单次尝试的来源、占位与禁用词；按码点重切本次尝试的超预算单元。
- `DailySummaryReading.java`：以冻结单元校验读批事实、证据与两种处置记录格式。
- `DailySummaryPrompt.java`：在同一文件定义阶段指令和类型化模型请求。终稿只携带证据端点。
- `DailySummaryReport.java`：验证选材覆盖与群内展示；组装最终引用和闲聊占位。
- `DailySummaryMaterial.java`：持有单次尝试的事实、选材与成稿；模型结果通过全批校验后才写入状态。
- `DailySummarySelection.java`：核对每项事实的选中或省略记录，禁止省略必选事实。
- `DailySummaryWriting.java`：核对事实覆盖、引用范围、正文预算及署名规则。
- `DailySummaryWorkflow.java`：按步骤计划运行模型，验证结果后推进并记录指纹、计数和耗时。
- `DailySummaryPresentation.java`：群内发件箱与本地预览共用的唯一无损分段与场景化标题渲染器。
- `DailySummaryService.java`：提交和重试作业，委托快照认领，在发布事务内提交正文与 Outbox。
- `DailySummaryWorker.java`：串行执行已认领作业，协调模型步骤、报告发布与诊断。
- `ScheduleSettingsService.java`：群定时日报开关、执行时间、场景模式与署名方式配置服务。

[PROTOCOL]: Update this header on change, then check AGENTS.md
