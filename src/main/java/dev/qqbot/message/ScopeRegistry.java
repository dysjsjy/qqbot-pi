package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ScopeRegistry {
    private final Db db;

    public ScopeRegistry(Db db) {
        this.db = db;
    }

    public static String privateId(String sender) {
        try {
            return "dm_"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(sender.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Group resolve(String channel, GroupIngestRequest request) {
        String external = request.privateChat() ? privateId(request.senderId()) : request.groupId();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,collection_since,connection_state,chat_type,target_id) values(?,?,?,?,?,?,?,'ONLINE',?,?) on conflict(channel,account_id,external_id) do nothing",
                UUID.randomUUID(),
                channel,
                request.accountId(),
                external,
                request.privateChat()
                        ? "私聊 · "
                                + request.senderName()
                                        .substring(0, Math.min(190, request.senderName().length()))
                        : external,
                request.privateChat(),
                request.privateChat() ? Instant.now() : null,
                request.privateChat() ? "PRIVATE" : "GROUP",
                request.privateChat() ? request.senderId() : null);
        var row =
                db.one(
                        "select * from group_config where channel=? and account_id=? and external_id=?",
                        channel,
                        request.accountId(),
                        external);
        if (!Objects.equals(row.get("chat_type"), request.privateChat() ? "PRIVATE" : "GROUP")
                || (request.privateChat()
                        && !Objects.equals(row.get("target_id"), request.senderId()))) {
            throw new IllegalArgumentException("Chat scope conflict");
        }
        return Group.from(row);
    }
}
