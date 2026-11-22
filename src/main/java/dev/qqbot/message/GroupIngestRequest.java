package dev.qqbot.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record GroupIngestRequest(
        @NotBlank @Size(max = 16) String channel,
        @NotBlank @Size(max = 160) String accountId,
        @NotBlank @Size(max = 160) String groupId,
        @NotBlank @Size(max = 160) String messageId,
        @NotBlank @Size(max = 160) String senderId,
        @NotBlank @Size(max = 200) String senderName,
        @NotNull Instant occurredAt,
        @NotNull @Size(max = 8000) String text,
        boolean mentionsBot,
        boolean isSelf,
        @jakarta.validation.constraints.Pattern(regexp = "TEXT|NON_TEXT") String kind,
        boolean privateChat,
        @Size(max = 256) String replyToMessageKey) {
    public GroupIngestRequest(
            String channel,
            String accountId,
            String groupId,
            String messageId,
            String senderId,
            String senderName,
            Instant occurredAt,
            String text,
            boolean mentionsBot,
            boolean isSelf,
            String kind,
            boolean privateChat) {
        this(
                channel,
                accountId,
                groupId,
                messageId,
                senderId,
                senderName,
                occurredAt,
                text,
                mentionsBot,
                isSelf,
                kind,
                privateChat,
                null);
    }

    public GroupIngestRequest(
            String channel,
            String accountId,
            String groupId,
            String messageId,
            String senderId,
            String senderName,
            Instant occurredAt,
            String text,
            boolean mentionsBot,
            boolean isSelf,
            String kind) {
        this(
                channel,
                accountId,
                groupId,
                messageId,
                senderId,
                senderName,
                occurredAt,
                text,
                mentionsBot,
                isSelf,
                kind,
                false);
    }

    public GroupIngestRequest(
            String channel,
            String accountId,
            String groupId,
            String messageId,
            String senderId,
            String senderName,
            Instant occurredAt,
            String text,
            boolean mentionsBot,
            boolean isSelf) {
        this(
                channel,
                accountId,
                groupId,
                messageId,
                senderId,
                senderName,
                occurredAt,
                text,
                mentionsBot,
                isSelf,
                "TEXT",
                false);
    }
}
