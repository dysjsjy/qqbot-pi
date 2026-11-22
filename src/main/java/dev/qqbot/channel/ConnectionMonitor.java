package dev.qqbot.channel;

import dev.qqbot.persistence.Db;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConnectionMonitor {
    private final Db db;

    public ConnectionMonitor(Db db) {
        this.db = db;
    }

    public void heartbeat(String channel, String accountId, boolean online) {
        db.tx(
                () -> {
                    for (var row :
                            db.list(
                                    "select id,connection_state from group_config where channel=? and account_id=? and enabled",
                                    channel,
                                    accountId)) {
                        if (!online && !"OFFLINE".equals(row.get("connection_state"))) {
                            db.exec(
                                    "insert into collection_gap(id,group_id,starts_at,reason) values(?,?,cast(unixepoch('subsec')*1000 as integer),'GATEWAY_OFFLINE')",
                                    UUID.randomUUID(),
                                    row.get("id"));
                        }
                        if (online) {
                            db.exec(
                                    "update collection_gap set ends_at=cast(unixepoch('subsec')*1000 as integer) where group_id=? and ends_at is null and reason in ('GATEWAY_OFFLINE','HEARTBEAT_TIMEOUT')",
                                    row.get("id"));
                        }
                        db.exec(
                                "update group_config set connection_state=?,last_heartbeat_at=cast(unixepoch('subsec')*1000 as integer) where id=?",
                                online ? "ONLINE" : "OFFLINE",
                                row.get("id"));
                    }
                    return null;
                });
    }

    @Scheduled(fixedDelay = 30_000)
    public void stale() {
        db.tx(
                () -> {
                    for (var row :
                            db.list(
                                    "select id,last_heartbeat_at from group_config where enabled and connection_state='ONLINE' and last_heartbeat_at<(cast(unixepoch('subsec')*1000 as integer) - 90000)")) {
                        db.exec(
                                "insert into collection_gap(id,group_id,starts_at,reason) values(?,?,?,'HEARTBEAT_TIMEOUT')",
                                UUID.randomUUID(),
                                row.get("id"),
                                row.get("last_heartbeat_at"));
                        db.exec(
                                "update group_config set connection_state='UNKNOWN' where id=?",
                                row.get("id"));
                    }
                    return null;
                });
    }
}
