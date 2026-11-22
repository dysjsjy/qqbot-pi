package dev.qqbot.admin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.qqbot.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Bounded operational logs. Never stores exception bodies, prompts or event payloads. */
@Component
public class RuntimeLogs extends AppenderBase<ILoggingEvent> {
    public record Entry(long id, Instant time, String level, String source, String message) {}

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<String> secrets;
    private long sequence;

    public RuntimeLogs(BotProperties props) {
        secrets = new ArrayList<>();
        for (String value :
                Arrays.asList(
                        props.adminPasswordHash(),
                        props.pi().apiKey(),
                        props.embedding().apiKey(),
                        props.qq().official().appSecret(),
                        props.weixin().token(),
                        props.weixin().webhookSecret())) {
            if (value != null && !value.isBlank()) {
                secrets.add(value);
            }
        }
        secrets.sort(Comparator.comparingInt(String::length).reversed());
    }

    @PostConstruct
    public void attach() {
        setName("admin-runtime-logs");
        start();
        ((Logger) LoggerFactory.getLogger("dev.qqbot")).addAppender(this);
        LoggerFactory.getLogger(RuntimeLogs.class).info("管理台运行日志已启用，保留最近 1000 条，重启后清空");
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        for (String secret : secrets) {
            message = message.replace(secret, "[REDACTED]");
        }
        message =
                message.replaceAll("(?i)(QQBot|Bearer)\\s+[^\\s,;]+", "$1 [REDACTED]")
                        .replaceAll(
                                "(?i)((?:api[_-]?key|app[_-]?secret|access[_-]?token|password|authorization)\\s*[:=]\\s*)[^\\s,;]+",
                                "$1[REDACTED]");
        message = message.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        if (message.length() > 1600) {
            message = message.substring(0, 1600);
        }
        entries.addFirst(
                new Entry(
                        ++sequence,
                        Instant.ofEpochMilli(event.getTimeStamp()),
                        event.getLevel().toString(),
                        event.getLoggerName(),
                        message));
        while (entries.size() > 1000) {
            entries.removeLast();
        }
    }

    public synchronized Map<String, Object> read(
            long after, String level, String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        var rows =
                entries.stream()
                        .filter(e -> e.id() > after)
                        .filter(e -> level.isBlank() || level.equalsIgnoreCase(e.level()))
                        .filter(
                                e ->
                                        (e.message() + " " + e.source())
                                                .toLowerCase(Locale.ROOT)
                                                .contains(needle))
                        .limit(Math.max(1, Math.min(limit, 200)))
                        .toList();
        return Map.of("items", rows, "latestId", sequence, "capacity", 1000);
    }

    @PreDestroy
    public void detach() {
        ((Logger) LoggerFactory.getLogger("dev.qqbot")).detachAppender(this);
        stop();
    }
}
