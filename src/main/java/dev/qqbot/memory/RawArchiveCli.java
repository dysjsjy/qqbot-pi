/**
 * [INPUT]: 依赖明确的 SQLite、归档目录和离线冷留存参数。
 * [OUTPUT]: 执行归档命令并输出无正文的 JSON 报告。
 * [POS]: 修改归档前获取应用实例锁；检查和校验只读。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.RawArchiveProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class RawArchiveCli {
    private RawArchiveCli() {}

    public static void main(String[] args) throws Exception {
        var options = new LinkedHashMap<String, String>();
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Expected --name value pairs");
        }
        for (int index = 0; index < args.length; index += 2) {
            if (options.put(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate argument");
            }
        }
        String command = required(options, "--command");
        if (!Set.of("inspect", "migrate", "verify", "finalize", "export-legacy")
                .contains(command)) {
            throw new IllegalArgumentException("Unsupported command");
        }
        Path database = Path.of(required(options, "--database")).toRealPath(),
                state = Path.of(required(options, "--state-dir")).toRealPath(),
                report = Path.of(required(options, "--report")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)
                || !Files.isDirectory(state)
                || report.equals(database)
                || report.startsWith(state.resolve("memory"))) {
            throw new IllegalArgumentException("Invalid explicit paths");
        }
        Clock clock =
                options.containsKey("--now")
                        ? Clock.fixed(Instant.parse(options.get("--now")), ZoneOffset.UTC)
                        : Clock.systemUTC();
        UUID group =
                options.containsKey("--group-id")
                        ? UUID.fromString(options.get("--group-id"))
                        : null;
        boolean readOnly = Set.of("inspect", "verify").contains(command);
        var json = new ObjectMapper().findAndRegisterModules();
        RawArchiveProperties properties = RawArchiveProperties.defaults();
        int archiveDays = Integer.parseInt(options.getOrDefault("--archive-retention-days", "0"));
        var retention = new RetentionProperties(7, archiveDays, 30);
        try (var lockChannel =
                        readOnly
                                ? null
                                : FileChannel.open(
                                        database.resolveSibling(
                                                database.getFileName() + ".bot.lock"),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE);
                var databaseLock = readOnly ? null : lockChannel.tryLock();
                var store = new RawSegmentStore(state, properties, json)) {
            if (!readOnly && databaseLock == null) {
                throw new RawArchiveException("RAW_DATABASE_LOCKED");
            }
            if (!readOnly) {
                store.acquire();
            }
            String url =
                    "jdbc:sqlite:"
                            + database
                            + (readOnly
                                    ? "?open_mode=1"
                                    : "?foreign_keys=on&transaction_mode=IMMEDIATE&busy_timeout=10000&synchronous=FULL");
            var source = new DriverManagerDataSource(url);
            if (!readOnly) {
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/sqlite")
                        .load()
                        .migrate();
            }
            var db =
                    new Db(
                            new JdbcTemplate(source),
                            new TransactionTemplate(new DataSourceTransactionManager(source)),
                            json);
            if (readOnly) {
                db.jdbc.execute("pragma query_only=on");
            }
            var archive = new RawArchiveService(db, store, properties, clock, retention);
            var migration = new RawArchiveMigration(db, store, archive, properties, clock);
            Object result;
            if (command.equals("inspect")) {
                result = migration.inspect(group);
            } else if (command.equals("verify")) {
                result = migration.verify(group);
            } else if (command.equals("export-legacy")) {
                result = migration.exportLegacy(Path.of(required(options, "--output-dir")));
            } else {
                var results = new ArrayList<Object>();
                List<Map<String, Object>> groups =
                        group == null
                                ? db.list("select id from group_config order by id")
                                : List.of(Map.of("id", group));
                for (var item : groups) {
                    results.add(
                            command.equals("migrate")
                                    ? migration.migrate((UUID) item.get("id"))
                                    : migration.finalizeMigration((UUID) item.get("id")));
                }
                if (command.equals("finalize")) {
                    var collector = new RawArchiveCollector(db, store, archive, clock);
                    for (int round = 0; round < 1000; round++) {
                        long pending =
                                RawArchiveService.number(
                                        db.one(
                                                "select count(*) n from raw_archive_gc where state in ('PENDING','RUNNING')"),
                                        "n");
                        if (pending == 0) {
                            break;
                        }
                        collector.runOnce();
                    }
                    if (db.one("select id from raw_archive_gc where state<>'DONE' limit 1")
                            != null) {
                        throw new RawArchiveException("RAW_GC_INCOMPLETE");
                    }
                }
                result = Map.of("ok", true, "partitions", results);
            }
            if (report.getParent() != null) {
                Files.createDirectories(report.getParent());
            }
            Files.writeString(
                    report,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
            System.out.println("RAW archive " + command + " completed; report=" + report);
        } catch (Exception failure) {
            if (report.getParent() != null) {
                Files.createDirectories(report.getParent());
            }
            Files.writeString(
                    report,
                    json.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(
                                            Map.of(
                                                    "ok",
                                                    false,
                                                    "error",
                                                    failure instanceof RawArchiveException
                                                            ? failure.getMessage()
                                                            : failure.getClass().getSimpleName()))
                            + "\n");
            throw failure;
        }
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }
}
