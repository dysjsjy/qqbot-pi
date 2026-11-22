package dev.qqbot;

import dev.qqbot.channel.*;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryService;

final class TestServices {
    private TestServices() {}

    static ChannelSender sender(
            BotProperties properties, com.fasterxml.jackson.databind.ObjectMapper json) {
        var http = new GatewayHttpClient(json);
        return new ChannelSender(
                java.util.List.of(
                        new OfficialQqClient(properties, json),
                        new WeixinBridgeClient(properties, http)));
    }

    static MemoryService memory(Db db, EmbeddingClient embeddings, BotProperties properties) {
        var files = new MarkdownMemory(db, properties);
        var index = new MemorySearchIndex(db, files, java.time.Clock.systemUTC());
        var vectors = new VectorMemory(db, embeddings, properties);
        return new MemoryService(
                new MarkdownSearch(db, files),
                new MemoryContext(db, files),
                vectors,
                properties,
                new EvidenceSearch(index, vectors, properties));
    }

    static IngestService ingest(Db db, DailySummaryService summaries, BotProperties properties) {
        var policies = new ReplyPolicyService(db);
        var conversations = new ConversationService(db);
        var replies = new ReplyQueue(db);
        return new IngestService(
                new ScopeRegistry(db),
                new MessageJournal(db, properties),
                policies,
                conversations,
                new CommandHandler(conversations, replies, summaries, db),
                new RunAdmission(db, replies, policies));
    }
}
