/**
 * [INPUT]: 接收 SQLite 分段元数据或分段写入结果。
 * [OUTPUT]: 提供分区、序号范围、字节边界与提交状态。
 * [POS]: 分段存储、查询与归档事务共享的值对象。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public record RawSegment(
        UUID id,
        RawPartition partition,
        long baseSeq,
        long lastSeq,
        long generation,
        long committedBytes,
        long recordCount,
        long openedMs,
        long minOccurredMs,
        long maxOccurredMs,
        String state) {
    public static RawSegment from(Map<String, Object> row) {
        return new RawSegment(
                (UUID) row.get("id"),
                new RawPartition((UUID) row.get("group_id"), number(row, "epoch")),
                number(row, "base_seq"),
                number(row, "last_seq"),
                number(row, "generation"),
                number(row, "committed_bytes"),
                number(row, "record_count"),
                number(row, "opened_ms"),
                number(row, "min_occurred_ms"),
                number(row, "max_occurred_ms"),
                (String) row.get("state"));
    }

    static RawSegment fromSqliteRow(ResultSet row, int rowNumber) throws SQLException {
        RawPartition partition =
                new RawPartition(UUID.fromString(row.getString("group_id")), row.getLong("epoch"));
        return new RawSegment(
                UUID.fromString(row.getString("id")),
                partition,
                row.getLong("base_seq"),
                row.getLong("last_seq"),
                row.getLong("generation"),
                row.getLong("committed_bytes"),
                row.getLong("record_count"),
                row.getLong("opened_ms"),
                row.getLong("min_occurred_ms"),
                row.getLong("max_occurred_ms"),
                row.getString("state"));
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public RawSegment sealed() {
        return new RawSegment(
                id,
                partition,
                baseSeq,
                lastSeq,
                generation,
                committedBytes,
                recordCount,
                openedMs,
                minOccurredMs,
                maxOccurredMs,
                "SEALED");
    }
}
