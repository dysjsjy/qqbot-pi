/**
 * [INPUT]: 接收原文消息、SQLite 行或读出的分段字段。
 * [OUTPUT]: 提供归档记录及旧版 Markdown 表示。
 * [POS]: 原文归档的值对象；不读写数据库或文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

public record RawRecord(
        long seq,
        UUID messageId,
        String messageKey,
        String senderId,
        String senderName,
        String body,
        long occurredAtMs,
        long receivedAtMs,
        String replyToMessageKey,
        String kind) {
    static final String MARKDOWN_TITLE = "# 群消息原样记录\n";
    static final String TEXT = "TEXT";

    public RawRecord {
        if (seq <= 0 || messageId == null || !TEXT.equals(kind)) {
            throw new IllegalArgumentException("Invalid RAW record");
        }
    }

    public static RawRecord from(Map<String, Object> row) {
        if (!TEXT.equals(row.get("kind"))) {
            throw new RawArchiveException("RAW_SOURCE_INVALID");
        }
        return new RawRecord(
                ((Number) row.get("event_seq")).longValue(),
                (UUID) row.get("id"),
                (String) row.get("message_key"),
                (String) row.get("sender_id"),
                (String) row.get("sender_name"),
                (String) row.get("body"),
                ((Timestamp) row.get("occurred_at")).getTime(),
                ((Timestamp) row.get("received_at")).getTime(),
                (String) row.get("reply_to_key"),
                TEXT);
    }

    static RawRecord fromSqliteRow(ResultSet row, int rowNumber) throws SQLException {
        if (!TEXT.equals(row.getString("kind"))) {
            throw new RawArchiveException("RAW_SOURCE_INVALID");
        }
        return new RawRecord(
                row.getLong("event_seq"),
                UUID.fromString(row.getString("id")),
                row.getString("message_key"),
                row.getString("sender_id"),
                row.getString("sender_name"),
                row.getString("body"),
                row.getLong("occurred_at"),
                row.getLong("received_at"),
                row.getString("reply_to_key"),
                TEXT);
    }

    public String markdown() {
        return "\n## ["
                + messageId
                + "] "
                + java.time.Instant.ofEpochMilli(occurredAtMs)
                + " · "
                + senderName
                + "\n\n"
                + body
                + "\n";
    }
}
