/**
 * [INPUT]: 接收源 SQLite 库与专用导出目录。
 * [OUTPUT]: 创建 V9 副本并复制旧版识别的列。
 * [POS]: RAW 离线导出的 SQL 边界；不读取归档文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.persistence.Db;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class LegacySqliteCopy {
    static final int SCHEMA_VERSION = 9;
    private static final String TABLES =
            """
            select name from sqlite_master where type='table'
            and name<>'flyway_schema_history' and name not like 'sqlite_%'
            """;

    private LegacySqliteCopy() {}

    static Db copyVersionNine(Db source, Path output) {
        // SIDE EFFECT: 在新目录里创建 V9 库；不改源库。
        Db target = createSchema(output.resolve("qqbot.db"));
        List<TableCopy> tables = copyPlan(target.jdbc);
        target.tx(() -> copyTables(source.jdbc, target.jdbc, tables));
        return target;
    }

    private static Db createSchema(Path file) {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:sqlite:" + file + "?foreign_keys=on&transaction_mode=IMMEDIATE");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/sqlite")
                .target(Integer.toString(SCHEMA_VERSION))
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new Db(jdbc, transactions, new ObjectMapper());
    }

    private static List<TableCopy> copyPlan(JdbcTemplate target) {
        return target.queryForList(TABLES, String.class).stream()
                .map(table -> planTable(target, table))
                .toList();
    }

    private static TableCopy planTable(JdbcTemplate target, String table) {
        List<String> columns =
                target.query(
                        "pragma table_info(\"" + table + "\")",
                        (row, rowNumber) -> row.getString("name"));
        String fields = String.join(",", columns);
        String parameters = String.join(",", Collections.nCopies(columns.size(), "?"));
        String projection =
                "memory_policy".equals(table)
                        ? fields.replace("context_mode", "'BUDGETED' as context_mode")
                        : fields;
        return new TableCopy(
                "select " + projection + " from " + table,
                "insert into " + table + "(" + fields + ") values(" + parameters + ")",
                columns.size());
    }

    private static Void copyTables(
            JdbcTemplate source, JdbcTemplate target, List<TableCopy> tables) {
        // SIDE EFFECT: V9 表有交叉引用，统一在事务提交时检查外键。
        target.execute("pragma defer_foreign_keys=on");
        for (TableCopy table : tables) {
            RowCallbackHandler copyRow =
                    row -> target.update(table.insert(), values(row, table.columnCount()));
            source.query(table.select(), copyRow);
        }
        return null;
    }

    private static Object[] values(ResultSet row, int columnCount) throws SQLException {
        var values = new Object[columnCount];
        for (int index = 0; index < columnCount; index++) {
            values[index] = row.getObject(index + 1);
        }
        return values;
    }

    private record TableCopy(String select, String insert, int columnCount) {}
}
