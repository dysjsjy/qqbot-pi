package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.channel.OutboxWorker;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.trace.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P03IngressTraceTest {
    @TempDir Path directory;

    @Test
    void ingressAgentAndDeliveryShareRunWithoutChangingBusiness() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("chain.db"))) {
            UUID group = fixture.group("chain");
            var policies = new ReplyPolicyService(fixture.db);
            var replies = new ReplyQueue(fixture.db);
            var conversations = new ConversationService(fixture.db);
            var admission = new RunAdmission(fixture.db, replies, policies);
            List<SpanRecord> spans = new CopyOnWriteArrayList<>();
            admission.setTraces(spans::addAll);
            var gateway = new BotProperties.Gateway(false, "", "", "test", "");
            var props =
                    new BotProperties(
                            "admin",
                            "",
                            directory,
                            false,
                            new BotProperties.Pi(
                                    "node", "unused", "test", "test", "KEY", "", 5, ""),
                            null,
                            new BotProperties.Qq(
                                    gateway.enabled(),
                                    new BotProperties.OfficialQq(
                                            gateway.accountId(),
                                            "",
                                            "https://api.bot.qq.com",
                                            "https://api.bot.qq.com/app/getAppAccessToken",
                                            false)),
                            gateway,
                            new BotProperties.Rag(false));
            var ingest =
                    new IngestService(
                            new ScopeRegistry(fixture.db),
                            new MessageJournal(fixture.db, props),
                            policies,
                            conversations,
                            new CommandHandler(conversations, replies, null, fixture.db),
                            admission);
            var receipt =
                    ingest.ingest(
                            new GroupIngestRequest(
                                    "QQ",
                                    "test",
                                    "chain",
                                    "first",
                                    "A",
                                    "甲",
                                    Instant.now(),
                                    "关键链路问题",
                                    true,
                                    false));
            assertTrue(receipt.accepted());
            UUID run = (UUID) fixture.db.one("select id from agent_run").get("id");
            TextGenerator generator = mock(TextGenerator.class);
            when(generator.generate(any(), any()))
                    .thenReturn(new TextGenerator.Result("正常答复", null));
            var memory = mock(MemoryService.class);
            when(memory.assemble(any()))
                    .thenReturn(
                            new ContextBundle(
                                    "system",
                                    "question",
                                    List.of(),
                                    new ContextStats(
                                            14, Map.of(), List.of(), List.of(), List.of(), ""),
                                    CitationMode.NONE));
            when(memory.inputLimit()).thenReturn(8000);
            var worker = new AgentWorker(fixture.db, generator, memory);
            worker.setTraces(spans::addAll);
            try {
                worker.tick();
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (spans.stream().noneMatch(span -> span.name().equals("agent.run"))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(
                        "SUCCEEDED",
                        fixture.db.one("select state from agent_run where id=?", run).get("state"));
                var delivery =
                        new OutboxWorker(
                                fixture.db,
                                message ->
                                        new MessageTransport.Delivery(
                                                MessageTransport.State.ACCEPTED, "sent", null),
                                null);
                delivery.setTraces(spans::addAll);
                delivery.tick();
                assertEquals(
                        "SENT",
                        fixture.db
                                .one("select state from outbox where agent_run_id=?", run)
                                .get("state"));
                assertTrue(
                        spans.stream()
                                .map(SpanRecord::name)
                                .toList()
                                .containsAll(
                                        List.of(
                                                "ingress",
                                                "ingress.enqueue",
                                                "agent.run",
                                                "agent.stats",
                                                "agent.complete",
                                                "delivery.send",
                                                "delivery.transport")));
                for (var span : spans) {
                    assertEquals(run, span.runId());
                    assertEquals(group, span.groupId());
                }
                assertEquals(
                        "SENT",
                        spans.stream()
                                .filter(span -> span.name().equals("delivery.send"))
                                .findFirst()
                                .orElseThrow()
                                .attributes()
                                .get("state"));
                spans.clear();
                assertTrue(
                        ingest.ingest(
                                        new GroupIngestRequest(
                                                "QQ",
                                                "test",
                                                "chain",
                                                "first",
                                                "A",
                                                "甲",
                                                Instant.now(),
                                                "重复",
                                                true,
                                                false))
                                .duplicate());
                assertTrue(spans.isEmpty());
                assertFalse(Trace.active());
            } finally {
                worker.close();
            }
        }
    }
}
