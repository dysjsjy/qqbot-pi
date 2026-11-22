package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import dev.qqbot.text.Texts;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReplyQueue {
    private final Db db;

    public ReplyQueue(Db db) {
        this.db = db;
    }

    public void enqueue(Group group, UUID origin, String key, String text) {
        int part = 0;
        for (String body : Texts.delivery(text)) {
            db.exec(
                    "insert into outbox(id,group_id,result_key,epoch,part_index,body,reply_to_message_id,state) values(?,?,?,?,?,?,?,'PENDING') on conflict(result_key,part_index) do nothing",
                    UUID.randomUUID(),
                    group.id(),
                    key,
                    group.epoch(),
                    part++,
                    body,
                    origin);
        }
    }
}
