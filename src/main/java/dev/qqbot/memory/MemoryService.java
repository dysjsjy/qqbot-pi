package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.message.Texts;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {
    public record Source(String id,String name,Instant time,String body,double score){}
    private final Db db;private final EmbeddingClient embeddings;private final BotProperties props;
    public MemoryService(Db db,EmbeddingClient embeddings,BotProperties props){this.db=db;this.embeddings=embeddings;this.props=props;}

    public int indexPending(int limit){
        var rows=db.list("select m.*,g.id as scoped_group_id from group_message m join group_config g on g.id=m.group_id where m.index_state='PENDING' and g.enabled order by m.received_at limit ?",limit);
        int completed=0;
        for(var row:rows){UUID messageId=(UUID)row.get("id"),groupId=(UUID)row.get("group_id");long epoch=((Number)row.get("epoch")).longValue();
            try{var chunks=Texts.chunks((String)row.get("body"),1200);var vectors=embeddings.embed(chunks);
                db.tx(()->{var current=db.one("select epoch,enabled from group_config where id=? for update",groupId);if(current==null||!Boolean.TRUE.equals(current.get("enabled"))||((Number)current.get("epoch")).longValue()!=epoch)throw new IllegalStateException("MEMORY_EPOCH_CHANGED");
                    for(int i=0;i<chunks.size();i++)db.exec("insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions) values(?,?,?,?,?,?,cast(? as vector),?,?) on conflict(message_id,part_index,model) do nothing",UUID.randomUUID(),groupId,messageId,epoch,i,chunks.get(i),EmbeddingClient.vector(vectors.get(i)),props.embedding().model(),props.embedding().dimensions());
                    db.exec("update group_message set index_state='INDEXED',index_attempts=index_attempts+1,error_code=null where id=?",messageId);return null;});completed++;
            }catch(Exception failure){db.exec("update group_message set index_attempts=index_attempts+1,index_state=case when index_attempts+1>=3 then 'FAILED' else 'PENDING' end,error_code=? where id=?","EMBEDDING_FAILED",messageId);}
        }
        return completed;
    }

    public List<Source> search(UUID groupId,String question,int limit){return search(groupId,question,limit,null);}
    public List<Source> search(UUID groupId,String question,int limit,UUID excludeMessage){
        if(question==null||question.isBlank())return List.of();
        var group=db.one("select epoch,enabled from group_config where id=?",groupId);
        if(group==null||!Boolean.TRUE.equals(group.get("enabled")))throw new IllegalArgumentException("GROUP_DISABLED");
        var vector=EmbeddingClient.vector(embeddings.embed(List.of(question)).get(0));long epoch=((Number)group.get("epoch")).longValue();
        return db.jdbc.query("""
            select m.id,m.sender_name,m.occurred_at,c.body,1-(c.embedding <=> cast(? as vector)) as score
            from memory_chunk c join group_message m on m.id=c.message_id
            where c.group_id=? and c.epoch=? and m.epoch=? and m.occurred_at>now()-interval '30 days'
            and m.id<>coalesce(cast(? as uuid),'00000000-0000-0000-0000-000000000000'::uuid)
            order by c.embedding <=> cast(? as vector) limit ?
            """,rs->{List<Source> result=new ArrayList<>();while(rs.next()){double score=rs.getDouble("score");if(score>=props.embedding().minSimilarity())result.add(new Source(rs.getObject("id").toString(),rs.getString("sender_name"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("body"),score));}return result;},vector,groupId,epoch,epoch,excludeMessage,vector,Math.min(10,Math.max(1,limit)));
    }

    @Transactional public void clear(UUID groupId){
        var group=db.one("select epoch from group_config where id=? for update",groupId);if(group==null)throw new IllegalArgumentException("GROUP_NOT_FOUND");
        long next=((Number)group.get("epoch")).longValue()+1;
        db.exec("update group_config set epoch=?,revision=revision+1 where id=?",next,groupId);
        db.exec("update agent_run set state='CANCELLED',finished_at=now(),error_code='MEMORY_CLEARED' where group_id=? and state in ('QUEUED','RUNNING')",groupId);
        db.exec("update daily_run set state='CANCELLED',finished_at=now(),error_code='MEMORY_CLEARED' where group_id=? and state in ('QUEUED','RUNNING','GENERATED')",groupId);
        db.exec("update outbox set state='CANCELLED' where group_id=? and state='PENDING'",groupId);
        db.exec("update outbox set body='' where group_id=?",groupId);
        db.exec("update agent_run set prompt='',final_text=null where group_id=?",groupId);
        db.exec("update daily_run set state=case when state='CANCELLED' then state else 'EXPIRED' end,report=null,source_ids=null,coverage=null,error_code='MEMORY_CLEARED' where group_id=?",groupId);
        db.exec("delete from memory_chunk where group_id=?",groupId);
        db.exec("delete from group_message where group_id=?",groupId);
        db.exec("update conversation set generation=generation+1,checkpoint_ref=null where group_id=?",groupId);
        db.audit("MEMORY_CLEAR",groupId);
    }

    public void cleanup(){db.tx(()->{
        var groups=db.list("select distinct group_id from group_message where occurred_at<now()-interval '30 days'");
        for(var row:groups){Object id=row.get("group_id");db.one("select id from group_config where id=? for update",id);
            db.exec("update conversation set generation=generation+1,checkpoint_ref=null where group_id=?",id);
            db.exec("update agent_run set state='CANCELLED',finished_at=now(),error_code='SOURCE_EXPIRED' where group_id=? and state in ('RUNNING','QUEUED')",id);
            db.exec("update outbox set state='CANCELLED',body='',error_code='SOURCE_EXPIRED' where group_id=? and state='PENDING'",id);
            db.exec("update daily_run r set state='EXPIRED',report=null,source_ids=null,coverage=null,error_code='SOURCE_EXPIRED' where r.group_id=? and exists(select 1 from group_message m where m.group_id=r.group_id and m.occurred_at<now()-interval '30 days' and coalesce(r.source_ids,'[]'::jsonb) @> jsonb_build_array(m.id::text))",id);
            db.exec("delete from group_message where group_id=? and occurred_at<now()-interval '30 days'",id);
        }
        db.exec("update conversation set generation=generation+1,checkpoint_ref=null where checkpoint_ref is not null and updated_at<now()-interval '7 days'");
        db.exec("delete from outbox where created_at<now()-interval '30 days'");
        db.exec("delete from agent_run where created_at<now()-interval '30 days' and not exists(select 1 from outbox o where o.agent_run_id=agent_run.id)");
        db.exec("delete from daily_run where created_at<now()-interval '30 days' and not exists(select 1 from outbox o where o.daily_run_id=daily_run.id)");
        db.exec("delete from admin_audit where created_at<now()-interval '90 days'");return null;
    });cleanupCheckpoints();}

    public void cleanupCheckpoints(){
        var directory=props.stateDir().toAbsolutePath().normalize().resolve("checkpoints");
        if(!java.nio.file.Files.isDirectory(directory))return;
        var retained=new HashSet<String>();for(var row:db.list("select checkpoint_ref from conversation where checkpoint_ref is not null"))retained.add((String)row.get("checkpoint_ref"));
        try(var paths=java.nio.file.Files.list(directory)){paths.filter(java.nio.file.Files::isRegularFile).filter(p->!retained.contains(p.toString())).filter(p->{try{return java.nio.file.Files.getLastModifiedTime(p).toInstant().isBefore(Instant.now().minusSeconds(3600));}catch(Exception e){return false;}}).forEach(p->{try{java.nio.file.Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}
    }
}
