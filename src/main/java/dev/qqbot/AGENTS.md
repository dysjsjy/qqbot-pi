# qqbot/
> L2 | Parent: ../../../AGENTS.md

- `BotApplication.java`：Spring 入口、时钟与后台任务启停。
- `BotProperties.java`：管理员、模型、平台与运行时部署配置。
- `MemoryProperties.java`：校验聊天、概括和画像共用的模型窗口预算。
- `MemoryUsageLifecycle.java`：恢复未结算模型调用，按期裁剪计量账目。
- `MemberProfileProperties.java`：实验画像总开关、轮询周期和聊天注入上限。
- `RawArchiveProperties.java`：配置在线 RAW 扫描、分页和分段上限。
- `RetentionProperties.java`：校验热窗、冷留存和过程保留期，提供统一水位。
- `admin/`：薄 HTTP 层、错误边界和诊断接口。
- `channel/`：官方 QQ、微信适配、入站与投递。
- `config/`：启动环境文件与配置载入。
- `delivery/`：平台发送能力端口。
- `management/`：会话配置、活跃成员、群管理员授权和状态查询。
- `memory/`：人工记忆、热冷留存、按需检索与实验性群成员画像。
- `message/`：消息入库、会话、命令和回复队列。
- `model/`：生成请求、提示策略与模型能力端口。
- `persistence/`：SQLite、事务、值转换与单实例锁。
- `pi/`：pi JSONL RPC、资源测量和会话边界。
- `schedule/`：昨日摘要、独立活跃榜与定时调度。
- `security/`：管理员会话、CSRF 与限速。
- `text/`：无状态文本规范化。
- `trace/`：隐私过滤调用链与热点统计。
- `usage/`：生成计量、价格和用量查询。

控制器委托业务层，业务层使用端口与 Db。
持久化层不依赖业务模块。

[PROTOCOL]: Update this header on change, then check AGENTS.md
