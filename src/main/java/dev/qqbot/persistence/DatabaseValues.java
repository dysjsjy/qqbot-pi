/**
 * [INPUT]: 依赖JDBC 标量、Java 时间类型与 JSON。
 * [OUTPUT]: 转换 SQLite 存储值和业务类型。
 * [POS]: Db 内部值边界；不依赖业务模块。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;

final class DatabaseValues {
    private static final Set<String> UUID_COLUMNS =
            Set.of(
                    "id",
                    "group_id",
                    "message_id",
                    "conversation_id",
                    "agent_run_id",
                    "daily_run_id",
                    "scoped_group_id",
                    "reply_to_message_id",
                    "document_id",
                    "run_id",
                    "chunk_id",
                    "segment_id");
    private static final Set<String> TIME_COLUMNS =
            Set.of(
                    "created_at",
                    "updated_at",
                    "finished_at",
                    "received_at",
                    "occurred_at",
                    "collection_since",
                    "last_received_at",
                    "last_heartbeat_at",
                    "next_run_at",
                    "next_attempt_at",
                    "starts_at",
                    "ends_at",
                    "snapshot_at",
                    "last_indexed",
                    "context_occurred_at",
                    "delivered_at",
                    "source_time",
                    "indexed_at");
    private final ObjectMapper json;

    DatabaseValues(ObjectMapper json) {
        this.json = json;
    }

    Object decode(String column, Object value) {
        if (value == null) {
            return null;
        }
        if (UUID_COLUMNS.contains(column)) {
            return UUID.fromString(value.toString());
        }
        if (Set.of("enabled", "reply_enabled", "dispatched").contains(column)) {
            return ((Number) value).intValue() != 0;
        }
        if (Set.of(
                        "epoch",
                        "current_epoch",
                        "generation",
                        "revision",
                        "event_seq",
                        "context_seq",
                        "last_message_seq",
                        "estimated_input_tokens",
                        "max_output_tokens",
                        "input_tokens",
                        "output_tokens",
                        "cache_read_tokens",
                        "cache_write_tokens",
                        "reasoning_tokens")
                .contains(column)) {
            return ((Number) value).longValue();
        }
        if (TIME_COLUMNS.contains(column)) {
            return new Timestamp(((Number) value).longValue());
        }
        if (column.equals("business_date")) {
            return java.sql.Date.valueOf(value.toString());
        }
        if (column.equals("local_time")) {
            return Time.valueOf(LocalTime.parse(value.toString()));
        }
        if (Set.of("coverage", "source_ids").contains(column)) {
            return parse(value.toString(), Object.class);
        }
        return value;
    }

    Object[] bind(Object[] values) {
        return Arrays.stream(values).map(this::bind).toArray();
    }

    private Object bind(Object value) {
        if (value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof java.sql.Date
                || value instanceof Time) {
            return value.toString();
        }
        return value;
    }

    String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("JSON encoding failed", failure);
        }
    }

    <T> T parse(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Stored JSON invalid", failure);
        }
    }
}
