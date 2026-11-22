package dev.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.persistence.Db;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class MemoryV2TestDatabase implements AutoCloseable {
    final DriverManagerDataSource source;
    final Db db;

    MemoryV2TestDatabase(Path file, String target) {
        this(
                new DriverManagerDataSource(
                        "jdbc:sqlite:"
                                + file
                                + "?foreign_keys=on&busy_timeout=10000&transaction_mode=IMMEDIATE"),
                "classpath:db/sqlite",
                target);
    }

    private MemoryV2TestDatabase(DriverManagerDataSource source, String location, String target) {
        this.source = source;
        var migration = Flyway.configure().dataSource(source).locations(location);
        if (target != null) {
            migration.target(target);
        }
        migration.load().migrate();
        db =
                new Db(
                        new JdbcTemplate(source),
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new ObjectMapper().findAndRegisterModules());
    }

    MemoryV2TestDatabase(Path file) {
        this(file, null);
    }

    @Override
    public void close() {}

    UUID group(String externalId) {
        UUID groupId = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,epoch) values(?,'QQ','test',?, ?,true,2)",
                groupId,
                externalId,
                externalId);
        return groupId;
    }

    UUID message(UUID groupId, String key, String sender, String body, long seq, Instant time) {
        UUID messageId = UUID.randomUUID();
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,?,?,?,?,?,?,2,'TEXT',?)",
                messageId,
                groupId,
                key,
                sender,
                sender,
                body,
                time,
                time,
                seq);
        db.exec("update group_config set event_seq=max(event_seq,?) where id=?", seq, groupId);
        return messageId;
    }
}
