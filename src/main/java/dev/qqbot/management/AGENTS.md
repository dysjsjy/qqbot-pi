# management/
> L2 | Parent: ../AGENTS.md

- `GroupAdministration.java`：会话 CRUD、群管理员授权、版本校验和停用清理。
- `GroupMembers.java`：查询当前 epoch 的 7、30、90 天成员活动；授权使用 30 天资格。
- `GroupSettings.java`：会话配置的校验输入契约。
- `ManagementQueries.java`：组合状态、日报和结构化审计；屏蔽网关凭据。
- `WorkspaceStatusService.java`：当前 epoch 聚合与日报、榜单定时群并集。

控制器向此层请求业务能力。
此层依赖 persistence、memory 与 text，不依赖 admin。

[PROTOCOL]: Update this header on change, then check AGENTS.md
