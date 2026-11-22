/**
 * [INPUT]: 依赖数据源、Db 与部署配置。
 * [OUTPUT]: 提供单实例启动锁、重启恢复与索引身份校验。
 * [POS]: 服务生命周期边界；文件锁阻止重复 SQLite 实例。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.persistence;

import dev.qqbot.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class Lifecycle {
    private final DataSource source;
    private final Db db;
    private final BotProperties props;
    private FileChannel localLockChannel;
    private FileLock localLock;

    public Lifecycle(DataSource source, Db db, BotProperties props) {
        this.source = source;
        this.db = db;
        this.props = props;
    }

    @PostConstruct
    void start() throws Exception {
        if (props.pi().provider().isBlank() || props.pi().model().isBlank()) {
            throw new IllegalStateException("PI_PROVIDER and PI_MODEL are required");
        }
        if (props.ragEnabled()
                && (props.embedding().url().isBlank()
                        || props.embedding().model().isBlank()
                        || props.embedding().dimensions() < 1)) {
            throw new IllegalStateException(
                    "Embedding configuration required when RAG_ENABLED=true");
        }
        if (props.qq().enabled()) {
            var official = props.qq().official();
            if (!java.util.Set.of("websocket", "webhook").contains(official.transport())) {
                throw new IllegalStateException(
                        "QQ_OFFICIAL_TRANSPORT must be websocket or webhook");
            }
            if (official.appId().isBlank()
                    || official.appSecret().isBlank()
                    || official.apiBaseUrl().isBlank()
                    || official.tokenUrl().isBlank()) {
                throw new IllegalStateException(
                        "Official QQ requires QQ_APP_ID and QQ_APP_SECRET and API/token URLs");
            }
        }
        var bridge = props.weixin();
        if (bridge.enabled()
                && (bridge.token().isBlank()
                        || bridge.baseUrl().isBlank()
                        || bridge.accountId().isBlank())) {
            throw new IllegalStateException("Enabled bridge requires URL, token and account ID");
        }
        try {
            acquireLock();
            db.exec(
                    "update agent_run set state='INTERRUPTED',error_code='SERVICE_RESTART',finished_at=cast(unixepoch('subsec')*1000 as integer) where state='RUNNING'");
            db.exec(
                    "update daily_run set state='INTERRUPTED',error_code='SERVICE_RESTART',finished_at=cast(unixepoch('subsec')*1000 as integer) where state='RUNNING'");
            db.exec(
                    "update memory_document set state='FAILED',error_code='SERVICE_RESTART',finished_at=cast(unixepoch('subsec')*1000 as integer) where state='RUNNING' and kind='SUMMARY' and strategy_version=2");
            db.exec(
                    "update memory_document set state='QUEUED',attempt=attempt+1,error_code='SERVICE_RESTART' where state='RUNNING' and not(kind='SUMMARY' and strategy_version=2)");
            db.exec(
                    "update outbox set state='UNKNOWN',error_code='SERVICE_RESTART' where state='SENDING'");
            for (var group :
                    db.list(
                            "select id,coalesce(last_received_at,collection_since,created_at) as starts_at from group_config where enabled")) {
                db.exec(
                        "insert into collection_gap(id,group_id,starts_at,ends_at,reason) values(?,?,?,cast(unixepoch('subsec')*1000 as integer),'SERVICE_RESTART_UNVERIFIED')",
                        UUID.randomUUID(),
                        group.get("id"),
                        group.get("starts_at"));
            }
            if (props.ragEnabled()) {
                String embeddingIdentity =
                        props.embedding().model() + ":" + props.embedding().dimensions();
                var old = db.one("select value from app_metadata where name='embedding_identity'");
                if (old != null && !old.get("value").equals(embeddingIdentity)) {
                    throw new IllegalStateException(
                            "Embedding model changed; explicit reindex required");
                }
                db.exec(
                        "insert into app_metadata(name,value) values('embedding_identity',?) on conflict(name) do nothing",
                        embeddingIdentity);
            }
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void acquireLock() throws Exception {
        String filename;
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("pragma database_list")) {
            filename = null;
            while (rows.next()) {
                if ("main".equals(rows.getString("name"))) {
                    filename = rows.getString("file");
                }
            }
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalStateException("SQLite mode requires a local database file");
        }
        Path database = Path.of(filename).toRealPath();
        localLockChannel =
                FileChannel.open(
                        database.resolveSibling(database.getFileName() + ".bot.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        localLock = localLockChannel.tryLock();
        if (localLock == null) {
            throw new IllegalStateException("Another bot instance owns this database");
        }
    }

    @PreDestroy
    void close() throws Exception {
        try {
            if (localLock != null) {
                localLock.release();
                localLock = null;
            }
        } finally {
            if (localLockChannel != null) {
                localLockChannel.close();
                localLockChannel = null;
            }
        }
    }
}
