/**
 * [INPUT]: 依赖真实提及、群配置、回复策略、日报与成员画像。
 * [OUTPUT]: 提供公开命令、群管理员命令和本人画像控制。
 * [POS]: 入站事务内先于模型准入执行，使用 Outbox 被动确认。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.memory.MemberProfileService;
import dev.qqbot.message.IngestService.MessageRoute;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommandHandler {
    private static final Duration FRESHNESS = Duration.ofMinutes(5);
    private static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(30);
    private static final String ADMIN_HELP =
            "管理命令（先 @ 当前机器人）：\n/bot status\n/bot reply show\n/bot reply mode mention|probability|energy\n/bot reply probability 0.15（0–1）\n/bot reply energy 2 12（增量和消耗均为 1–1000 的整数）\n/summary yesterday";
    private final ConversationService conversations;
    private final ReplyQueue replies;
    private final DailySummaryService summaries;
    private final Db db;
    private final ReplyPolicyService policies;
    private final Clock clock;
    private final MemberProfileService profiles;

    public CommandHandler(
            ConversationService conversations,
            ReplyQueue replies,
            DailySummaryService summaries,
            Db db) {
        this(conversations, replies, summaries, db, new ReplyPolicyService(db), Clock.systemUTC());
    }

    public CommandHandler(
            ConversationService conversations,
            ReplyQueue replies,
            DailySummaryService summaries,
            Db db,
            ReplyPolicyService policies,
            Clock clock) {
        this(conversations, replies, summaries, db, policies, clock, null);
    }

    @Autowired
    public CommandHandler(
            ConversationService conversations,
            ReplyQueue replies,
            DailySummaryService summaries,
            Db db,
            ReplyPolicyService policies,
            Clock clock,
            MemberProfileService profiles) {
        this.conversations = conversations;
        this.replies = replies;
        this.summaries = summaries;
        this.db = db;
        this.policies = policies;
        this.clock = clock;
        this.profiles = profiles;
    }

    static boolean administrative(String command) {
        return command.equals("/bot") || command.equals("/summary") || command.equals("/profile");
    }

    static String canonical(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    boolean administer(Group group, UUID messageId, String command, GroupIngestRequest request) {
        if (group.privateChat()) {
            if (command.equals("/profile")) {
                replies.enqueue(group, messageId, "cmd:" + messageId, "私聊不支持成员画像。");
                return true;
            }
            if (!command.equals("/summary")) {
                return false;
            }
            replies.enqueue(group, messageId, "cmd:" + messageId, "私聊不支持群日报。发送 /help 查看私聊命令。");
            return true;
        }
        if (!request.mentionsBot()) {
            return false;
        }
        Instant now = clock.instant(), expires = request.occurredAt().plus(FRESHNESS);
        if (!expires.isAfter(now) || request.occurredAt().isAfter(now.plus(FUTURE_TOLERANCE))) {
            return false;
        }
        if (db.exec(
                        "insert into command_receipt(group_id,message_key,expires_at) values(?,?,?) on conflict do nothing",
                        group.id(),
                        request.messageId(),
                        expires)
                == 0) {
            return false;
        }
        if (command.equals("/profile")) {
            String[] words = canonical(request.text()).split(" ");
            String response =
                    profiles == null
                            ? "本群未启用实验性成员画像。"
                            : profiles.self(
                                    group.id(),
                                    request.senderId(),
                                    words.length == 2 ? words[1] : "");
            replies.enqueue(group, messageId, "cmd:" + messageId, response);
            return true;
        }
        boolean authorized = group.isAdmin(request.senderId());
        String response =
                authorized ? adminResponse(group, command, request) : "仅本群当前机器人的管理员可执行此命令。";
        replies.enqueue(group, messageId, "cmd:" + messageId, response);
        return authorized;
    }

    private String adminResponse(Group group, String command, GroupIngestRequest request) {
        String input = canonical(request.text());
        if (command.equals("/summary")) {
            if (!input.equals("/summary yesterday")) {
                return "用法：/summary yesterday（仅支持昨日摘要）。";
            }
            UUID run = summaries.trigger(group.id(), "COMMAND");
            var job = db.one("select state from daily_run where id=?", run);
            return "昨日分析任务："
                    + run
                    + "（"
                    + job.get("state")
                    + "）。完整报告可在管理端查看；失败任务可再次触发，发送失败仅补发未成功分段。";
        }
        if (input.equals("/bot help")) {
            return ADMIN_HELP;
        }
        if (input.equals("/bot status")) {
            var schedule =
                    db.one("select enabled from activity_schedule where group_id=?", group.id());
            return "群采集：已启用；机器人回复：已启用；回复模式："
                    + modeLabel(policies.get(group.id()).get("mode"))
                    + "；每日活跃榜："
                    + (schedule != null && Boolean.TRUE.equals(schedule.get("enabled"))
                            ? "已启用"
                            : "未启用")
                    + "。";
        }
        if (input.equals("/bot reply show")) {
            return describe(policies.get(group.id()));
        }
        ReplyPolicyService.Patch patch;
        try {
            patch = parseReply(input.split(" "));
        } catch (IllegalArgumentException failure) {
            return "指令或参数无效。\n" + ADMIN_HELP;
        }
        return "回复设置：\n"
                + describe(policies.save(group.id(), patch, null, "COMMAND", request.senderId()));
    }

    private static ReplyPolicyService.Patch parseReply(String[] args) {
        if (args.length < 4 || !args[1].equals("reply")) {
            throw new IllegalArgumentException("Invalid command");
        }
        return switch (args[2]) {
            case "mode" -> {
                if (args.length != 4
                        || !Set.of("mention", "probability", "energy").contains(args[3])) {
                    throw new IllegalArgumentException("Invalid mode");
                }
                yield new ReplyPolicyService.Patch(
                        args[3].toUpperCase(Locale.ROOT), null, null, null);
            }
            case "probability" -> {
                if (args.length != 4 || !args[3].matches("[01](?:\\.[0-9]+)?")) {
                    throw new IllegalArgumentException("Invalid probability");
                }
                var probability = new java.math.BigDecimal(args[3]);
                if (probability.compareTo(java.math.BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("Invalid probability");
                }
                yield new ReplyPolicyService.Patch(
                        "PROBABILITY", probability.doubleValue(), null, null);
            }
            case "energy" -> {
                if (args.length != 5
                        || !args[3].matches("[0-9]{1,4}")
                        || !args[4].matches("[0-9]{1,4}")) {
                    throw new IllegalArgumentException("Invalid energy");
                }
                yield new ReplyPolicyService.Patch(
                        "ENERGY", null, Integer.valueOf(args[3]), Integer.valueOf(args[4]));
            }
            default -> throw new IllegalArgumentException("Invalid command");
        };
    }

    private static String describe(Map<String, Object> settings) {
        return "模式："
                + modeLabel(settings.get("mode"))
                + "；回复概率："
                + settings.get("probability")
                + "；能量增量 / 消耗："
                + settings.get("energy_gain")
                + " / "
                + settings.get("energy_cost")
                + "；当前能量："
                + settings.get("energy")
                + "。";
    }

    private static String modeLabel(Object mode) {
        return switch (mode.toString()) {
            case "ENERGY" -> "能量回复";
            case "PROBABILITY" -> "概率回复";
            default -> "仅 @ 回复";
        };
    }

    Optional<Boolean> dispatchConversationCommand(
            MessageRoute route, ConversationService.Conversation conversation) {
        Group group = route.group();
        UUID messageId = route.messageId();
        String response;
        switch (route.command()) {
            case "/help" ->
                    response =
                            group.privateChat()
                                    ? "私聊命令：/help /new /cancel /memory 问题。直接发送问题即可回复；历史和记忆按你的私聊独立保存。"
                                    : "命令：/help /new /cancel /memory 问题。@我 会读取本群 Markdown 记忆和最近 20 条群消息。本群管理员可 @ 我发送 /bot help 查看管理命令。";
            case "/new" -> {
                conversations.reset(conversation.id());
                response = group.privateChat() ? "当前私聊已重置；你的私聊记忆仍保留。" : "当前会话已重置；群记忆仍保留。";
            }
            case "/cancel" ->
                    response =
                            conversations.cancel(conversation.id()) ? "已取消你的生成任务。" : "当前没有运行中的任务。";
            case MessageRoute.MEMORY_COMMAND -> {
                if (!route.prompt().isEmpty()) {
                    return Optional.empty();
                }
                response = "用法：/memory 你的问题";
            }
            case "" -> {
                return Optional.empty();
            }
            default -> response = "未知命令。发送 /help 查看可用命令。";
        }
        replies.enqueue(group, messageId, "cmd:" + messageId, response);
        return Optional.of(true);
    }
}
