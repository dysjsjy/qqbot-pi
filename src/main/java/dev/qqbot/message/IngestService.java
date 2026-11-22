/**
 * [INPUT]: 依赖范围登记、消息日志、回复策略、命令和模型准入。
 * [OUTPUT]: 提供去重采集与已采集消息的分流回执。
 * [POS]: 采集与回复分流共用入站事务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.text.Texts;
import dev.qqbot.trace.Trace;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestService {
    private static final String QQ = "QQ";
    private static final Set<String> CHANNELS = Set.of(QQ, "WEIXIN");
    private static final String NON_TEXT = "NON_TEXT";

    public record Receipt(
            UUID groupId, UUID messageId, boolean duplicate, boolean accepted, boolean command) {}

    private final ScopeRegistry scopes;
    private final MessageJournal messages;
    private final ReplyPolicyService policies;
    private final ConversationService conversations;
    private final CommandHandler commands;
    private final RunAdmission admission;

    public IngestService(
            ScopeRegistry scopes,
            MessageJournal messages,
            ReplyPolicyService policies,
            ConversationService conversations,
            CommandHandler commands,
            RunAdmission admission) {
        this.scopes = scopes;
        this.messages = messages;
        this.policies = policies;
        this.conversations = conversations;
        this.commands = commands;
        this.admission = admission;
    }

    /** 采集已认证事件并按范围分流；不支持的平台或不匹配的私聊目标会被拒绝。 */
    @Transactional
    public Receipt ingest(GroupIngestRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Trace.begin(admission.traces(), Trace.INGRESS, "ingress", null, null);
        String status = Trace.OK;
        try {
            return collectEvent(request);
        } catch (RuntimeException failure) {
            status = Trace.ERROR;
            throw failure;
        } finally {
            Trace.end(status, null);
        }
    }

    private Receipt collectEvent(GroupIngestRequest request) {
        String channel = request.channel().toUpperCase(Locale.ROOT);
        Trace.attr("channel", channel);
        validateEvent(channel, request);
        boolean isNonText = NON_TEXT.equals(request.kind());
        boolean isIgnored = isIgnoredEvent(request, isNonText);
        if (request.privateChat() && isIgnored) {
            return new Receipt(null, null, false, false, false);
        }
        // SIDE EFFECT: 范围发现、采集和分流使用同一个入站事务。
        Group group = resolveScope(channel, request);
        if (!group.enabled() || isIgnored || !messages.accepts(request.occurredAt())) {
            return new Receipt(group.id(), null, false, false, false);
        }
        return appendMessage(group, request, isNonText);
    }

    private static void validateEvent(String channel, GroupIngestRequest request) {
        if (!CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Unsupported channel: channel=" + channel);
        }
        if (request.privateChat()
                && (!QQ.equals(channel) || !request.groupId().equals(request.senderId()))) {
            throw new IllegalArgumentException("Invalid private chat target: channel=" + channel);
        }
    }

    private static boolean isIgnoredEvent(GroupIngestRequest request, boolean isNonText) {
        return request.isSelf() || (request.text().isBlank() && !isNonText);
    }

    private Group resolveScope(String channel, GroupIngestRequest request) {
        try (var span = Trace.span("ingress.resolve")) {
            return scopes.resolve(channel, request);
        }
    }

    private Receipt appendMessage(Group group, GroupIngestRequest request, boolean isNonText) {
        MessageJournal.Entry message = writeMessage(group, request);
        if (message.duplicate()) {
            return new Receipt(group.id(), message.id(), true, false, false);
        }
        if (isNonText) {
            messages.ignoreIndex(message.id());
            return new Receipt(group.id(), message.id(), false, true, false);
        }
        return routeMessage(MessageRoute.classify(group, message.id(), request));
    }

    private MessageJournal.Entry writeMessage(Group group, GroupIngestRequest request) {
        try (var span = Trace.span("ingress.append")) {
            return messages.append(group, request);
        }
    }

    private Receipt routeMessage(MessageRoute route) {
        // SIDE EFFECT: 即使群关闭回复，命令也不能进入记忆索引。
        if (route.isCommand()) {
            messages.command(route.messageId());
        }
        if (!route.group().replyEnabled()) {
            return route.receipt(true, false);
        }
        if (CommandHandler.administrative(route.command())) {
            try (var span = Trace.span("ingress.command")) {
                boolean isAccepted =
                        commands.administer(
                                route.group(), route.messageId(), route.command(), route.request());
                return route.receipt(isAccepted, true);
            }
        }
        if (route.isOrdinaryGroupText() && !policies.shouldReply(route.group().id())) {
            return route.receipt(true, false);
        }
        return routeConversationMessage(route);
    }

    private Receipt routeConversationMessage(MessageRoute route) {
        // SIDE EFFECT: 会话、命令确认和生成任务由入站事务共同提交。
        var conversation = conversations.ensure(route.group().id(), route.request().senderId());
        try (var span = Trace.span("ingress.command")) {
            var commandResult = commands.dispatchConversationCommand(route, conversation);
            if (commandResult.isPresent()) {
                return route.receipt(commandResult.orElseThrow(), true);
            }
        }
        try (var span = Trace.span("ingress.enqueue")) {
            admission.enqueue(route, conversation);
        }
        return route.receipt(true, route.isCommand());
    }

    record MessageRoute(Group group, UUID messageId, GroupIngestRequest request, String command) {
        static final String MEMORY_COMMAND = "/memory";

        static MessageRoute classify(Group group, UUID messageId, GroupIngestRequest request) {
            return new MessageRoute(group, messageId, request, Texts.command(request.text()));
        }

        boolean isCommand() {
            return !command.isEmpty();
        }

        boolean isOrdinaryGroupText() {
            return !isCommand() && !request.mentionsBot() && !group.privateChat();
        }

        boolean isHistoryQuery() {
            return MEMORY_COMMAND.equals(command);
        }

        String prompt() {
            String text = request.text().strip();
            return isHistoryQuery() ? text.substring(MEMORY_COMMAND.length()).strip() : text;
        }

        Receipt receipt(boolean isAccepted, boolean isCommand) {
            return new Receipt(group.id(), messageId, false, isAccepted, isCommand);
        }
    }
}
