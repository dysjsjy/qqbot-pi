/**
 * [INPUT]: 接收分段元数据、原文记录与已打开的文件通道。
 * [OUTPUT]: 编解码 V1 文件头、原文帧及 CRC32C；拒绝越过提交边界的帧。
 * [POS]: 原文和稀疏索引共用的磁盘格式；不生成路径或维护文件状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

final class RawSegmentFormat {
    static final int HEADER_BYTES = 64;
    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    static final int LOG_MAGIC = 0x51524157;
    static final int INDEX_MAGIC = 0x51494458;
    static final String CORRUPT = "RAW_ARCHIVE_CORRUPT";
    private static final int FRAME_HEADER_BYTES = 16;
    private static final int FRAME_CHECKED_HEADER_BYTES = 12;
    private static final int FILE_CHECKED_HEADER_BYTES = 60;
    private static final short FORMAT_VERSION = 1;
    private static final String MESSAGE_ID = "messageId";
    private static final String MESSAGE_KEY = "messageKey";
    private static final String SENDER_ID = "senderId";
    private static final String SENDER_NAME = "senderName";
    private static final String BODY = "body";
    private static final String OCCURRED_AT = "occurredAtMs";
    private static final String RECEIVED_AT = "receivedAtMs";
    private static final String REPLY_TO = "replyToMessageKey";
    private static final String KIND = "kind";
    private final ObjectMapper json;

    RawSegmentFormat(ObjectMapper json) {
        this.json = json;
    }

    int frameBytes(RawRecord record) {
        return encodePayload(record).length + FRAME_HEADER_BYTES;
    }

    byte[] encodePayload(RawRecord record) {
        try {
            var value =
                    new Payload(
                            record.messageId(),
                            record.messageKey(),
                            record.senderId(),
                            record.senderName(),
                            record.body(),
                            record.occurredAtMs(),
                            record.receivedAtMs(),
                            record.replyToMessageKey(),
                            record.kind());
            byte[] payload = json.writeValueAsBytes(value);
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new RawArchiveException("RAW_RECORD_TOO_LARGE");
            }
            return payload;
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_RECORD_INVALID", failure);
        }
    }

    private RawRecord decodePayload(long sequence, byte[] payload) {
        try {
            JsonNode value = json.readTree(payload);
            return new RawRecord(
                    sequence,
                    UUID.fromString(value.required(MESSAGE_ID).asText()),
                    nullableText(value, MESSAGE_KEY),
                    nullableText(value, SENDER_ID),
                    nullableText(value, SENDER_NAME),
                    nullableText(value, BODY),
                    value.required(OCCURRED_AT).longValue(),
                    value.required(RECEIVED_AT).longValue(),
                    nullableText(value, REPLY_TO),
                    value.required(KIND).asText());
        } catch (IOException | RuntimeException invalid) {
            throw new RawArchiveException(CORRUPT, invalid);
        }
    }

    private static String nullableText(JsonNode value, String field) {
        JsonNode node = value.required(field);
        return node.isNull() ? null : node.asText();
    }

    void writeFrame(FileChannel channel, RawRecord record, Consumer<String> checkpoint)
            throws IOException {
        byte[] payload = encodePayload(record);
        ByteBuffer prefix = ByteBuffer.allocate(FRAME_HEADER_BYTES);
        prefix.putLong(record.seq());
        prefix.putInt(payload.length);
        prefix.putInt(frameChecksum(record.seq(), payload));
        // SIDE EFFECT: 两次写入允许测试分别模拟帧头和内容写入中断。
        write(channel, prefix.flip());
        checkpoint.accept("frameHeaderWritten");
        write(channel, ByteBuffer.wrap(payload));
        checkpoint.accept("frameWritten");
    }

    RawRecord readFrame(FileChannel channel, long boundary) throws IOException {
        FrameHeader prefix = readFrameHeader(channel, boundary);
        byte[] payload = new byte[prefix.length()];
        read(channel, ByteBuffer.wrap(payload));
        if (frameChecksum(prefix.sequence(), payload) != prefix.checksum()) {
            throw new RawArchiveException(CORRUPT);
        }
        return decodePayload(prefix.sequence(), payload);
    }

    private static FrameHeader readFrameHeader(FileChannel channel, long boundary)
            throws IOException {
        if (boundary - channel.position() < FRAME_HEADER_BYTES) {
            throw new RawArchiveException(CORRUPT);
        }
        ByteBuffer bytes = ByteBuffer.allocate(FRAME_HEADER_BYTES);
        read(channel, bytes);
        bytes.flip();
        var prefix = new FrameHeader(bytes.getLong(), bytes.getInt(), bytes.getInt());
        if (prefix.length() < 1
                || prefix.length() > MAX_PAYLOAD_BYTES
                || prefix.length() > boundary - channel.position()) {
            throw new RawArchiveException(CORRUPT);
        }
        return prefix;
    }

    private static int frameChecksum(long sequence, byte[] payload) {
        ByteBuffer prefix = ByteBuffer.allocate(FRAME_CHECKED_HEADER_BYTES);
        prefix.putLong(sequence);
        prefix.putInt(payload.length);
        // CRC 覆盖序号、载荷字节数和完整载荷，不覆盖 CRC 字段本身。
        var crc = new CRC32C();
        crc.update(prefix.flip());
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    static ByteBuffer fileHeader(RawSegment segment, int magic) {
        ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES);
        bytes.putInt(magic);
        bytes.putShort(FORMAT_VERSION);
        bytes.putShort((short) HEADER_BYTES);
        putUuid(bytes, segment.partition().groupId());
        bytes.putLong(segment.partition().epoch());
        putUuid(bytes, segment.id());
        bytes.putLong(segment.baseSeq());
        bytes.putInt(0);
        // CRC 覆盖文件类型、版本、分区和分段身份等前 60 字节。
        var crc = new CRC32C();
        crc.update(bytes.array(), 0, FILE_CHECKED_HEADER_BYTES);
        bytes.putInt((int) crc.getValue());
        return bytes.flip();
    }

    private static void putUuid(ByteBuffer bytes, UUID id) {
        bytes.putLong(id.getMostSignificantBits());
        bytes.putLong(id.getLeastSignificantBits());
    }

    static void checkHeader(FileChannel channel, RawSegment segment, int magic) throws IOException {
        channel.position(0);
        ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES);
        read(channel, bytes);
        if (!Arrays.equals(bytes.array(), fileHeader(segment, magic).array())) {
            throw new RawArchiveException(CORRUPT);
        }
    }

    static void read(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) < 0) {
                throw new IOException("Unexpected EOF");
            }
        }
    }

    static void write(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private record FrameHeader(long sequence, int length, int checksum) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({
        MESSAGE_ID,
        MESSAGE_KEY,
        SENDER_ID,
        SENDER_NAME,
        BODY,
        OCCURRED_AT,
        RECEIVED_AT,
        REPLY_TO,
        KIND
    })
    private record Payload(
            @JsonProperty(MESSAGE_ID) UUID messageId,
            @JsonProperty(MESSAGE_KEY) String messageKey,
            @JsonProperty(SENDER_ID) String senderId,
            @JsonProperty(SENDER_NAME) String senderName,
            @JsonProperty(BODY) String body,
            @JsonProperty(OCCURRED_AT) long occurredAtMs,
            @JsonProperty(RECEIVED_AT) long receivedAtMs,
            @JsonProperty(REPLY_TO) String replyToMessageKey,
            @JsonProperty(KIND) String kind) {}
}
