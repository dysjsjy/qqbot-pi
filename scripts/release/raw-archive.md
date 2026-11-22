# 原始消息分段归档

SQLite `group_message` 继续保存采集事实，历史上下文、搜索和摘要继续从 SQL 读取。RAW 文件副本采用本地追加日志，不需要 Kafka 服务。人工 `MEMORY.md` 和 AI 摘要仍是 Markdown。

```text
BOT_STATE_DIR/memory/<群配置 UUID>/
  MEMORY.md
  raw/<旧文档 UUID>.md                 迁移完成并 finalize 后回收
  summaries/<文档 UUID>.md
  raw-log/epoch-<epoch>/
    00000000000000000001-g0.log
    00000000000000000001-g0.index
```

每个群或私聊、每个 epoch 独立分区，使用已有 `event_seq`，允许序号空洞。默认到 64 MiB 或首次写入满 24 小时后，在下一条记录前滚动；空闲时不建空文件。一段对应一份持续追加的文档。关闭、重开句柄不滚段。迁移保留全部旧文档 ID，多份旧文档可引用同一段的不同消息。

文件头与帧使用 CRC32C，单条 JSON 上限 1 MiB。段文件由程序管理，不可手工修改、拼接或从备份单独替换。索引可以重建。

## 升级与离线迁移

首次启动自动升级 SQLite 至 V10，不自动转换旧 RAW 文件。有旧文档的分区显示 `NEEDS_MIGRATION`，旧记录仍可读，采集、检索和摘要继续工作，RAW 等待离线迁移。`RAW_ARCHIVE_ENABLED=false` 只暂停归档，不恢复旧 writer。

1. 正常停止应用，确认进程退出；复制数据库及其可能存在的 `-wal/-shm` 文件、整个 `BOT_STATE_DIR`，作为同一份停机备份。数据库和文件锁由操作系统自动释放，不删除锁文件绕过互斥。
2. 在工作副本上先 inspect，再 migrate、verify，核对消息数及文档 ID。正式数据也采用相同流程；不要直接把用户提供的历史导出当作测试写入目录。
3. migrate 成功后保留旧 `.md` 文件，确认读接口与来源正常。finalize 再次 verify，并持久化回收任务；每次真正删除旧文件前核对 SHA-256，文件被修改就报错并保留。
4. finalize 命令执行已排队回收，只有回收完成才返回成功。之后启动新版应用。外部备份不会自动删除。

源码环境需要 JDK 17/21、Maven wrapper。命令不启动 Spring、渠道或模型，不读取 `.env`。以下路径是**工作副本**示例：

```powershell
$database = 'D:/archive-work/qqbot.db'
$state = 'D:/archive-work/state'
./scripts/memory/raw-archive.ps1 -Command inspect -Database $database -StateDir $state -Report 'D:/archive-work/inspect.json'
./scripts/memory/raw-archive.ps1 -Command migrate -Database $database -StateDir $state -Report 'D:/archive-work/migrate.json'
./scripts/memory/raw-archive.ps1 -Command verify -Database $database -StateDir $state -Report 'D:/archive-work/verify.json'
./scripts/memory/raw-archive.ps1 -Command finalize -Database $database -StateDir $state -Report 'D:/archive-work/finalize.json'
```

数据库可位于 state 内或分开放置，工具不猜路径。`-GroupId UUID` 可限制分区；不传则处理全部群。报告须在 `memory` 外，不含正文。`-Now ISO-8601` 仅用于验证历史副本。工具默认迁移全部留存来源。生产设置有限冷留存时，先让应用完成过期清理与文件回收，再传 `-ArchiveRetentionDays N`（Java CLI：`--archive-retention-days N`）。未来 TEXT 到达发生时间后再迁移。缺文件、内容不一致或旧文档超过原格式的 100 条限制会拒绝切换。

已有 JAR 时每条命令追加 `-Jar 'D:/release/qqbot-pi-0.2.0-SNAPSHOT.jar'`，无需源码、Maven或模型凭据。也可直接运行：

