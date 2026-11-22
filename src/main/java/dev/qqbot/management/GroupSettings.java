/**
 * [INPUT]: 依赖Jakarta 输入校验约束。
 * [OUTPUT]: 提供群配置不可变输入契约。
 * [POS]: 管理业务与 HTTP 请求的校验边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.management;

import jakarta.validation.constraints.*;

public record GroupSettings(
        @NotBlank @Pattern(regexp = "QQ|WEIXIN") String channel,
        @NotBlank @Size(max = 160) String accountId,
        @NotBlank @Size(max = 160) String externalId,
        @NotBlank @Size(max = 200) String name,
        boolean enabled,
        boolean replyEnabled,
        @Size(max = 2000) String adminIds,
        long revision) {}
