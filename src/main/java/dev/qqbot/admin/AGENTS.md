# admin/
> L2 | Parent: ../AGENTS.md

- `ApiErrors.java`：常规管理接口的中文校验与冲突错误边界。
- `ActivityAdminController.java`：群活跃榜配置及冻结榜单分页。
- `SystemController.java`：会话、CSRF 与工作区状态 HTTP 入口。
- `GroupAdminController.java`：会话 CRUD、活跃成员查询与群管理员授权。
- `LogController.java`：运行日志与审计查询 HTTP 入口。
- `RuntimeLogs.java`：有界运行日志缓冲；不持久保存模型提示。
- `MarkdownMemoryController.java`：人工 Markdown 读取、保存与关键词搜索。
- `MemoryAdminController.java`：记忆检索、留存概览、索引状态与记忆清除。
- `MemberProfileAdminController.java`：画像策略、证据检视、人工纠偏和后台作业提交；写入委托画像服务。
- `MemoryApiErrors.java`：记忆接口校验、版本冲突与输入错误。
- `MemoryArchiveController.java`：归档策略、文档分页和失败任务重试。
- `MemorySettingsController.java`：带修订号的归档方式与概括参数，不设置回复上下文。
- `MemoryUsageController.java`：生成费用、token 和上下文统计。
- `ReplyPolicyController.java`：带版本的群回复模式、概率与能量配置。
- `RunAdminController.java`：日报列表、详情和失败重试。
- `ScheduleAdminController.java`：定时设置与昨日分析提交。
- `TraceController.java`：调用链列表、热点与隐私过滤详情。

HTTP 层只依赖业务服务。
工作区状态由 management 组合，凭据不进入响应。

[PROTOCOL]: Update this header on change, then check AGENTS.md