```sh
java -Dloader.main=dev.qqbot.memory.RawArchiveCli -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --command inspect --database /work/qqbot.db --state-dir /work/state --report /work/inspect.json
```

inspect/verify 使用 SQLite 只读连接，不修索引、不截尾、不执行数据库迁移。修改命令同时取得数据库旁 `.bot.lock` 和 state 下 `memory/.raw-archive.lock`；应用未停止时立即失败。为取得一致核对结果，所有命令都建议在停机副本执行。

迁移阶段为 PREPARED → PUBLISHED → VERIFIED → FINALIZED。PREPARED 在写文件前登记 staging 路径；同一快照重跑会重建未发布文件，配置变化留下的旧 staging 进入回收清单。PUBLISHED 后重跑验证已发布数据；VERIFIED/FINALIZED 重跑不重复来源。PREPARED 源指纹变化报 `RAW_MIGRATION_SOURCE_CHANGED`，应检查副本或从配套备份重新开始，不能强行忽略。

## 容器和服务器包

镜像 `/app/app.jar` 已包含正式 codec、V10 和离线入口。停止 bot 后，复用当前 Compose 文件及项目名运行：

```sh
docker compose stop bot
docker compose run --rm --no-deps --entrypoint java bot \
  -Dloader.main=dev.qqbot.memory.RawArchiveCli -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --command inspect --database /app/.state/qqbot.db --state-dir /app/.state --report /app/.state/raw-inspect.json
```

将 inspect 依次换成 migrate、verify、finalize，各用独立报告名。先备份 state 卷，确保容器用户10001可写。不要同时启动新旧应用连接真实群。本机验证了 JAR 入口，Docker/实际生产卷仍需在部署环境演练。

## 提交、恢复和回收

RAW 独立单线程 worker 每轮每群至多扫描一批。`RawArchiveService` 选取本批原文并追加完整帧，随后执行 `force(true)`。未来 TEXT 阻断水位，超期 TEXT、命令和非文本可跳过。`RawArchivePublication` 在一个 SQL 事务中复核会话、旧水位和来源，再用旧水位 CAS 发布分段边界、引用和新水位。SQLite 必须使用 FULL/EXTRA synchronous。

启动校验 active 段的已提交前缀，截去 SQL 边界后的未提交尾部；sealed 段启动检查头和长度，读取逐帧验 CRC，离线 verify 扫全部帧并和 SQL 逐条核对。已提交内容损坏标记 BROKEN，禁止自动重试或截去“修复”。CRC不能替代备份，没有做真实断电实验。

`RawSegmentLog` 负责原文帧及未提交尾部恢复。`RawSparseIndex` 只加速定位；索引缺失、过短或损坏时从已提交日志重建，只读 verify 不写索引。锁顺序为 group 锁 → 文件操作 → 短 SQL 发布事务；清空/删群事务不等待文件锁，只使元数据失效并登记 GC。文件 I/O 串行、用完即关，稀疏索引和恢复缓存有上限。

清空立即推进 epoch 并使旧正文不可读。事务提交后才执行异步文件回收。删除前先校验清单内所有路径的群范围。Markdown 删除比对 tombstone token，保护清空后写入的同名人工记忆。回收队列跟踪迁移旧文件和所有纳管 epoch，删群后仍可重试。

默认永久保留。设有限冷留存后，到期先移除来源并同步计数。混合过期段逐帧比对 SQLite 原文，将剩余帧分批写入新 generation。发布事务复核旧段的代际、提交字节数和有序来源指纹，再切换元数据并登记旧代际回收。回收失败时，过期正文仍不可见。SQL 来源已过期但物理重写未完成时，先完成正常回收再执行离线 `verify`。

## 状态与故障

管理台显示正常/暂停/等待迁移/失败、有效待归档与已归档消息数、待回收及失败任务数。详情默认每页100条，支持加载更多；分页固定首屏 seq 上界，之后追加不进入这次翻页。来源过期返回409，收起文档重新打开。段记录不伪装成 `<id>.md`。

