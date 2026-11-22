# sqlite/
> L2 | Parent: ../../AGENTS.md

- `V1__schema.sql`：群、消息、模型任务、日报与 Outbox 基础表。
- `V2__heartbeat.sql`：群连接心跳时间。
- `V3__official_qq_reply.sql`：QQ 被动回复源消息引用。
- `V4__reply_policy.sql`：群回复模式、概率和动态能量。
- `V5__markdown_memory.sql`：人工记忆与 Markdown 归档策略。
- `V6__private_chat.sql`：隔离 QQ 私聊目标和群聊范围。
- `V7__memory_context_usage.sql`：事件顺序、分层上下文与模型计量。
- `V8__memory_search_index.sql`：会话内倒排检索与索引状态。
- `V9__trace_span.sql`：隐私过滤后的调用链跨度。
- `V10__raw_archive_segments.sql`：原始归档分段与回收引用。
- `V11__daily_summary_profile.sql`：日报场景模式与署名策略。
- `V12__single_memory_context.sql`：移除旧上下文开关，保留归档设置和内容。
- `V13__group_activity.sql`：群发言资格、独立活跃榜、短期命令去重及策略版本。
- `V14__member_profile.sql`：保留旧模型账目，增加群与 epoch 隔离的画像表和本人证据下界。

[PROTOCOL]: Update this header on change, then check AGENTS.md
