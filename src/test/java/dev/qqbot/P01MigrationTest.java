package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P01MigrationTest {
    @TempDir Path directory;

    @Test
    void insertAndReadBack() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("p01.db"))) {
            UUID id = UUID.randomUUID(), run = UUID.randomUUID();
            fixture.db.exec(
                    "insert into trace_span(id,run_id,kind,name,seq,status,start_us,duration_us,cpu_us,alloc_bytes,memory_bytes,estimated_input_tokens) values(?,?,'AGENT','agent.run',0,'OK',?,1500,300,4096,1000000,10)",
                    id,
                    run,
                    1794441600000000L);
            var row = fixture.db.one("select * from trace_span where run_id=?", run);
            assertEquals(id, row.get("id"));
            assertEquals(run, row.get("run_id"));
            assertNull(row.get("group_id"));
            assertNull(row.get("input_tokens"));
            assertEquals(1794441600000000L, ((Number) row.get("start_us")).longValue());
            assertEquals(10L, row.get("estimated_input_tokens"));
            assertEquals("{}", row.get("attributes"));
        }
    }
}
