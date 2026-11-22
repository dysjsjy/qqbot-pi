/**
 * [INPUT]: 读取测试类路径中的合成会话夹具。
 * [OUTPUT]: 将用例装入隔离 SQLite 并提供上下文请求。
 * [POS]: 为 T10 上下文与效果回归提供同一套确定性数据。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.qqbot.memory.*;
import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class MemoryV2FixtureLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode DOCUMENT = readDocument();
    private final Db db;
    private final JsonNode specification;
    private final Map<String, UUID> entities = new LinkedHashMap<>();
    private final Map<String, String> sourceKeys = new LinkedHashMap<>();
    private final Map<String, JsonNode> events = new LinkedHashMap<>();
    private final Instant now;

    record LoadedCase(
            String id,
            JsonNode specification,
            ContextRequest request,
            UUID runId,
            Map<String, UUID> entities,
            Map<String, String> sourceKeys,
            Map<String, JsonNode> events) {
        LoadedCase {
            entities = Map.copyOf(entities);
            sourceKeys = Map.copyOf(sourceKeys);
            events = Map.copyOf(events);
        }

        Clock clock() {
            return Clock.fixed(request.scope().evaluatedAt(), ZoneOffset.UTC);
        }
    }

    MemoryV2FixtureLoader(Db db, JsonNode specification) {
        this.db = db;
        this.specification = specification;
        now = Instant.parse(value(specification, "now").asText());
    }

    static List<JsonNode> cases() {
        var result = new ArrayList<JsonNode>();
        DOCUMENT.path("cases").forEach(result::add);
        return List.copyOf(result);
    }

    static List<JsonNode> effectCases() {
        var document = readDocument("effect-cases.json");
        if (document.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException("Unsupported effect fixture format");
        }
        var result = new ArrayList<JsonNode>();
        for (JsonNode specification : document.path("cases")) {
            ObjectNode merged = JSON.createObjectNode();
            merged.setAll((ObjectNode) document.path("defaults"));
            merged.setAll((ObjectNode) specification);
            result.add(merged);
        }
        return List.copyOf(result);
    }

    static UUID id(String caseId, String kind, String alias) {
        return UUID.nameUUIDFromBytes(
                (caseId + ':' + kind + ':' + alias).getBytes(StandardCharsets.UTF_8));
    }

    LoadedCase load(MarkdownMemory files) {
        if (DOCUMENT.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException("Unsupported fixture format");
        }
        for (JsonNode event : specification.path("events")) {
            String alias = event.path("id").asText();
            if (events.put(alias, event) != null) {
                throw new IllegalArgumentException("Duplicate fixture event: " + alias);
            }
            group(scope(event));
        }
        JsonNode trigger = specification.path("trigger");
        UUID group = group(scope(trigger));
        for (JsonNode event : events.values()) {
            if ("USER".equals(event.path("role").asText())) {
                message(event, false);
            }
        }
        for (JsonNode event : events.values()) {
            if ("ASSISTANT".equals(event.path("role").asText())) {
                assistant(event);
            }
        }
        UUID message = message(trigger, true),
                conversation =
                        conversation(
                                scope(trigger),
                                trigger.path("sender").asText(),
                                value(trigger, "generation").asLong());
        UUID run = entity("run", "trigger:" + trigger.path("id").asText());
        Instant occurredAt = Instant.parse(value(trigger, "occurredAt").asText());
        long sequence = trigger.path("seq").asLong();
        db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,context_seq,context_occurred_at,created_at) values(?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",
                run,
                group,
                message,
                conversation,
                value(trigger, "generation").asLong(),
                value(trigger, "epoch").asLong(),
                trigger.path("kind").asText(),
                trigger.path("question").asText(),
                sequence,
                occurredAt,
                now);
        if (!value(specification, "manual").asText().isEmpty()) {
            files.write(group, value(specification, "manual").asText());
        }
        if (!value(specification, "summary").asText().isEmpty()) {
            throw new IllegalArgumentException("Summary fixtures require explicit source links");
        }
        var request =
                new ContextRequest(
                        new MemoryScope(
                                group,
                                value(trigger, "epoch").asLong(),
                                message,
                                sequence,
                                occurredAt,
                                now),
                        conversation,
                        value(trigger, "generation").asLong(),
                        trigger.path("sender").asText(),
                        trigger.path("question").asText(),
                        QueryRouter.route(
                                trigger.path("kind").asText(), trigger.path("question").asText()));
        return new LoadedCase(
                specification.path("id").asText(),
                specification,
                request,
                run,
                entities,
                sourceKeys,
                events);
    }

    private UUID group(String alias) {
        String key = "group:" + alias;
        UUID previous = entities.get(key);
        if (previous != null) {
            return previous;
        }
        UUID group = entity("group", alias);
        boolean privateChat = alias.startsWith("DM_");
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,reply_enabled,epoch,chat_type,target_id,created_at) values(?,'QQ','fixture',?,?,true,true,?,?,?,?)",
                group,
                specification.path("id").asText() + ":" + alias,
                alias,
                value(specification, "epoch").asLong(),
                privateChat ? "PRIVATE" : "GROUP",
                privateChat ? alias.substring(3) : null,
                now);
        return group;
    }

    private UUID message(JsonNode event, boolean trigger) {
        String alias = event.path("id").asText();
        UUID message = entity("message", alias);
        UUID group = group(scope(event));
        long sequence = event.path("seq").asLong();
        Instant occurredAt = Instant.parse(value(event, "occurredAt").asText());
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq,reply_to_key) values(?,?,?,?,?,?,?,?,?,'TEXT',?,?)",
                message,
                group,
                event.path("messageKey").asText(alias),
                event.path("sender").asText(),
                event.path("name").asText(),
                event.path(trigger ? "question" : "body").asText(),
                occurredAt,
                now.minusSeconds(1).plusMillis(sequence),
                value(event, "epoch").asLong(),
                sequence,
                event.hasNonNull("replyToMessageKey")
                        ? event.path("replyToMessageKey").asText()
                        : null);
        sourceKeys.put(alias, "message:" + message + ":0");
        sequence(group, sequence);
        return message;
    }

    private void assistant(JsonNode event) {
        String replyFor = event.path("replyFor").asText();
        JsonNode origin = events.get(replyFor);
        if (origin == null
                || !"USER".equals(origin.path("role").asText())
                || !scope(origin).equals(scope(event))) {
            throw new IllegalArgumentException("Assistant fixture has no scoped USER origin");
        }
        String runAlias = scope(event) + ":" + replyFor;
        UUID run = entities.get("run:" + runAlias);
        UUID group = group(scope(event));
        String owner = event.path("conversation").asText(origin.path("sender").asText());
        long generation = value(event, "generation").asLong();
        UUID conversation = conversation(scope(event), owner, generation);
        if (run == null) {
            run = entity("run", runAlias);
            db.exec(
                    "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,final_text,context_seq,context_occurred_at,created_at,finished_at) values(?,?,?,?,?,?,'CHAT',?,'SUCCEEDED','',?,?,?,?)",
                    run,
                    group,
                    entities.get("message:" + replyFor),
                    conversation,
                    generation,
                    value(event, "epoch").asLong(),
                    origin.path("body").asText(),
                    origin.path("seq").asLong(),
                    Instant.parse(value(origin, "occurredAt").asText()),
                    now.minusSeconds(120),
                    now.minusSeconds(60));
        }
        String alias = event.path("id").asText();
        UUID outbox = entity("outbox", alias);
        String state = event.path("state").asText();
        Long sequence = event.hasNonNull("seq") ? event.path("seq").asLong() : null;
        Instant deliveredAt =
                "SENT".equals(state) && event.hasNonNull("deliveredAt")
                        ? Instant.parse(event.path("deliveredAt").asText())
                        : null;
        if (!"SENT".equals(state) && sequence != null) {
            throw new IllegalArgumentException("Unsent fixture has a delivery sequence");
        }
        db.exec(
                "insert into outbox(id,group_id,result_key,agent_run_id,conversation_id,generation,epoch,part_index,body,state,event_seq,delivered_at,created_at,next_attempt_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                outbox,
                group,
                run.toString(),
                run,
                conversation,
                generation,
                value(event, "epoch").asLong(),
                event.path("partIndex").asInt(0),
                event.path("body").asText(),
                state,
                sequence,
                deliveredAt,
                now.minusSeconds(60),
                now);
        String previous =
                (String)
                        db.one("select final_text from agent_run where id=?", run)
                                .get("final_text");
        db.exec(
                "update agent_run set final_text=? where id=?",
                previous + (previous.isEmpty() ? "" : "\n") + event.path("body").asText(),
                run);
        sourceKeys.put(alias, "outbox:" + outbox);
        if (sequence != null) {
            sequence(group, sequence);
        }
    }

    private UUID conversation(String scope, String sender, long generation) {
        String alias = scope + ":" + sender;
        UUID previous = entities.get("conversation:" + alias);
        if (previous != null) {
            return previous;
        }
        UUID conversation = entity("conversation", alias);
        db.exec(
                "insert into conversation(id,group_id,sender_id,generation,updated_at) values(?,?,?,?,?)",
                conversation,
                group(scope),
                sender,
                generation,
                now);
        return conversation;
    }

    private void sequence(UUID group, long sequence) {
        db.exec("update group_config set event_seq=max(event_seq,?) where id=?", sequence, group);
    }

    private String scope(JsonNode event) {
        return value(event, "scope").asText();
    }

    private UUID entity(String kind, String alias) {
        return entities.computeIfAbsent(
                kind + ":" + alias, key -> id(specification.path("id").asText(), kind, alias));
    }

    private JsonNode value(JsonNode event, String key) {
        return event.has(key)
                ? event.get(key)
                : specification.has(key)
                        ? specification.get(key)
                        : DOCUMENT.path("defaults").get(key);
    }

    private static JsonNode readDocument() {
        return readDocument("context-cases.json");
    }

    private static JsonNode readDocument(String filename) {
        try (var fixture =
                MemoryV2FixtureLoader.class.getResourceAsStream("/memory-fixtures/" + filename)) {
            if (fixture == null) {
                throw new IllegalStateException("Missing memory fixture: " + filename);
            }
            return JSON.readTree(fixture);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load frozen context fixtures", failure);
        }
    }
}
