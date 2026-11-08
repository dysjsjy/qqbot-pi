# MVP 运行、测试与恢复

## 首次检查

1. 填好 `.env`，启动 PostgreSQL/pgvector，再启动应用。Flyway执行V1、V2迁移。
2. 检查 `/actuator/health`，登录管理台；填写机器人稳定账号ID和授权群ID，启用采集。群配置不得改成另一个scope，新增群请创建新记录。
3. 平台发普通文本、`/help`、`/memory 问题` 与@问题；普通消息只落库，自己的回声不存；验证群管理员可触发昨日分析。
4. 每个平台核对真实消息和主动回执，记录网关版本；无实际账号时只能记录为BLOCKED。

缺模型/provider、embedding、密码哈希或启用网关所需配置，应用启动应失败。单实例锁由专用数据库连接持有；不要通过关闭锁来运行多个实例。数据库账号限用于此应用。日志不打印群正文、模型prompt、回调token或模型密钥。

## 自动化测试

后端 `mvnw.cmd verify -Pfrontend`。数据库测试需设置专用 `TEST_DATABASE_URL`；当前测试数据库用户名postgres、空密码，真实服务请配置专用实例相同测试凭据。测试执行TRUNCATE，不能指向用户数据。生产数据库验证还需真实PG17：Flyway完整迁移、连接池事务、多实例锁、故障恢复。

浏览器：

```powershell
cd test-support
npm ci
npx playwright install chromium
npx playwright test
```

可用 `PW_CHROMIUM_EXECUTABLE` 指向已有Chromium。测试自动启动Vite，API采用可控模拟响应，检查登录、四页、检索文本安全显示、日报提交、UNKNOWN状态、清除和退出。API会话/CSRF由独立Spring MockMvc测试验证。截图在 `plan/1107/evidence`，浏览器报告在 `test-support/playwright-report`。

pi测试包括受控JSONL子进程以及**真实1.5.0 CLI + 本地HTTP模型桩**；后者验证原生会话和RPC交互，不验证模型中文检索质量。真实模型仍须执行固定20问与5个无依据问题的验收集。

## 常见状态

| 状态 | 含义与操作 |
| --- | --- |
| PENDING / FAILED索引 | 每条最多自动3次；恢复embedding后在群记忆页重新处理失败索引 |
| INTERRUPTED | 重启中断；日报允许一次显式重试，同来源快照，不自动重跑 |
| LIMIT_EXCEEDED | 超过1000条/100000码点，报告未生成；缩小实际使用规模后重新评估计划 |
| GENERATED | 报告已生成，等待Outbox发送；重启不再生成 |
| DELIVERY_UNKNOWN / UNKNOWN | 可能已发送；查平台记录人工核对，不自动重发 |
| DELIVERY_FAILED | 明确拒绝或有限重试已耗尽；完整报告可在管理台查看 |
| NO_DATA | 昨日没有可分析文本，不调用模型；覆盖缺口仍可查看 |
| EXPIRED / CANCELLED | 来源过期、清除、配置禁用或用户取消；结果不可继续发送 |

关闭群采集会停止该群任务与待发消息，并记录停用区间；关闭回复保留采集、取消生成与待发。关闭定时开关仅阻止后续自动触发。修改每日时间为已过去时，扫描器只补当前日期的昨日任务，已有同日期任务不重发。

## 停写备份

数据库和 `.state` 会话文件必须作为一组备份。备份含群正文和密钥使用痕迹，应限制目录权限；环境配置另行安全保存。以下命令为操作示例，未自动执行：

```powershell
docker compose stop bot
docker compose exec -T database pg_dump -U qqbot -d qqbot -Fc -f /tmp/qqbot-backup.dump
New-Item -ItemType Directory -Path backups/20261107 -Force
docker compose cp database:/tmp/qqbot-backup.dump ./backups/20261107/database.dump
docker compose cp bot:/app/.state ./backups/20261107/state
docker compose start bot
```

必须确认pg_dump与复制成功后再恢复写入；若失败也应在排查后恢复bot服务。备份目录不要提交仓库。保留7天，删除前明确核对具体日期目录。

## 恢复演练

在**新Compose项目、空数据卷**中恢复，避免覆盖原数据库：停止新项目bot；将dump复制到新数据库容器，使用 `pg_restore -U qqbot -d qqbot --no-owner --exit-on-error /tmp/qqbot-backup.dump`；将state内容恢复到新bot的 `/app/.state` 并确保10001用户可读写；同步环境配置后启动。

检查两群原文数量、向量数量与检索、定时配置、报告、会话恢复。旧RUNNING转为INTERRUPTED，旧SENDING转为UNKNOWN。备份若早于记忆清除，需再次应用清除审计。不要同时将新旧项目接入真实群，以免重复采集/发送。此演练和Compose实际构建尚未在当前无运行Docker Engine的环境完成。

## 保留期

每小时清理：30天原文与向量；移除引用过期来源的日报，关闭可能携带旧来源的临时会话，取消旧待发；7天未活动会话失效；90天操作审计删除。未引用的检查点文件延迟1小时删除以避免与完成事务竞争。文件与数据库清理都在应用state目录范围内。日志轮转与7天备份清理由宿主机执行，MVP不内置监控平台。

## 仍需实际环境完成

真实QQ/微信20条采集和主动发送、微信登录失效恢复、固定中文RAG命中率、1000条关键事实全量分析、2小时容量冒烟、数据库恢复、生产进程资源上限。执行结果写入计划验收记录，未执行时保持NOT_RUN/BLOCKED。
