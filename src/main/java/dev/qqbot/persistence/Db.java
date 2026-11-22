/**
 * [INPUT]: 依赖 JdbcTemplate、事务模板与 JSON 映射器。
 * [OUTPUT]: 提供有类型的行查询、旧行字典、事务与结构化审计。
 * [POS]: 持久化模块的业务入口；集中转换 SQLite 绑定参数。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Db {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DatabaseValues values;

    public Db(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        boolean local =
                Boolean.TRUE.equals(
                        jdbc.execute(
                                (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                        connection ->
                                                connection
                                                        .getMetaData()
                                                        .getURL()
                                                        .startsWith("jdbc:sqlite:")));
        if (!local) {
            throw new IllegalStateException(
                    "The core requires a SQLite database; external storage requires a plugin");
        }
        this.values = new DatabaseValues(json);
    }

    public int exec(String statement, Object... args) {
        return jdbc.update(statement, values.bind(args));
    }

    public List<Map<String, Object>> list(String statement, Object... args) {
        var rows = jdbc.queryForList(statement, values.bind(args));
        for (var row : rows) {
            row.replaceAll(values::decode);
        }
        return rows;
    }

    public Map<String, Object> one(String statement, Object... args) {
        var rows = list(statement, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取查询返回的第一行；无结果时返回空值。映射器必须返回非空对象。 */
    public <T> Optional<T> find(String statement, RowMapper<T> mapper, Object... arguments) {
        return query(statement, mapper, arguments).stream().findFirst();
    }

    /** 按查询顺序映射多行；SQL、映射器和映射结果均不得为空值。 */
    public <T> List<T> query(String statement, RowMapper<T> mapper, Object... arguments) {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return List.copyOf(jdbc.query(statement, mapper, values.bind(arguments)));
    }

    public <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    public String json(Object value) {
        return values.json(value);
    }

    public <T> T parse(String value, Class<T> type) {
        return values.parse(value, type);
    }

    public void audit(String action, Object target) {
        exec(
                "insert into admin_audit(id,action,target) values(?,?,?)",
                UUID.randomUUID(),
                action,
                target.toString());
    }

    public void audit(String action, Object target, Map<String, ?> details) {
        exec(
                "insert into admin_audit(id,action,target,details) values(?,?,?,?)",
                UUID.randomUUID(),
                action,
                target.toString(),
                json(details));
    }
}
