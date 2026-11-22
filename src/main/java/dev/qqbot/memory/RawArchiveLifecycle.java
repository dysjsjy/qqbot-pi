package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("lifecycle")
public class RawArchiveLifecycle {
    private final Db db;
    private final RawSegmentStore store;

    public RawArchiveLifecycle(Db db, RawSegmentStore store) {
        this.db = db;
        this.store = store;
    }

    @PostConstruct
    public void start() {
        Object synchronous = db.jdbc.queryForObject("pragma synchronous", Integer.class);
        if (synchronous == null || ((Number) synchronous).intValue() < 2) {
            throw new RawArchiveException("RAW_SQLITE_DURABILITY_REQUIRED");
        }
        store.acquire();
        for (var row : db.list("select * from raw_archive_segment where state<>'BROKEN'")) {
            RawSegment segment = RawSegment.from(row);
            try {
                store.locked(
                        segment.partition().groupId(),
                        () -> {
                            if ("ACTIVE".equals(segment.state())) {
                                store.recover(segment);
                            } else {
                                store.check(segment);
                            }
                            return null;
                        });
            } catch (RawArchiveException failure) {
                db.tx(
                        () -> {
                            db.exec(
                                    "update raw_archive_segment set state='BROKEN' where id=?",
                                    segment.id());
                            db.exec(
                                    "update raw_archive_state set status='FAILED',error_code='RAW_ARCHIVE_CORRUPT' where group_id=? and epoch=?",
                                    segment.partition().groupId(),
                                    segment.partition().epoch());
                            return null;
                        });
            }
        }
    }
}
