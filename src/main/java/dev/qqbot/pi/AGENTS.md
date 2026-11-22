# pi/
> L2 | Parent: ../AGENTS.md

- `GenerationBudget.java`：计算首内容、空闲和绝对截止时间。
- `PiCheckpoints.java`：在文件边界校验旧会话，复制启动材料并原子发布检查点。
- `PiExchange.java`：协调有界 RPC 确认、提示分发、最终回复、原生会话状态与取消。
- `PiJsonLines.java`：读写有字节上限的 UTF-8 JSONL；把 RPC 帧转为有类型的事件。
- `PiLaunch.java`：写入单次运行的配置并构建白名单环境，不启动子进程。
- `PiRunRegistry.java`：登记活跃调用，分配公平执行槽并公布取消状态。
- `PiRuntime.java`：校验并冻结请求，依次准入、启动、生成、发布检查点和回收资源。
- `PiSession.java`：区分无会话模式与携带历史检查点的原生会话。
- `PiUsageCapture.java`：保存最新可信的模型用量和子进程观测值。
- `ProcessResources.java`：终止进程树，清理单次运行的临时目录。

[PROTOCOL]: Update this header on change, then check AGENTS.md
