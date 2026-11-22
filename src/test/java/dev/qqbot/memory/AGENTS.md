# memory/
> L2 | Parent: ../AGENTS.md

- `ContextCompositionTest.java`：验证冻结输入、历史引用复用与查证模式画像隔离。
- `DialogueWindowsTest.java`：验证短确认、重复过滤及跨窗来源重叠。
- `MarkdownSummaryPreparationTest.java`：验证预算前缀和首条超限时的来源登记决策。
- `MemorySearchWriterTest.java`：验证索引分块身份兼容与代际隔离。
- `MemberProfileActivityTest.java`：验证统计窗口、冷启动准入、别名和短时发言计数。
- `MemberProfileCardTest.java`：验证卡片选取顺序、去重和置信度。
- `MemberProfileObservationsTest.java`：验证本人证据去重、独立窗口确认和替换关系。
- `MemberProfileWindowTest.java`：验证首次、增量和重放的证据水位及冷留存边界。
- `RawArchiveGcManifestTest.java`：验证原有回收清单格式、群路径检查和重试退避。
- `RawArchiveMigrationVerifierTest.java`：验证 RAW 旧文档块顺序和内容不一致的拒绝路径。
- `RawArchiveScanTest.java`：验证过滤行推进、未来文本阻断和批次字节上限。

[PROTOCOL]: Update this header on change, then check AGENTS.md