| 状态或错误 | 处理 |
|---|---|
| NEEDS_MIGRATION / RAW_MIGRATION_REQUIRED | 停机离线迁移；旧 RAW 失败任务也使用迁移工具 |
| RAW_DATABASE_LOCKED / RAW_ARCHIVE_LOCKED | 确认应用或其他工具进程已退出，核对 DB与state配套 |
| RAW_ARCHIVE_IO / RAW_ARCHIVE_COMMIT_FAILED | 检查容量、权限与SQLite；自动按1、2、4…60秒退避，管理台可提前重试 |
| RAW_RECORD_TOO_LARGE / RAW_SEQUENCE_MISSING | 不跳过问题记录；检查采集数据/历史迁移，不能手改水位 |
| RAW_ARCHIVE_CORRUPT / BROKEN | 停机verify，恢复配套备份；普通retry返回409 |
| RAW_MIGRATION_SOURCE_CHANGED | 保留旧文件，检查副本与哈希，不自动删除 |
| RAW_GC_IO / RAW_GC_INCOMPLETE | 失效内容不可读；修复权限/容量后collector自动重试，停机后可重跑finalize |
| RAW_SCOPE_CHANGED / RAW_SOURCE_CHANGED | 清空、删群或来源变化取消旧批次；新epoch重新开始 |

`GET /api/admin/groups/{id}/memory/policy` 追加 rawArchive；`GET .../documents/{id}?limit=100&cursor=...` 追加 storage、open、nextCursor、totalSources、snapshotSeq。`POST .../raw-archive/retry` 返回202，仍要求管理员及CSRF。SUMMARY原任务重试保持原规则。

## 配置

修改后重启。归档由 `BOT_ARCHIVE_RETENTION_DAYS` 控制，默认 `0`（永久保留），与 `BOT_HOT_WINDOW_DAYS` 无关：

| 环境变量 | 默认 | 范围/意义 |
|---|---:|---|
| RAW_ARCHIVE_ENABLED | true | 是否运行RAW worker |
| RAW_ARCHIVE_SEGMENT_BYTES | 67108864 | 8 MiB–1 GiB |
| RAW_ARCHIVE_SEGMENT_MAX_AGE_SECONDS | 86400 | 60–604800，下一条写入前检查 |
| RAW_ARCHIVE_POLL_MS | 1000 | 100–60000 |
| RAW_ARCHIVE_BATCH_MESSAGES | 500 | 1–2000，扫描行数上限 |
| RAW_ARCHIVE_BATCH_BYTES | 1048576 | 64 KiB–8 MiB目标；单个合法大帧可独占一批 |
| RAW_ARCHIVE_INDEX_INTERVAL_BYTES | 4096 | 1024–65536 |
| RAW_ARCHIVE_MAX_OPEN_SEGMENTS | 16 | 1–64，当前限制索引缓存段数；文件用完即关，无常驻句柄池 |
| RAW_ARCHIVE_GC_POLL_MS | 60000 | 1000–3600000 |

## 回滚与反向导出

恢复升级前 DB+state 备份会失去备份后的新增消息。旧 JAR 不可直接搭配 V10 或新段文件。要保留新增消息，停机执行：

```powershell
./scripts/memory/raw-archive.ps1 -Command export-legacy -Database $database -StateDir $state -Report 'D:/archive-work/export.json' -OutputDir 'D:/archive-work/legacy-export' -Jar 'D:/release/app.jar'
```

OutputDir 须尚不存在、父目录已存在，并位于当前 state/memory 外。工具创建新的 **V9数据库**及 `memory/`，复制共同表，重建所有仍有效 RAW 来源的 Markdown（每份最多100条），首份保留原ID，分片新ID写入 `export-manifest.json`；包含迁移后新增消息。仅导出副本为 V9 必填上下文列补入 `BUDGETED`。人工记忆和 READY 摘要一并复制，不重写当前记忆内容，不伪造或删改 Flyway 历史。

用匹配 V9 的版本检查新目录后再决定切换，保留新版数据供核对。工具不复制 pi checkpoints，兼容版本从SQL重建上下文。测试覆盖新增消息导出、V9 schema与外键完整性，没有执行真实版本切换。
