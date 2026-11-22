# model/
> L2 | Parent: ../AGENTS.md

- `GenerationObserver.java`：生成分发、用量与检查点回调。
- `GenerationRequest.java`：单次生成的范围、提示和预算契约。
- `ModelUsage.java`：供应商报告的 token 分类与计费状态。
- `PromptPolicy.java`：按生成用途选择系统提示；聊天有画像卡时才加注入规则。
- `Purpose.java`：隔离聊天、记忆查询、概括、日报和画像账目；区分后台槽用途。
- `TextGenerator.java`：只暴露有计量上下文的生成与取消；不暴露原生会话调试接口。
- `TokenEstimator.java`：保守估算提示 token；不依赖供应商。
- `UsageStatus.java`：区分已报告与未知用量。



[PROTOCOL]: Update this header on change, then check AGENTS.md
