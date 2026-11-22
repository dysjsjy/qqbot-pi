package dev.qqbot.trace;

import java.util.List;

/** 实现必须：不抛异常、不阻塞、不访问数据库。 */
public interface TraceSink {
    void accept(List<SpanRecord> spans);
}
