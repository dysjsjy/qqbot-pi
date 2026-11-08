package dev.qqbot.persistence;
import dev.qqbot.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
@Component
public class Lifecycle {
 private final DataSource source;private final Db db;private final BotProperties props;private Connection lock;
 public Lifecycle(DataSource source,Db db,BotProperties props){this.source=source;this.db=db;this.props=props;}
 @PostConstruct void start()throws Exception{
  if(props.pi().provider().isBlank()||props.pi().model().isBlank())throw new IllegalStateException("PI_PROVIDER and PI_MODEL are required");
  if(props.embedding().url().isBlank()||props.embedding().model().isBlank()||props.embedding().dimensions()<1)throw new IllegalStateException("Embedding configuration required");
  for(var gateway:new BotProperties.Gateway[]{props.qq(),props.weixin()})if(gateway.enabled()&&(gateway.token().isBlank()||gateway.baseUrl().isBlank()||gateway.accountId().isBlank()))throw new IllegalStateException("Enabled gateway requires URL, token and account ID");
  lock=source.getConnection();try(var statement=lock.createStatement();var result=statement.executeQuery("select pg_try_advisory_lock(11072026)")){result.next();if(!result.getBoolean(1)){lock.close();throw new IllegalStateException("Another bot instance owns this database");}}
  try {
  db.exec("update agent_run set state='INTERRUPTED',error_code='SERVICE_RESTART',finished_at=now() where state='RUNNING'");
  db.exec("update daily_run set state='INTERRUPTED',error_code='SERVICE_RESTART',finished_at=now() where state='RUNNING'");
  db.exec("update outbox set state='UNKNOWN',error_code='SERVICE_RESTART' where state='SENDING'");
  db.exec("insert into collection_gap(id,group_id,starts_at,ends_at,reason) select gen_random_uuid(),id,coalesce(last_received_at,collection_since,created_at),now(),'SERVICE_RESTART_UNVERIFIED' from group_config where enabled");
  String embeddingIdentity=props.embedding().model()+":"+props.embedding().dimensions();
  var old=db.one("select value from app_metadata where name='embedding_identity'");
  if(old!=null&&!old.get("value").equals(embeddingIdentity))throw new IllegalStateException("Embedding model changed; explicit reindex required");
  db.exec("insert into app_metadata(name,value) values('embedding_identity',?) on conflict(name) do nothing",embeddingIdentity);
  } catch(Exception failure){lock.close();lock=null;throw failure;}
 }
 @PreDestroy void close()throws Exception{if(lock!=null)lock.close();}
}
