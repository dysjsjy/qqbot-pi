/**
 * [INPUT]: 绑定部署的画像总开关、轮询频率与聊天预算。
 * [OUTPUT]: 提供画像后台与聊天路径共享的界限。
 * [POS]: 总开关默认关闭，不替代群级建模和注入开关。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bot.experimental.member-profile")
public record MemberProfileProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30000") long pollMs,
        @DefaultValue("500") int contextTokens,
        @DefaultValue("4") int maxInjectedMembers) {
    public MemberProfileProperties {
        if (pollMs < 5000
                || pollMs > 300000
                || contextTokens < 128
                || contextTokens > 1500
                || maxInjectedMembers < 1
                || maxInjectedMembers > 8) {
            throw new IllegalArgumentException("INVALID_MEMBER_PROFILE_CONFIG");
        }
    }

    public static MemberProfileProperties defaults() {
        return new MemberProfileProperties(false, 30000, 500, 4);
    }

    public int injectionLimit() {
        return Math.min(4, maxInjectedMembers);
    }

    public int tokenLimit() {
        return Math.min(500, contextTokens);
    }
}
