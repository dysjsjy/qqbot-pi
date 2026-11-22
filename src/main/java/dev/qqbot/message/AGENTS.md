# message/
> L2 | Parent: ../AGENTS.md

- `AgentRunStore.java`：认领并冻结回复范围与消息水位，复核代际后原子发布答复和投递。
- `AgentWorker.java`：单任务回复编排，计算剩余预算并校验答复。
- `CitationValidator.java`：校验引用编号与检索资料范围。
- `CommandHandler.java`：复用真实提及与短期去重校验，分流群管理和本人画像命令。
- `ConversationService.java`：按会话隔离对话代际与短期历史。
- `Group.java`：会话范围快照与当前机器人的群管理员判断。
- `GroupIngestRequest.java`：平台消息入站契约。
- `IngestService.java`：同一事务内去重采集、分流命令和应用群回复策略，再提交生成准入。
- `MessageJournal.java`：入站热窗、当前 epoch 消息写入、去重与 QQ 同消息提及升级。
- `ReplyPolicyService.java`：网页与群内共用的 version、概率、能量和提及策略。
- `ReplyQueue.java`：回复任务与发送队列编排。
- `RunAdmission.java`：以有类型查询检查队列与冻结来源；普通消息成功入队后扣能量。
- `ScopeRegistry.java`：会话范围锁与清理边界。



[PROTOCOL]: Update this header on change, then check AGENTS.md
