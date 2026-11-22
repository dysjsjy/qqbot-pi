package dev.qqbot;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Deterministic business fixture, never a substitute for reviewing a real model's report. */
final class DailySummaryTestModel {
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode input(String prompt) throws Exception {
        int start = prompt.indexOf("\nINPUT_JSON\n");
        if (start < 0) {
            throw new IllegalStateException("TEST_EXPECTED_WORKFLOW_PROTOCOL");
        }
        return JSON.readTree(prompt.substring(start + 12));
    }

    static String reply(String prompt) throws Exception {
        var in = input(prompt);
        String action = in.path("action").asText();
        var result = JSON.createObjectNode();
        if (action.equals("READ_BATCH")) {
            var facts = result.putArray("facts");
            var dispositions = result.putArray("dispositions");
            com.fasterxml.jackson.databind.node.ObjectNode background = null;
            int backgroundIndex = -1;
            for (var unit : in.path("units")) {
                String text = unit.path("text").asText();
                var disposition = dispositions.addObject().put("unit", unit.path("id").asText());
                if (text.contains("闲聊") || text.contains("🙂")) {
                    disposition.put("reason", "CHAT");
                    continue;
                }
                if (!text.contains("待办")) {
                    if (background == null) {
                        backgroundIndex = facts.size();
                        background =
                                facts.addObject()
                                        .put("kind", "TOPIC")
                                        .put("text", "已讨论发布安排")
                                        .put("topic", "发布")
                                        .put("owner", "")
                                        .put("due", "");
                        background.putArray("units");
                    }
                    ((com.fasterxml.jackson.databind.node.ArrayNode) background.get("units"))
                            .add(unit.path("id").asText());
                    disposition.putArray("facts").add(backgroundIndex);
                    continue;
                }
                int index = facts.size();
                var fact =
                        facts.addObject()
                                .put("kind", text.contains("待办") ? "ACTION" : "TOPIC")
                                .put("text", text.contains("待办") ? "小王周四前补齐压测结果" : "已讨论发布安排")
                                .put("topic", "发布")
                                .put("owner", text.contains("待办") ? "小王" : "")
                                .put("due", text.contains("待办") ? "周四" : "");
                fact.putArray("units").add(unit.path("id").asText());
                disposition.putArray("facts").add(index);
                fact.put("value", "REQUIRED").put("reason", "DEADLINE");
            }
        } else if (action.equals("ORGANIZE_TOPIC") || action.equals("EDIT_MATERIAL")) {
            var facts = result.putArray("facts");
            var groups = new LinkedHashMap<String, List<JsonNode>>();
            for (var fact : in.path("facts")) {
                groups.computeIfAbsent(
                                fact.path("topic").asText() + "/" + fact.path("text").asText(),
                                k -> new ArrayList<>())
                        .add(fact);
            }
            for (var group : groups.values()) {
                var out = facts.addObject();
                copy(group.get(0), out);
                out.put("relation", group.size() > 1 ? "MERGE" : "KEEP");
                out.put(
                        "value",
                        group.stream().anyMatch(f -> f.path("value").asText().equals("REQUIRED"))
                                ? "REQUIRED"
                                : "OPTIONAL");
                out.put(
                                "reason",
                                out.path("value").asText().equals("REQUIRED")
                                        ? "DEADLINE"
                                        : "USEFUL")
                        .put("review", "")
                        .put("detail", "FULL");
                var ids = out.putArray("inputs");
                group.forEach(f -> ids.add(f.path("id").asText()));
            }
        } else if (action.equals("SELECT_FACTS")) {
            var selected = result.putArray("selected");
            result.putArray("omitted");
            for (var fact : in.path("facts")) {
                selected.addObject()
                        .put("id", fact.path("id").asText())
                        .put(
                                "detail",
                                in.path("strategy").asText().equals("BRIEF") ? "BRIEF" : "FULL");
            }
        } else if (action.equals("WRITE_REPORT")) {
            var paragraphs = result.putArray("paragraphs");
            for (var fact : in.path("facts")) {
                var p = paragraphs.addObject().put("text", fact.path("text").asText());
                p.putArray("facts").add(fact.path("id").asText());
                var sources = p.putArray("sources");
                fact.path("sources").forEach(source -> sources.add(source.asText()));
            }
        } else {
            throw new IllegalStateException("TEST_UNKNOWN_ACTION");
        }
        return JSON.writeValueAsString(result);
    }

    private static void copy(JsonNode from, com.fasterxml.jackson.databind.node.ObjectNode to) {
        for (String key : List.of("kind", "text", "topic", "owner", "due")) {
            to.put(key, from.path(key).asText());
        }
    }
}
