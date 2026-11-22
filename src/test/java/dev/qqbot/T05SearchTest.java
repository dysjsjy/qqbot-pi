package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.memory.*;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class T05SearchTest {
    private static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;
    private MemoryV2TestDatabase fixture;
    private UUID groupId;
    private BotProperties properties;
    private MarkdownMemory files;
    private MemorySearchIndex index;
    private EmbeddingClient embeddings;
    private VectorMemory vectors;

    @BeforeEach
    void prepare() {
        fixture = database();
        groupId = fixture.group("search");
        properties = properties(true);
        files = new MarkdownMemory(fixture.db, properties);
        index = new MemorySearchIndex(fixture.db, files, CLOCK);
        embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyList()))
                .thenAnswer(
                        invocation ->
                                ((List<?>) invocation.getArgument(0))
                                        .stream().map(text -> new float[] {1, 0, 0}).toList());
        vectors = new VectorMemory(fixture.db, embeddings, properties, CLOCK);
    }

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("search.db"));
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void S01NormalizesExactTermsAndSkipsEmptyQuery() {
        assertEquals(List.of("发布", "布计", "计划", "api", "v2"), SearchText.queryTerms("发布计划 API_V2"));
        assertEquals(SearchText.queryTerms("发布计划 API_V2"), SearchText.queryTerms("发布计划 ａｐｉ＿ｖ２"));
        assertEquals(List.of("api", "v2"), SearchText.queryTerms("API API v2 v2"));
        assertEquals(
                32,
                SearchText.queryTerms(
                                String.join(
                                        " ",
                                        java.util.stream.IntStream.range(0, 40)
                                                .mapToObj(number -> "word" + number)
                                                .toList()))
                        .size());
        assertEquals(128, SearchText.queryTerms("a".repeat(200)).get(0).length());
        assertTrue(search().search(scope(), "！！...", QueryMode.HISTORY, 4).isEmpty());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S02ChunksPreserveParagraphSeparatorsAndWholeEmoji() {
        String original = "第一段\r\n \r\n" + "🙂".repeat(501) + "\n\n末段\n";
        var chunks = SearchText.chunks(original);
        assertEquals(original, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(part -> part.codePointCount(0, part.length()) <= 500));
        for (String part : chunks) {
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
        assertEquals(original, String.join("", SearchText.paragraphs(original)));
    }

    @Test
    void S03FiltersBeforeLimitInHundredThousandMessageIndex() {
        UUID other = fixture.group("other");
        var expected = new ArrayList<UUID>();
        fixture.db.tx(
                () -> {
                    for (int batch = 0; batch < 100000; batch += 1000) {
                        var messages = new ArrayList<Object[]>();
                        var chunks = new ArrayList<Object[]>();
                        var sources = new ArrayList<Object[]>();
                        var terms = new ArrayList<Object[]>();
                        for (int number = batch; number < batch + 1000; number++) {
                            UUID target = groupId;
                            long epoch = 2, sequence;
                            Instant time;
                            boolean matching = true;
                            if (number < 10000) {
                                sequence = number + 1;
                                time =
                                        NOW.minusSeconds(3600)
                                                .plusSeconds(number < 5 ? number + 1 : 0);
                                matching = number < 5;
                            } else if (number < 40000) {
                                target = other;
                                sequence = number - 9999;
                                time = NOW.minusSeconds(3060);
                            } else if (number < 60000) {
                                epoch = 1;
                                sequence = number - 29999;
                                time = NOW.minusSeconds(3060);
                            } else if (number < 70000) {
                                sequence = number - 29999;
                                time = NOW.minus(Duration.ofDays(30)).minusMillis(1);
                            } else if (number < 90000) {
                                sequence = number - 29999;
                                time = NOW.minusSeconds(2400);
                            } else {
                                sequence = number - 9998;
                                time = NOW.minusSeconds(7200);
                            }
                            UUID message = stable("scale-message:" + number),
                                    chunk = stable("scale-chunk:" + number);
                            if (number < 5) {
                                expected.add(message);
                            }
                            String body =
                                    matching
                                            ? "发布计划 "
                                                    + (number < 5 ? "visible-" : "forbidden-")
                                                    + number
                                            : "ordinary discussion";
                            messages.add(
                                    new Object[] {
                                        bound(message),
                                        bound(target),
                                        "scale-" + number,
                                        body,
                                        bound(time),
                                        bound(time),
                                        epoch,
                                        sequence
                                    });
                            chunks.add(
                                    new Object[] {
                                        bound(chunk),
                                        bound(target),
                                        epoch,
                                        "message:" + message,
                                        body,
                                        bound(time),
                                        bound(NOW)
                                    });
                            sources.add(new Object[] {bound(chunk), bound(message)});
                            if (matching) {
                                for (String term : List.of("发布", "布计", "计划")) {
                                    terms.add(new Object[] {bound(chunk), bound(target), term});
                                }
                            }
                        }
                        fixture.db.jdbc.batchUpdate(
                                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,?,'sender','sender',?,?,?,?,'TEXT',?)",
                                messages);
                        fixture.db.jdbc.batchUpdate(
                                "insert into memory_search_chunk(id,group_id,epoch,source_kind,source_key,part_index,body,content_hash,source_time,indexed_at) values(?,?,?,'RAW',?,0,?,'scale',?,?)",
                                chunks);
                        fixture.db.jdbc.batchUpdate(
                                "insert into memory_search_source(chunk_id,message_id) values(?,?)",
                                sources);
                        fixture.db.jdbc.batchUpdate(
                                "insert into memory_search_term(chunk_id,group_id,term) values(?,?,?)",
                                terms);
                    }
                    fixture.db.exec("update group_config set event_seq=90001 where id=?", groupId);
                    return null;
                });
        assertEquals(100000, count("group_message"));
        var upper = new MemoryScope(groupId, 2, null, 80001, NOW.minusSeconds(3000), NOW);
        Collections.reverse(expected);
        var result = index.lexical(upper, "发布计划", QueryMode.HISTORY, 200);
        assertEquals(
                expected,
                result.evidence().stream().map(item -> item.messageIds().get(0)).toList());
        assertEquals(5, result.bodiesRead());
        assertTrue(result.evidence().stream().noneMatch(item -> item.body().contains("forbidden")));
        var enhanced = new EvidenceSearch(index, vectors, properties(false));
        assertEquals(
                expected.subList(0, 4),
                enhanced.search(upper, "发布计划", QueryMode.HISTORY, 4).stream()
                        .map(item -> item.messageIds().get(0))
                        .toList());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S03IndexedAndFallbackBodiesShareTwoHundredBudget() {
        for (int number = 1; number <= 190; number++) {
            message("indexed-" + number, "发布计划 " + number, number, NOW.minusSeconds(60));
        }
        drain();
        for (int number = 191; number <= 210; number++) {
            message("pending-" + number, "发布计划 " + number, number, NOW.minusSeconds(30));
        }
        var reads = new AtomicInteger();
        Db observed = observed(reads);
        var observedIndex = new MemorySearchIndex(observed, files, CLOCK);
        var result = observedIndex.lexical(scope(), "发布计划", QueryMode.HISTORY, 200);
        assertEquals(200, result.bodiesRead());
        assertEquals(200, reads.get());
        drain();
        reads.set(0);
        assertEquals(
                200, observedIndex.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).bodiesRead());
        assertEquals(200, reads.get());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S03VectorReservationCapsAllBodyReadsAtTwoHundred() {
        message("many-parts", ("发布计划" + "x".repeat(496)).repeat(190), 1, NOW.minusSeconds(60));
        for (int number = 0; number < 10; number++) {
            UUID message =
                    message(
                            "semantic-" + number,
                            "semantic item " + number,
                            number + 2,
                            NOW.minusSeconds(30));
            vector(message, groupId, 2, 0, "semantic item " + number);
        }
        drain();
        var reads = new AtomicInteger();
        Db observed = observed(reads);
        var observedIndex = new MemorySearchIndex(observed, files, CLOCK);
        var observedVectors = new VectorMemory(observed, embeddings, properties, CLOCK);
        var result =
                new EvidenceSearch(observedIndex, observedVectors, properties)
                        .search(scope(), "发布计划", QueryMode.HISTORY, 4);
        assertEquals(4, result.size());
        assertEquals(200, reads.get());
        verify(embeddings, times(1)).embed(List.of("发布计划"));
        assertTrue(
                result.stream()
                        .allMatch(
                                item ->
                                        item.body().codePointCount(0, item.body().length())
                                                <= 500));
    }

    @Test
    void S04LexicalAndVectorApplyEveryScopeBoundary() {
        UUID valid = message("valid", "发布计划 合法", 1, NOW.minusSeconds(3600));
        UUID boundary = message("boundary", "发布计划 边界", 5, NOW.minus(Duration.ofDays(30)));
        UUID other = fixture.group("other");
        UUID foreign = fixture.message(other, "foreign", "A", "发布计划 跨群", 1, NOW.minusSeconds(60));
        UUID old = message("old", "发布计划 旧epoch", 2, NOW.minusSeconds(60));
        fixture.db.exec("update group_message set epoch=1 where id=?", old);
        UUID futureTime = message("future-time", "发布计划 未来时间", 3, NOW.plusSeconds(60));
        UUID expired =
                message("expired", "发布计划 已到期", 4, NOW.minus(Duration.ofDays(30)).minusMillis(1));
        UUID futureSeq = message("future-seq", "发布计划 未来序号", 10, NOW.minusSeconds(7200));
        for (UUID message : List.of(valid, boundary, old, futureTime, expired, futureSeq)) {
            vector(message, groupId, message.equals(old) ? 1 : 2, 0, "发布计划");
        }
        vector(foreign, other, 2, 0, "发布计划");
        drain();
        index.indexPending(other, 100);
        var visible = new MemoryScope(groupId, 2, null, 10, NOW, NOW);
        Set<UUID> expected = Set.of(valid, boundary);
        assertEquals(
                expected,
                new HashSet<>(
                        index.lexical(visible, "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                                .map(item -> item.messageIds().get(0))
                                .toList()));
        var semantic = vectors.search(visible, "发布计划", 10);
        assertEquals(
                expected,
                new HashSet<>(
                        semantic.evidence().stream()
                                .map(item -> item.messageIds().get(0))
                                .toList()));
        assertEquals(2, semantic.bodiesRead());
        fixture.db.exec("update group_config set enabled=false where id=?", groupId);
        clearInvocations(embeddings);
        assertTrue(search().search(visible, "发布计划", QueryMode.HISTORY, 4).isEmpty());
        assertTrue(vectors.search(visible, "发布计划", 10).evidence().isEmpty());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S05RejectsWholeSummaryWhenAnySourceIsMissingOrOutOfScope() {
        var sources = new ArrayList<UUID>();
        for (int number = 1; number <= 100; number++) {
            sources.add(
                    message(
                            "summary-source-" + number,
                            "普通原文" + number,
                            number,
                            NOW.minusSeconds(60)));
        }
        UUID second = sources.get(99), document = summary(sources, "发布计划 摘要", "READY");
        String independent = "发布计划 独立合法资料";
        files.write(groupId, independent);
        drain();
        String manual = "manual:" + MemberLabels.hash(independent) + ":0";
        var visible = new MemoryScope(groupId, 2, null, 101, NOW, NOW);
        assertEquals(
                Set.of("summary:" + document + ":0", manual),
                new HashSet<>(
                        index.lexical(visible, "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                                .map(Evidence::sourceKey)
                                .toList()));
        assertEquals(
                100,
                ((Number)
                                fixture.db
                                        .one(
                                                "select source_count from memory_document where id=?",
                                                document)
                                        .get("source_count"))
                        .intValue());
        fixture.db.exec("update group_message set event_seq=102 where id=?", second);
        assertEquals(
                List.of(manual),
                index.lexical(visible, "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                        .map(Evidence::sourceKey)
                        .toList());
        fixture.db.exec("update group_message set event_seq=100 where id=?", second);
        fixture.db.exec(
                "delete from memory_document_source where document_id=? and message_id=?",
                document,
                second);
        assertEquals(
                List.of(manual),
                index.lexical(visible, "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                        .map(Evidence::sourceKey)
                        .toList());
        fixture.db.exec(
                "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                document,
                second);
        fixture.db.exec("delete from group_message where id=?", second);
        assertEquals(
                List.of(manual),
                index.lexical(visible, "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                        .map(Evidence::sourceKey)
                        .toList());
    }

    @Test
    void S06ChatRequiresTwoTermsAndHalfCoverageWithoutEmbedding() {
        message("weak", "api", 1, NOW.minusSeconds(60));
        drain();
        assertTrue(search().search(scope(), "api v2 release plan", QueryMode.CHAT, 4).isEmpty());
        UUID strong = message("strong", "api v2", 2, NOW.minusSeconds(30));
        drain();
        assertEquals(
                List.of(strong),
                search().search(scope(), "api v2 release plan", QueryMode.CHAT, 4).stream()
                        .map(item -> item.messageIds().get(0))
                        .toList());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S07OnlySparseHistoryEmbedsAndDisabledRagNeverEmbeds() {
        for (int number = 1; number <= 3; number++) {
            message("source-" + number, "发布计划 " + number, number, NOW.minusSeconds(60));
        }
        drain();
        assertEquals(3, search().search(scope(), "发布计划", QueryMode.HISTORY, 4).size());
        verify(embeddings, times(1)).embed(anyList());
        message("source-4", "发布计划 第四条", 4, NOW.minusSeconds(30));
        drain();
        clearInvocations(embeddings);
        assertEquals(4, search().search(scope(), "发布计划", QueryMode.HISTORY, 4).size());
        verifyNoInteractions(embeddings);
        when(embeddings.embed(anyList()))
                .thenThrow(new AssertionError("RAG_DISABLED_MUST_NOT_EMBED"));
        var disabled = properties(false);
        var local =
                new EvidenceSearch(
                        index, new VectorMemory(fixture.db, embeddings, disabled, CLOCK), disabled);
        assertEquals(4, local.search(scope(), "发布计划", QueryMode.HISTORY, 4).size());
        verifyNoInteractions(embeddings);
    }

    @Test
    void S08FixedRanksFuseByCanonicalKeyAndBreakTiesLexically() {
        var mockedIndex = mock(MemorySearchIndex.class);
        var mockedVectors = mock(VectorMemory.class);
        when(mockedIndex.active(any())).thenReturn(true);
        var first = evidence(1, SourceKind.RAW);
        var second = evidence(2, SourceKind.RAW);
        var third = evidence(3, SourceKind.VECTOR);
        when(mockedIndex.lexical(any(), anyString(), eq(QueryMode.HISTORY), eq(190)))
                .thenReturn(new MemorySearchIndex.Matches(List.of(first, second), 2));
        var semanticFirst =
                new Evidence(
                        first.sourceKey(),
                        SourceKind.VECTOR,
                        first.body(),
                        first.messageIds(),
                        NOW,
                        0.9);
        var overlap =
                new Evidence(
                        "summary:overlap:0",
                        SourceKind.SUMMARY,
                        "overlap",
                        List.of(first.messageIds().get(0), second.messageIds().get(0)),
                        NOW,
                        0.8);
        when(mockedVectors.search(any(MemoryScope.class), anyString(), eq(10)))
                .thenReturn(
                        new MemorySearchIndex.Matches(List.of(semanticFirst, third, overlap), 3));
        var result =
                new EvidenceSearch(mockedIndex, mockedVectors, properties)
                        .search(scope(), "发布计划", QueryMode.HISTORY, 4);
        assertEquals(
                List.of(first.sourceKey(), second.sourceKey(), third.sourceKey()),
                result.stream().map(Evidence::sourceKey).toList());
        assertEquals(2.0 / 61, result.get(0).score(), 1e-15);
        assertEquals(1.0 / 62, result.get(1).score(), 1e-15);
        assertEquals(1.0 / 62, result.get(2).score(), 1e-15);
    }

    @Test
    void S09ManualHashChangesAndLateReadySummaryAreIndexed() {
        files.write(groupId, "旧版配置 API");
        index.indexPending(groupId, 100);
        assertEquals(
                SourceKind.MANUAL,
                index.lexical(scope(), "api", QueryMode.HISTORY, 200).evidence().get(0).kind());
        files.write(groupId, "新版安排 DNS");
        assertTrue(index.lexical(scope(), "api", QueryMode.HISTORY, 200).evidence().isEmpty());
        index.indexPending(groupId, 100);
        assertTrue(index.lexical(scope(), "api", QueryMode.HISTORY, 200).evidence().isEmpty());
        assertEquals(
                "manual:" + MemberLabels.hash("新版安排 DNS") + ":0",
                index.lexical(scope(), "dns", QueryMode.HISTORY, 200)
                        .evidence()
                        .get(0)
                        .sourceKey());
        UUID source = message("source", "原始资料", 1, NOW.minusSeconds(60));
        UUID document = summary(List.of(source), "发布计划 新摘要", "QUEUED");
        drain();
        assertTrue(index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).evidence().isEmpty());
        fixture.db.exec("update memory_document set state='READY' where id=?", document);
        index.indexPending(groupId, 100);
        assertEquals(
                List.of("summary:" + document + ":0"),
                index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).evidence().stream()
                        .map(Evidence::sourceKey)
                        .toList());
        assertEquals(0, index.indexPending(groupId, 100));
    }

    @Test
    void S10RebuildIsIdempotentBoundedAndDoesNotCallExternalServices() {
        for (int number = 1; number <= 205; number++) {
            message("raw-" + number, "发布计划 " + number, number, NOW.minusSeconds(number));
        }
        assertEquals(200, index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).bodiesRead());
        drain();
        var before = index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).evidence();
        long terms = count("memory_search_term");
        assertEquals(205, count("memory_search_chunk"));
        assertEquals(0, index.indexPending(groupId, 100));
        assertEquals(terms, count("memory_search_term"));
        long sequence =
                ((Number)
                                fixture.db
                                        .one(
                                                "select event_seq from group_config where id=?",
                                                groupId)
                                        .get("event_seq"))
                        .longValue();
        index.rebuild(groupId);
        assertEquals(0, count("memory_search_chunk"));
        assertEquals(0, count("memory_search_state"));
        assertEquals(205, count("group_message"));
        assertEquals(200, index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).bodiesRead());
        drain();
        assertEquals(before, index.lexical(scope(), "发布计划", QueryMode.HISTORY, 200).evidence());
        assertEquals(terms, count("memory_search_term"));
        assertEquals(
                sequence,
                fixture.db
                        .one("select event_seq from group_config where id=?", groupId)
                        .get("event_seq"));
        verifyNoInteractions(embeddings);
    }

    @Test
    void S09S10ExactIndexTrajectoryPreservesEverySourceAndRebuildsFourBlocks() {
        UUID first = message("trajectory-first", "rawfirstprobe", 1, NOW.minusSeconds(120));
        UUID second = message("trajectory-second", "rawsecondprobe", 2, NOW.minusSeconds(60));
        files.write(groupId, "manualoldprobe");
        UUID document = summary(List.of(first, second), "summaryreadyprobe", "QUEUED");
        index.indexPending(groupId, 100);
        assertEquals(3, count("memory_search_chunk"));
        assertEquals(2, count("memory_search_source"));
        assertEquals(
                1,
                index.lexical(scope(), "rawfirstprobe", QueryMode.HISTORY, 200).evidence().size());
        assertEquals(
                1,
                index.lexical(scope(), "rawsecondprobe", QueryMode.HISTORY, 200).evidence().size());
        assertEquals(
                1,
                index.lexical(scope(), "manualoldprobe", QueryMode.HISTORY, 200).evidence().size());
        assertTrue(
                index.lexical(scope(), "summaryreadyprobe", QueryMode.HISTORY, 200)
                        .evidence()
                        .isEmpty());
        var originalKeys =
                fixture.db.list(
                        "select source_kind,source_key,part_index from memory_search_chunk where group_id=? order by source_kind,source_key,part_index",
                        groupId);
        long originalTerms = count("memory_search_term");
        index.indexPending(groupId, 100);
        assertEquals(3, count("memory_search_chunk"));
        assertEquals(originalTerms, count("memory_search_term"));
        assertEquals(2, count("memory_search_source"));
        assertEquals(
                originalKeys,
                fixture.db.list(
                        "select source_kind,source_key,part_index from memory_search_chunk where group_id=? order by source_kind,source_key,part_index",
                        groupId));
        assertEquals("manualoldprobe", files.read(groupId));
        files.write(groupId, "manualnewprobe");
        index.indexPending(groupId, 100);
        assertEquals(3, count("memory_search_chunk"));
        assertTrue(
                index.lexical(scope(), "manualoldprobe", QueryMode.HISTORY, 200)
                        .evidence()
                        .isEmpty());
        assertEquals(
                List.of("manual:" + MemberLabels.hash("manualnewprobe") + ":0"),
                index.lexical(scope(), "manualnewprobe", QueryMode.HISTORY, 200).evidence().stream()
                        .map(Evidence::sourceKey)
                        .toList());
        assertTrue(
                fixture.db
                        .list(
                                "select * from memory_search_term where group_id=? and term='manualoldprobe'",
                                groupId)
                        .isEmpty());
        fixture.db.exec("update memory_document set state='READY' where id=?", document);
        index.indexPending(groupId, 100);
        assertEquals(4, count("memory_search_chunk"));
        assertEquals(4, count("memory_search_source"));
        var summarySource =
                index.lexical(scope(), "summaryreadyprobe", QueryMode.HISTORY, 200).evidence();
        assertEquals(1, summarySource.size());
        assertEquals(Set.of(first, second), new HashSet<>(summarySource.get(0).messageIds()));
        var before =
                fixture.db.list(
                        "select source_kind,source_key,part_index,body from memory_search_chunk where group_id=? order by source_kind,source_key,part_index",
                        groupId);
        long terms = count("memory_search_term");
        index.clearIndex(groupId);
        assertEquals(0, count("memory_search_chunk"));
        assertEquals(0, count("memory_search_source"));
        assertEquals(0, count("memory_search_term"));
        var worker = new MemorySearchWorker(fixture.db, index);
        worker.tick();
        assertEquals(4, count("memory_search_chunk"));
        assertEquals(
                before,
                fixture.db.list(
                        "select source_kind,source_key,part_index,body from memory_search_chunk where group_id=? order by source_kind,source_key,part_index",
                        groupId));
        assertEquals(
                summarySource,
                index.lexical(scope(), "summaryreadyprobe", QueryMode.HISTORY, 200).evidence());
        worker.tick();
        assertEquals(4, count("memory_search_chunk"));
        assertEquals(terms, count("memory_search_term"));
        assertEquals(4, count("memory_search_source"));
        assertEquals("manualnewprobe", files.read(groupId));
        assertEquals("summaryreadyprobe", files.readDocument(groupId, document, "SUMMARY"));
        assertEquals(
                List.of("rawfirstprobe", "rawsecondprobe"),
                fixture
                        .db
                        .list(
                                "select body from group_message where group_id=? order by event_seq",
                                groupId)
                        .stream()
                        .map(row -> (String) row.get("body"))
                        .toList());
        verifyNoInteractions(embeddings);
    }

    @Test
    void missingSummaryFileDoesNotBlockRawOrBecomeSuccessfulEmptyIndex() {
        UUID source = message("source", "发布计划 原文", 1, NOW.minusSeconds(60));
        UUID document = summary(List.of(source), "", "READY");
        assertEquals(1, index.indexPending(groupId, 100));
        assertEquals(
                0,
                ((Number)
                                fixture.db
                                        .one(
                                                "select count(*) n from memory_search_chunk where document_id=?",
                                                document)
                                        .get("n"))
                        .intValue());
        files.writeDocument(groupId, document, "SUMMARY", "独立摘要 dns");
        assertEquals(1, index.indexPending(groupId, 100));
        assertEquals(
                SourceKind.SUMMARY,
                index.lexical(scope(), "dns", QueryMode.HISTORY, 200).evidence().get(0).kind());
    }

    @Test
    void vectorIndexUsesCanonicalChunksAndLegacyChunksNormalizeWithoutReembedding() {
        String original = "x".repeat(1600) + "发布计划" + "y".repeat(1000);
        UUID message = message("legacy", original, 1, NOW.minusSeconds(60));
        vector(message, groupId, 2, 1, original.substring(1200, 2400));
        var legacy = vectors.search(scope(), "发布计划", 10);
        assertEquals("message:" + message + ":3", legacy.evidence().get(0).sourceKey());
        assertEquals(SearchText.chunks(original).get(3), legacy.evidence().get(0).body());
        assertEquals(1, legacy.bodiesRead());
        verify(embeddings, times(1)).embed(List.of("发布计划"));
        fixture.db.exec("delete from memory_chunk");
        clearInvocations(embeddings);
        assertEquals(1, vectors.indexPending(100));
        var stored =
                fixture.db.list(
                        "select part_index,body from memory_chunk where message_id=? order by part_index",
                        message);
        assertEquals(
                SearchText.chunks(original),
                stored.stream().map(row -> (String) row.get("body")).toList());
        assertTrue(
                stored.stream()
                        .allMatch(
                                row ->
                                        ((String) row.get("body"))
                                                        .codePointCount(
                                                                0,
                                                                ((String) row.get("body")).length())
                                                <= 500));
    }

    @Test
    void optionalVectorFailureKeepsLexicalButLexicalFailurePropagates() {
        message("source", "发布计划 合法", 1, NOW.minusSeconds(60));
        drain();
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("UNAVAILABLE"));
        assertEquals(1, search().search(scope(), "发布计划", QueryMode.HISTORY, 4).size());
        var broken = mock(MemorySearchIndex.class);
        when(broken.active(any())).thenReturn(true);
        when(broken.lexical(any(), anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("DATABASE_UNAVAILABLE"));
        assertEquals(
                "DATABASE_UNAVAILABLE",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        new EvidenceSearch(broken, vectors, properties)
                                                .search(scope(), "发布计划", QueryMode.HISTORY, 4))
                        .getMessage());
    }

    @Test
    void legacySearchKeepsMemorySourceShapeAndDoesNotScanArchiveFiles() {
        files.write(groupId, "人工发布计划");
        UUID source = message("raw", "原文发布", 1, NOW.minusSeconds(60));
        summary(List.of(source), "概括发布计划", "READY");
        drain();
        var observedFiles = spy(files);
        var localIndex = new MemorySearchIndex(fixture.db, observedFiles, CLOCK);
        var results =
                new MarkdownSearch(fixture.db, localIndex, CLOCK).search(groupId, "发布计划", 10, null);
        assertTrue(results.stream().anyMatch(item -> item.id().equals("MEMORY.md")));
        assertTrue(results.stream().anyMatch(item -> item.name().startsWith("自动概括")));
        verify(observedFiles, never()).readDocument(any(), any(), anyString());
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .valueToTree(results.get(0));
        assertEquals(
                Set.of("id", "name", "time", "body", "score"),
                new HashSet<>(
                        java.util.stream.StreamSupport.stream(
                                        Spliterators.spliteratorUnknownSize(json.fieldNames(), 0),
                                        false)
                                .toList()));
    }

    private EvidenceSearch search() {
        return new EvidenceSearch(index, vectors, properties);
    }

    private MemoryScope scope() {
        return new MemoryScope(groupId, 2, null, 1000000, NOW, NOW);
    }

    private UUID message(String key, String body, long sequence, Instant time) {
        return fixture.message(groupId, key, "A", body, sequence, time);
    }

    private void drain() {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (index.indexPending(groupId, 100) == 0) {
                return;
            }
        }
        fail("index did not drain");
    }

    private long count(String table) {
        return ((Number) fixture.db.one("select count(*) n from " + table).get("n")).longValue();
    }

    private UUID summary(List<UUID> sources, String content, String state) {
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,2,'SUMMARY',?,?)",
                id,
                groupId,
                state,
                sources.size());
        for (UUID source : sources) {
            fixture.db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                    id,
                    source);
        }
        files.writeDocument(groupId, id, "SUMMARY", content);
        return id;
    }

    private void vector(UUID message, UUID group, long epoch, int part, String body) {
        fixture.db.exec(
                "insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions) values(?,?,?,?,?,?,?,'test',3)",
                UUID.randomUUID(),
                group,
                message,
                epoch,
                part,
                body,
                "[1,0,0]");
    }

    private Evidence evidence(int suffix, SourceKind kind) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
        return new Evidence("message:" + id + ":0", kind, "body-" + suffix, List.of(id), NOW, 1);
    }

    private static UUID stable(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Object bound(UUID id) {
        return id.toString();
    }

    private Object bound(Instant time) {
        return time.toEpochMilli();
    }

    private Db observed(AtomicInteger reads) {
        Db observed = spy(fixture.db);
        doAnswer(
                        invocation -> {
                            var result = (List<?>) invocation.callRealMethod();
                            for (Object value : result) {
                                if (value instanceof Map<?, ?> row && row.containsKey("body")) {
                                    reads.incrementAndGet();
                                }
                            }
                            return result;
                        })
                .when(observed)
                .list(anyString(), any(Object[].class));
        return observed;
    }

    private BotProperties properties(boolean rag) {
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        return new BotProperties(
                "admin",
                "",
                directory,
                false,
                new BotProperties.Pi("node", "unused", "local", "model", "KEY", "", 5, ""),
                new BotProperties.Embedding("http://127.0.0.1/unused", "", "test", 3, 0.5),
                new BotProperties.Qq(
                        gateway.enabled(),
                        new BotProperties.OfficialQq(
                                gateway.accountId(),
                                "",
                                "https://api.bot.qq.com",
                                "https://api.bot.qq.com/app/getAppAccessToken",
                                false)),
                gateway,
                new BotProperties.Rag(rag));
    }
}
