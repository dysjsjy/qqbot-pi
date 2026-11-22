# qqbot/
> L2 | Parent: ../../../../../AGENTS.md

- `ApiSecurityTest.java`：管理员会话、CSRF 与平台账号边界。
- `ApplicationIntegrationTest.java`：群配置、成员和榜单 HTTP、日报幂等及当前 epoch 统计。
- `ArchitectureTest.java`：包依赖方向、薄控制器、计量生成端口与文件大小约束。
- `ChannelSenderTest.java`：ChannelSender 验证。
- `config/`：该能力的单元与启动边界验证。
- `D01DailyWorkflowRegressionTest.java`：日报流程回归。
- `D02DailyWorkflowTest.java`：日报阶段编排。
- `D03DailyLifecycleTest.java`：日报恢复、资源与真实 pi 协议。
- `D03DailyRuntimeTest.java`：日报恢复、资源与真实 pi 协议。
- `DailySummaryTestModel.java`：DailySummaryModel 验证。
- `DatabaseBehaviorTest.java`：DatabaseBehavior 验证。
- `GroupActivityIntegrationTest.java`：迁移、群成员隔离、指令授权、榜单和投递生命周期。
- `HttpAdapterTest.java`：HttpAdapter 验证。
- `MemoryOptimizationSmokeTest.java`：检索分类、对话窗口、降噪与人工段落顺序。
- `channel/`：官方平台协议与投递规则的纯逻辑测试。
- `memory/`：记忆与原文归档的纯逻辑测试。
- `MemberProfileSmokeTest.java`：模型桩验证账目迁移、冷启动、本人命令和重启不重复派发。
- `message/`：入站文本分类和分流契约测试。
- `MemoryV2FixtureLoader.java`：从测试类路径装载合成会话，写入隔离数据库。
- `MemoryV2TestDatabase.java`：记忆测试的隔离数据库与夹具。
- `OfficialQqApplicationIntegrationTest.java`：OfficialQqApplicationIntegration 验证。
- `OfficialQqMentionTest.java`：OfficialQqMention 验证。
- `OfficialQqTest.java`：OfficialQq 验证。
- `OfficialQqWebhookTest.java`：OfficialQqWebhook 验证。
- `OfficialQqWebSocketTest.java`：OfficialQqWebSocket 验证。
- `P01MigrationTest.java`：链路迁移与记录基础。
- `P01TraceTest.java`：链路迁移与记录基础。
- `P02TraceStoreTest.java`：链路持久化。
- `P03IngressTraceTest.java`：入站链路记录。
- `P04ModelTraceTest.java`：模型链路记录。
- `P05PiTraceTest.java`：pi 链路记录。
- `P08ContextCacheTest.java`：上下文缓存与用量节流。
- `P08UsageThrottleTest.java`：上下文缓存与用量节流。
- `P09PromptTest.java`：提示隐私与协议。
- `P10TraceIntegrationTest.java`：SQLite 完整调用链、清理和隐私回归。
- `persistence/`：该能力的单元与启动边界验证。
- `pi/`：RPC 格式、启动环境、槽位和资源回收验证。
- `PiRuntimeTest.java`：pi 协议、取消、超时和本地模型适配。
- `Q01DailyEditorialTest.java`：日报内容组织。
- `Q02DailyPresentationTest.java`：日报呈现。
- `Q03DailyLatencyTest.java`：日报延迟预算。
- `R01DailyReadabilityTest.java`：日报可读性、多场景模式、署名策略、前置降噪与瞬时故障局部重试。
- `RetentionSmokeTest.java`：热索引裁剪、冷 RAW／摘要／日报／榜单、有限回收和清除冒烟。
- `S01RawMigrationSchemaTest.java`：原始归档迁移结构。
- `S02RawSegmentTest.java`：验证 V1 分段字节、索引重建、提交边界恢复与文件路径约束。
- `S03RawArchiveTest.java`：原始归档追加与读取。
- `S04RawHttpTest.java`：原始归档 HTTP。
- `S05RawMigrationTest.java`：原始归档离线迁移、V9 分页导出及目标保护。
- `S06RawLifecycleTest.java`：原始归档生命周期。
- `security/`：该能力的单元与启动边界验证。
- `schedule/`：日报来源快照与调度纯函数测试。
- `SQLiteApplicationIntegrationTest.java`：SQLiteApplicationIntegration 验证。
- `SQLiteDatabaseIntegrationTest.java`：SQLiteDatabaseIntegration 验证。
- `T01ContractTest.java`：记忆接口与持久化基础。
- `T01FoundationTest.java`：记忆接口与持久化基础。
- `T03HistoryTest.java`：群历史范围。
- `T04ContextTest.java`：上下文分层预算。
- `T05SearchTest.java`：记忆检索排序。
- `T06UsageTest.java`：生成用量计量。
- `T07PolicyTest.java`：记忆概括策略。
- `T07SummaryTest.java`：记忆概括策略。
- `T08WorkflowTest.java`：统一记忆路径、检索门控、历史隔离与用量回归。
- `T09AdminTest.java`：记忆管理接口。
- `T10ApplicationTest.java`：计量接线、V6 文件快照升级、重建和重启。
- `T10ConfigurationTest.java`：应用配置、维护与记忆集成。
- `T10EffectFixtureTest.java`：记忆测试的隔离数据库与夹具。
- `T10FixtureTest.java`：记忆测试的隔离数据库与夹具。
- `T10MaintenanceTest.java`：应用配置、维护与记忆集成。
- `T10StartupTest.java`：应用配置、维护与记忆集成。
- `TestServices.java`：构建临时数据库的共享业务夹具。
- `text/`：该能力的单元与启动边界验证。
- `usage/`：验证模型用量观察、合并与报告状态。

数据库用例使用临时 SQLite，不读取部署数据库。
真实 pi 协议测试只访问本机合成模型端点。

[PROTOCOL]: Update this header on change, then check AGENTS.md
