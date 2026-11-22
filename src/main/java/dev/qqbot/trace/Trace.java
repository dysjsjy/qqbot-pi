package dev.qqbot.trace;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.*;

public final class Trace {
    public static final String INGRESS = "INGRESS",
            AGENT = "AGENT",
            DELIVERY = "DELIVERY",
            BACKGROUND = "BACKGROUND";
    public static final String OK = "OK", ERROR = "ERROR", CANCELLED = "CANCELLED";
    static final int MAX_SPANS = 200, MAX_ATTRIBUTES = 24;
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Span NOOP = new Span();

    private Trace() {}

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** sink 为 null 或当前线程已有活动链时什么都不做。 */
    public static void begin(TraceSink sink, String kind, String name, UUID runId, UUID groupId) {
        if (sink == null || CURRENT.get() != null) {
            return;
        }
        Context context = new Context(sink, kind, runId, groupId);
        CURRENT.set(context);
        context.root = new Span(context, null, name);
    }

    public static void identify(UUID runId, UUID groupId) {
        Context context = CURRENT.get();
        if (context == null) {
            return;
        }
        if (runId != null) {
            context.runId = runId;
        }
        if (groupId != null) {
            context.groupId = groupId;
        }
    }

    public static Span span(String name) {
        Context context = CURRENT.get();
        if (context == null) {
            return NOOP;
        }
        if (context.spans.size() >= MAX_SPANS) {
            context.dropped++;
            return NOOP;
        }
        return new Span(context, context.stack.peek(), name);
    }

    /** 写到根 span 的 attributes。 */
    public static void attr(String key, Object value) {
        Context context = CURRENT.get();
        if (context != null) {
            context.root.attr(key, value);
        }
    }

    /** 关闭所有未关闭的 span，把记录交给 sink。runId 仍为 null 时丢弃。永不抛异常。 */
    public static void end(String status, String errorCode) {
        Context context = CURRENT.get();
        if (context == null) {
            return;
        }
        CURRENT.remove();
        try {
            while (context.stack.size() > 1) {
                context.stack.peek().close();
            }
            Span root = context.root;
            root.status = status == null ? OK : status;
            root.errorCode = errorCode;
            if (context.dropped > 0) {
                root.attr("droppedSpans", context.dropped);
            }
            root.close();
            if (context.runId == null) {
                return;
            }
            long[] sums = new long[6];
            boolean[] seen = new boolean[6];
            for (Span span : context.spans) {
                if (span == root) {
                    continue;
                }
                for (int index = 0; index < 6; index++) {
                    if (span.tokens[index] != null) {
                        sums[index] += span.tokens[index];
                        seen[index] = true;
                    }
                }
            }
            for (int index = 0; index < 6; index++) {
                if (seen[index]) {
                    root.tokens[index] = sums[index];
                }
            }
            List<SpanRecord> records = new ArrayList<>(context.spans.size());
            for (Span span : context.spans) {
                records.add(span.record());
            }
            context.sink.accept(records);
        } catch (RuntimeException ignored) {
        }
    }

    private static long cpu() {
        return THREADS.isCurrentThreadCpuTimeSupported()
                ? Math.max(0, THREADS.getCurrentThreadCpuTime())
                : 0;
    }

    private static long allocated() {
        return THREADS instanceof com.sun.management.ThreadMXBean bean
                ? Math.max(0, bean.getThreadAllocatedBytes(Thread.currentThread().getId()))
                : 0;
    }

    private static final class Context {
        final TraceSink sink;
        final String kind;
        UUID runId, groupId;
        final long epochUs, nanos = System.nanoTime();
        final List<Span> spans = new ArrayList<>();
        final Deque<Span> stack = new ArrayDeque<>();
        Span root;
        int dropped;

        Context(TraceSink sink, String kind, UUID runId, UUID groupId) {
            this.sink = sink;
            this.kind = kind;
            this.runId = runId;
            this.groupId = groupId;
            Instant now = Instant.now();
            epochUs = now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
        }
    }

    public static final class Span implements AutoCloseable {
        private final Context context;
        private final Span parent;
        private final String name;
        private final UUID id;
        private final int seq;
        private final long startNanos, startCpu, startAllocated;
        private long durationUs, cpuUs, allocBytes, memoryBytes;
        private String status = OK, errorCode;
        private final Long[] tokens = new Long[6];
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean closed;

        private Span() {
            context = null;
            parent = null;
            name = null;
            id = null;
            seq = 0;
            startNanos = 0;
            startCpu = 0;
            startAllocated = 0;
            closed = true;
        }

        private Span(Context context, Span parent, String name) {
            this.context = context;
            this.parent = parent;
            this.name = name;
            id = UUID.randomUUID();
            seq = context.spans.size();
            context.spans.add(this);
            context.stack.push(this);
            startCpu = cpu();
            startAllocated = allocated();
            startNanos = System.nanoTime();
        }

        /** value 只允许 String / Number / Boolean / Map<String,Number>；字符串超过 120 字符截断。 */
        public Span attr(String key, Object value) {
            if (context == null
                    || key == null
                    || value == null
                    || attributes.size() >= MAX_ATTRIBUTES) {
                return this;
            }
            attributes.put(
                    key,
                    value instanceof String text && text.length() > 120
                            ? text.substring(0, 120)
                            : value);
            return this;
        }

        public Span fail(String code) {
            if (context != null) {
                status = ERROR;
                errorCode = code;
            }
            return this;
        }

        public Span cancelled(String code) {
            if (context != null) {
                status = CANCELLED;
                errorCode = code;
            }
            return this;
        }

        /** 只有名为 model.call 的 span 可以调用。 */
        public Span tokens(
                Long estimated,
                Long input,
                Long output,
                Long cacheRead,
                Long cacheWrite,
                Long reasoning) {
            if (context == null) {
                return this;
            }
            tokens[0] = estimated;
            tokens[1] = input;
            tokens[2] = output;
            tokens[3] = cacheRead;
            tokens[4] = cacheWrite;
            tokens[5] = reasoning;
            return this;
        }

        /** 幂等。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            durationUs = (System.nanoTime() - startNanos) / 1000;
            cpuUs = Math.max(0, cpu() - startCpu) / 1000;
            allocBytes = Math.max(0, allocated() - startAllocated);
            Runtime runtime = Runtime.getRuntime();
            memoryBytes = runtime.totalMemory() - runtime.freeMemory();
            context.stack.remove(this);
        }

        private SpanRecord record() {
            return new SpanRecord(
                    id,
                    context.runId,
                    context.groupId,
                    parent == null ? null : parent.id.toString(),
                    context.kind,
                    name,
                    seq,
                    status,
                    errorCode,
                    context.epochUs + (startNanos - context.nanos) / 1000,
                    durationUs,
                    cpuUs,
                    allocBytes,
                    memoryBytes,
                    tokens[0],
                    tokens[1],
                    tokens[2],
                    tokens[3],
                    tokens[4],
                    tokens[5],
                    new LinkedHashMap<>(attributes));
        }
    }
}
