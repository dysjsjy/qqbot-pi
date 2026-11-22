/**
 * [INPUT]: 依赖群配置行与共用的管理员 ID 规则。
 * [OUTPUT]: 提供群范围快照与当前机器人管理员判断。
 * [POS]: 入站事务用群配置快照隔离群聊、账号和私聊。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.text.AdminIds;
import java.util.Map;
import java.util.UUID;

public record Group(
        UUID id,
        String channel,
        String accountId,
        String externalId,
        String name,
        boolean enabled,
        boolean replyEnabled,
        String adminIds,
        long epoch,
        long revision,
        String chatType,
        String targetId) {
    public Group(
            UUID id,
            String channel,
            String accountId,
            String externalId,
            String name,
            boolean enabled,
            boolean replyEnabled,
            String adminIds,
            long epoch,
            long revision) {
        this(
                id,
                channel,
                accountId,
                externalId,
                name,
                enabled,
                replyEnabled,
                adminIds,
                epoch,
                revision,
                "GROUP",
                null);
    }

    public static Group from(Map<String, Object> r) {
        return new Group(
                (UUID) r.get("id"),
                (String) r.get("channel"),
                (String) r.get("account_id"),
                (String) r.get("external_id"),
                (String) r.get("name"),
                (boolean) r.get("enabled"),
                (boolean) r.get("reply_enabled"),
                (String) r.get("admin_ids"),
                ((Number) r.get("epoch")).longValue(),
                ((Number) r.get("revision")).longValue(),
                (String) r.getOrDefault("chat_type", "GROUP"),
                (String) r.get("target_id"));
    }

    public boolean privateChat() {
        return "PRIVATE".equals(chatType);
    }

    public boolean isAdmin(String sender) {
        return !privateChat() && AdminIds.parse(adminIds).contains(sender);
    }
}
