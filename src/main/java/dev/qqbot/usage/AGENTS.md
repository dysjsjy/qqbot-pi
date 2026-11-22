# usage/
> L2 | Parent: ../AGENTS.md

- `MeteredTextGenerator.java`：登记模型调用和分发，合并与节流记录用量，写终态前刷新观察。
- `ModelCallStore.java`：调用状态、已知用量和价格快照持久化。
- `PriceCalculator.java`：按配置价格计算已知生成费用。
- `UsageQueries.java`：按北京时间和生成用途汇总用量。HTTP 保留平铺的总计字段。



[PROTOCOL]: Update this header on change, then check AGENTS.md
