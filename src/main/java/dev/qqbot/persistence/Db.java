package dev.qqbot.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
@Component
public class Db {
 public final JdbcTemplate jdbc;
 private final TransactionTemplate transactions;
 private final ObjectMapper json;
 public Db(JdbcTemplate jdbc,TransactionTemplate transactions,ObjectMapper json){this.jdbc=jdbc;this.transactions=transactions;this.json=json;}
 public int exec(String sql,Object... args){return jdbc.update(sql,parameters(args));}
 public List<Map<String,Object>> list(String sql,Object... args){var rows=jdbc.queryForList(sql,parameters(args));for(var row:rows)row.replaceAll((k,v)->{if(v instanceof org.postgresql.util.PGobject pg&&Set.of("json","jsonb").contains(pg.getType()))return parse(v.toString(),Object.class);return v;});return rows;}
 public static Object[] parameters(Object[] values){return java.util.Arrays.stream(values).map(v->v instanceof java.time.Instant i?java.sql.Timestamp.from(i):v).toArray();}
 public Map<String,Object> one(String sql,Object... args){var rows=list(sql,args);return rows.isEmpty()?null:rows.get(0);}
 public <T>T tx(Supplier<T> work){return transactions.execute(status->work.get());}
 public String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("JSON encoding failed",e);}}
 public <T>T parse(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalArgumentException("Stored JSON invalid",e);}}
 public void audit(String action,Object target){exec("insert into admin_audit(id,action,target) values(?,?,?)",UUID.randomUUID(),action,target.toString());}
}
