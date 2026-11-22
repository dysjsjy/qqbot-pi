/**
 * [INPUT]: 依赖生成请求的业务用途。
 * [OUTPUT]: 定义生成用途并区分前台与后台。
 * [POS]: 用量账目与运行槽共享的枚举契约。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.model;

public enum Purpose {
    CHAT,
    MEMORY_QUERY,
    MEMORY_SUMMARY,
    DAILY_SUMMARY,
    MEMBER_PROFILE;

    public boolean background() {
        return this == MEMORY_SUMMARY || this == DAILY_SUMMARY || this == MEMBER_PROFILE;
    }
}
