# persistence/
> L2 | Parent: ../AGENTS.md

- `Db.java`：参数化 SQL、有类型的单行与多行读取、事务、JSON 转换与结构化审计。
- `DatabaseValues.java`：SQLite 标量与 UUID、时间、布尔值的双向转换。
- `Lifecycle.java`：SQLite 文件锁、重启恢复与 embedding 身份校验。

业务模块通过 Db 访问 SQLite。
Lifecycle 持有文件锁，阻止同一数据库运行多个实例。

[PROTOCOL]: Update this header on change, then check AGENTS.md
