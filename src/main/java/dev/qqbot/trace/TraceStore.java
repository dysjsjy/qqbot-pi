package dev.qqbot.trace;

import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TraceStore implements TraceSink {
    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    private static final String INSERT =
            "insert into trace_span(id,run_id,group_id,parent_span,kind,name,seq,status,error_code,start_us,duration_us,cpu_us,alloc_bytes,memory_bytes,estimated_input_tokens,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,attributes) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private final Db db;
    private final boolean enabled;
    private final int retentionDays;
    private final Clock clock;
    private final LinkedBlockingQueue<SpanRecord> queue = new LinkedBlockingQueue<>(5000);
    private final AtomicLong dropped = new AtomicLong();

    public TraceStore(
            Db db,
            @Value("${bot.trace.enabled:true}") boolean enabled,
            @Value("${bot.trace.retention-days:7}") int retentionDays,
            Clock clock) {
        this.db = db;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    @Override
    public void accept(List<SpanRecord> spans) {
        if (!enabled || spans == null) {
            return;
        }
        for (SpanRecord span : spans) {
            if (!queue.offer(span)) {
                dropped.incrementAndGet();
            }
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void flush() {
        List<SpanRecord> batch = new ArrayList<>();
        while (queue.drainTo(batch, 500) > 0) {
            List<SpanRecord> current = batch;
            try {
                db.tx(
                        () -> {
                            for (SpanRecord span : current) {
                                db.exec(
                                        INSERT,
                                        span.id(),
                                        span.runId(),
                                        span.groupId(),
                                        span.parentSpan(),
                                        span.kind(),
                                        span.name(),
                                        span.seq(),
                                        span.status(),
                                        span.errorCode(),
                                        span.startUs(),
                                        span.durationUs(),
                                        span.cpuUs(),
                                        span.allocBytes(),
                                        span.memoryBytes(),
                                        span.estimatedInputTokens(),
                                        span.inputTokens(),
                                        span.outputTokens(),
                                        span.cacheReadTokens(),
                                        span.cacheWriteTokens(),
                                        span.reasoningTokens(),
                                        db.json(span.attributes()));
                            }
                            return null;
                        });
            } catch (RuntimeException failure) {
                log.warn(
                        "TRACE_FLUSH_FAILED spans={} error={}",
                        current.size(),
                        failure.getClass().getSimpleName());
            }
            batch = new ArrayList<>();
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void expire() {
        try {
            db.exec(
                    "delete from trace_span where start_us<?",
                    (clock.millis() - Math.max(1, retentionDays) * 86400000L) * 1000);
        } catch (RuntimeException failure) {
            log.warn("TRACE_EXPIRE_FAILED error={}", failure.getClass().getSimpleName());
        }
    }

    public int pending() {
        return queue.size();
    }

    public long dropped() {
        return dropped.get();
    }
}
