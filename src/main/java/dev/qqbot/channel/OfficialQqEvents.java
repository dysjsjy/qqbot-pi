/**
 * [INPUT]: 接收已校验的官方 QQ 回调和网关事件。
 * [OUTPUT]: 提交标准入站请求，提供近期事件诊断。
 * [POS]: 回调与长连接共用提及识别。诊断只保存事件和入站回执字段。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.BotProperties;
import dev.qqbot.message.GroupIngestRequest;
import dev.qqbot.message.IngestService;
import dev.qqbot.text.Texts;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfficialQqEvents {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OfficialQqEvents.class);
    private static final Pattern MENTION = Pattern.compile("<@!?([A-Za-z0-9_-]+)>");
    private final BotProperties properties;
    private final IngestService ingest;
    private final Validator validator;
    private final Deque<RecentEvent> recent = new ArrayDeque<>();
    private final Set<String> selfIds = ConcurrentHashMap.newKeySet();

    public OfficialQqEvents(BotProperties properties, IngestService ingest, Validator validator) {
        this.properties = properties;
        this.ingest = ingest;
        this.validator = validator;
        addSelfId(properties.qq().official().appId());
        addSelfId(properties.qq().official().botOpenid());
    }

    public void identifyBot(JsonNode user) {
        for (String key : List.of("id", "member_openid", "user_openid", "union_openid")) {
            addSelfId(user.path(key).asText(""));
        }
    }

    private void addSelfId(String id) {
        if (id != null && id.matches("[A-Za-z0-9_-]{1,160}")) {
            selfIds.add(id);
        }
    }

    public synchronized List<RecentEvent> events() {
        return List.copyOf(recent);
    }

    public void dispatch(JsonNode event, String transport) {
        var data = event.path("d");
        String type = event.path("t").asText("");
        if (event.path("op").asInt(-1) != 0) {
            return;
        }
        boolean privateChat = type.equals("C2C_MESSAGE_CREATE");
        if (!privateChat
                && !Set.of("GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE").contains(type)) {
            remember(type, data, false, false);
            return;
        }
        String member =
                data.path("author").path(privateChat ? "user_openid" : "member_openid").asText("");
        if (member.isBlank()) {
            member = data.path("author").path("id").asText("");
        }
        String text = data.path("content").asText("");
        boolean mentionsBot = type.equals("GROUP_AT_MESSAGE_CREATE") || privateChat;
        for (JsonNode mention : data.path("mentions")) {
            for (String key : List.of("id", "member_openid", "user_openid", "union_openid")) {
                if (selfIds.contains(mention.path(key).asText(""))) {
                    mentionsBot = true;
                }
            }
        }
        var matcher = MENTION.matcher(text);
        var cleaned = new StringBuffer();
        boolean removed = false;
        while (matcher.find()) {
            if (selfIds.contains(matcher.group(1))) {
                mentionsBot = true;
                removed = true;
                matcher.appendReplacement(cleaned, "");
            }
        }
        matcher.appendTail(cleaned);
        if (removed) {
            text = cleaned.toString().strip();
        }
        String name = data.path("author").path("username").asText(member);
        if (name.isBlank()) {
            name = member;
        }
        Instant at;
        try {
            at = java.time.OffsetDateTime.parse(data.path("timestamp").asText()).toInstant();
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QQ_TIMESTAMP");
        }
        var request =
                new GroupIngestRequest(
                        "QQ",
                        properties.qq().accountId(),
                        privateChat ? member : data.path("group_openid").asText(""),
                        data.path("id").asText(""),
                        member,
                        name,
                        at,
                        text,
                        mentionsBot,
                        data.path("author").path("bot").asBoolean(false)
                                || selfIds.contains(member),
                        text.isBlank() ? "NON_TEXT" : "TEXT",
                        privateChat);
        if (!validator.validate(request).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QQ_MESSAGE");
        }
        var receipt = ingest.ingest(request);
        remember(type, data, receipt.accepted(), receipt.duplicate());
        log.info(
                "QQ_EVENT transport={} type={} group={} accepted={} duplicate={} mentionsBot={} command={}",
                transport,
                type,
                request.groupId(),
                receipt.accepted(),
                receipt.duplicate(),
                mentionsBot,
                !Texts.command(text).isEmpty());
    }

    private synchronized void remember(
            String type, JsonNode data, boolean accepted, boolean duplicate) {
        JsonNode author = data.path("author");
        String member =
                author.path("member_openid")
                        .asText(author.path("user_openid").asText(author.path("id").asText("")));
        var entry =
                new RecentEvent(
                        Instant.now().toString(),
                        type,
                        data.path("group_openid").asText(""),
                        member,
                        type.equals("C2C_MESSAGE_CREATE") ? "PRIVATE" : "GROUP",
                        accepted,
                        duplicate);
        // SIDE EFFECT: 诊断缓冲只保存有限的近期事件。
        recent.addFirst(entry);
        while (recent.size() > 50) {
            recent.removeLast();
        }
    }

    public record RecentEvent(
            String receivedAt,
            String eventType,
            String groupOpenid,
            String memberOpenid,
            String chatType,
            boolean accepted,
            boolean duplicate) {}
}
