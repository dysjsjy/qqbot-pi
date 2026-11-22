-- [INPUT]: 依赖 V7 的 memory_policy 表。
-- [OUTPUT]: 移除上下文模式字段。保留归档策略、修订号和记忆内容。
-- [POS]: 已有实例随 Flyway 升级切换到统一上下文。
-- [PROTOCOL]: Update this header on change, then check AGENTS.md
ALTER TABLE memory_policy DROP COLUMN context_mode;
