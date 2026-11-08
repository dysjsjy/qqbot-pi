package dev.qqbot.channel;

import dev.qqbot.message.GroupStore;
import dev.qqbot.persistence.Db;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {
    private final Db db;private final ChannelSender sender;private final GroupStore groups;private final AtomicBoolean running=new AtomicBoolean();
    public OutboxWorker(Db db,ChannelSender sender,GroupStore groups){this.db=db;this.sender=sender;this.groups=groups;}
    @Scheduled(fixedDelay=500) public void tick(){if(!running.compareAndSet(false,true))return;try{
        db.exec("update outbox o set state='CANCELLED',error_code='PREVIOUS_PART_FAILED' where o.state='PENDING' and exists(select 1 from outbox p where p.result_key=o.result_key and p.part_index<o.part_index and p.state in ('UNKNOWN','FAILED','CANCELLED'))");
        for(int i=0;i<20;i++){var row=claim();if(row==null)break;send(row);}
        reconcile();
    }finally{running.set(false);}}
    public Map<String,Object> claim(){return db.one("""
        with candidate as (select o.id from outbox o join group_config g on g.id=o.group_id
          where o.state='PENDING' and o.next_attempt_at<=now() and g.enabled and g.reply_enabled and g.epoch=o.epoch
          and not exists(select 1 from outbox p where p.result_key=o.result_key and p.part_index<o.part_index and p.state<>'SENT')
          order by o.created_at,o.part_index for update of o skip locked limit 1)
        update outbox o set state='SENDING',attempts=attempts+1 from candidate c where o.id=c.id returning o.*
        """);}
    public void send(Map<String,Object> row){UUID id=(UUID)row.get("id");try{db.tx(()->{
        var current=db.one("select * from group_config where id=? for update",row.get("group_id"));
        boolean valid=current!=null&&Boolean.TRUE.equals(current.get("enabled"))&&Boolean.TRUE.equals(current.get("reply_enabled"))&&Objects.equals(current.get("epoch"),row.get("epoch"));
        if(valid&&row.get("conversation_id")!=null){var c=db.one("select generation from conversation where id=? for update",row.get("conversation_id"));valid=c!=null&&Objects.equals(c.get("generation"),row.get("generation"));}
        if(!valid){db.exec("update outbox set state='CANCELLED',error_code='STALE_RESULT' where id=?",id);return null;}
        var group=groups.get((UUID)row.get("group_id"));
        var result=sender.send(group.channel(),group.externalId(),(String)row.get("body"),id.toString());
        String state=switch(result.state()){case ACCEPTED->"SENT";case RETRYABLE->((Number)row.get("attempts")).intValue()<3?"PENDING":"FAILED";case FAILED->"FAILED";case UNKNOWN->"UNKNOWN";};
        db.exec("update outbox set state=?,platform_id=?,error_code=?,next_attempt_at=now()+interval '5 seconds' where id=?",state,result.platformId(),result.code(),id);
        return null;
    });}catch(Exception failure){db.exec("update outbox set state='UNKNOWN',error_code='SEND_RESULT_UNKNOWN' where id=? and state='SENDING'",id);}}
    public void reconcile(){db.exec("""
        update daily_run r set state=case
        when exists(select 1 from outbox o where o.daily_run_id=r.id and o.state='UNKNOWN') then 'DELIVERY_UNKNOWN'
        when exists(select 1 from outbox o where o.daily_run_id=r.id and o.state='FAILED') then 'DELIVERY_FAILED'
        when not exists(select 1 from outbox o where o.daily_run_id=r.id and o.state<>'SENT') then 'DELIVERED'
        else r.state end
        where r.state='GENERATED' and exists(select 1 from outbox o where o.daily_run_id=r.id)
        """);}
}
