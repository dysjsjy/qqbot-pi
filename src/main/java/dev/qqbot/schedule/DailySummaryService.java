package dev.qqbot.schedule;

import dev.qqbot.message.Texts;
import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DailySummaryService {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final Db db;private final Clock clock;
    public DailySummaryService(Db db,Clock clock){this.db=db;this.clock=clock;}

    @Scheduled(fixedDelay=10_000) public void scheduleDue(){
        var now=clock.instant();LocalDate today=LocalDate.now(clock.withZone(ZONE));
        var due=db.list("select s.group_id,s.local_time from daily_schedule s join group_config g on g.id=s.group_id where s.enabled and g.enabled and g.reply_enabled and s.zone='Asia/Shanghai' and s.next_run_at<=? limit 20",now);
        for(var row:due)db.tx(()->{
            create((UUID)row.get("group_id"),today.minusDays(1),"SCHEDULE");
            LocalTime time=((java.sql.Time)row.get("local_time")).toLocalTime();
            db.exec("update daily_schedule set next_run_at=? where group_id=? and next_run_at<=?",today.plusDays(1).atTime(time).atZone(ZONE).toInstant(),row.get("group_id"),now);return null;
        });
    }
    public UUID trigger(UUID groupId,String mode){return create(groupId,LocalDate.now(clock.withZone(ZONE)).minusDays(1),mode);}
    public UUID create(UUID groupId,LocalDate date,String mode){return db.tx(()->{
        UUID id=UUID.randomUUID();var group=db.one("select epoch,enabled,reply_enabled from group_config where id=? for update",groupId);
        if(group==null||!Boolean.TRUE.equals(group.get("enabled"))||!Boolean.TRUE.equals(group.get("reply_enabled")))throw new IllegalArgumentException("GROUP_DISABLED");
        db.exec("insert into daily_run(id,group_id,business_date,epoch,trigger_type,state) values(?,?,?,?,?,'QUEUED') on conflict(group_id,business_date) do nothing",id,groupId,date,group.get("epoch"),mode);
        return (UUID)db.one("select id from daily_run where group_id=? and business_date=?",groupId,date).get("id");
    });}

    public Map<String,Object> process(UUID id){return db.tx(()->{
        var before=db.one("select group_id from daily_run where id=?",id);if(before==null)return null;
        var group=db.one("select * from group_config where id=? for update",before.get("group_id"));
        var job=db.one("update daily_run set state='RUNNING' where id=? and state='QUEUED' returning *",id);if(job==null)return null;
        UUID groupId=(UUID)job.get("group_id");long epoch=((Number)job.get("epoch")).longValue();
        if(!Boolean.TRUE.equals(group.get("enabled"))||!Objects.equals(group.get("epoch"),job.get("epoch"))){db.exec("update daily_run set state='CANCELLED',finished_at=now() where id=?",id);return null;}
        LocalDate date=((java.sql.Date)job.get("business_date")).toLocalDate();
        Instant from=date.atStartOfDay(ZONE).toInstant(),to=date.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<Map<String,Object>> rows;
        List<Map<String,Object>> gaps=db.list("select starts_at,ends_at,reason from collection_gap where group_id=? and starts_at<? and coalesce(ends_at,?)>? order by starts_at",groupId,to,clock.instant(),from);
        Instant since=group.get("collection_since")==null?clock.instant():((java.sql.Timestamp)group.get("collection_since")).toInstant();
        if(since.isAfter(from))gaps.add(Map.of("starts_at",from,"ends_at",since.isBefore(to)?since:to,"reason","COLLECTION_NOT_ENABLED"));
        Map<String,Object> coverage=new LinkedHashMap<>();
        if(job.get("snapshot_at")==null){
            Instant snapshot=clock.instant();
            rows=db.list("select id,sender_name,body,occurred_at from group_message where group_id=? and epoch=? and kind='TEXT' and occurred_at>=? and occurred_at<? and received_at<=? and body not like '/%' order by occurred_at,id",groupId,epoch,from,to,snapshot);
            List<String> ids=rows.stream().map(r->r.get("id").toString()).toList();
            coverage.put("messages",rows.size());coverage.put("nonTextMessages",db.one("select count(*) n from group_message where group_id=? and epoch=? and kind='NON_TEXT' and occurred_at>=? and occurred_at<? and received_at<=?",groupId,epoch,from,to,snapshot).get("n"));coverage.put("gaps",gaps);coverage.put("windowStart",from);coverage.put("windowEnd",to);coverage.put("snapshotAt",snapshot);coverage.put("latePolicy","快照之后到达的事件不纳入本次分析");
            db.exec("update daily_run set snapshot_at=?,source_ids=?::jsonb,coverage=?::jsonb where id=?",snapshot,db.json(ids),db.json(coverage),id);
        }else{
            rows=db.list("select m.id,m.sender_name,m.body,m.occurred_at from group_message m join daily_run r on r.id=? where m.group_id=? and m.epoch=? and m.id::text in (select jsonb_array_elements_text(r.source_ids)) order by m.occurred_at,m.id",id,groupId,epoch);
            @SuppressWarnings("unchecked") var saved=(Map<String,Object>)job.get("coverage");coverage.putAll(saved);
            if(rows.size()!=((Number)coverage.get("messages")).intValue())throw new IllegalStateException("SOURCE_SNAPSHOT_EXPIRED");
        }
        if(rows.size()>1000||rows.stream().mapToInt(r->((String)r.get("body")).codePointCount(0,((String)r.get("body")).length())).sum()>100_000){db.exec("update daily_run set state='LIMIT_EXCEEDED',error_code='INPUT_LIMIT',finished_at=now() where id=?",id);return null;}
        if(rows.isEmpty()){
            String report=gaps.isEmpty()?"昨日无可分析文本。":"昨日无已采集文本，且采集不完整。";
            db.exec("update daily_run set state='NO_DATA',report=?,finished_at=now() where id=?",report,id);return null;
        }
        List<String> blocks=new ArrayList<>(),sources=new ArrayList<>();StringBuilder block=new StringBuilder();
        for(var row:rows){
            String name=(String)row.get("sender_name");if(name.length()>40)name=name.substring(0,40);
            String line="["+row.get("id")+"] "+row.get("occurred_at")+" "+name+": "+row.get("body")+"\n";
            if(block.length()+line.length()>12_000&&!block.isEmpty()){blocks.add(block.toString());block.setLength(0);}block.append(line);sources.add(row.get("id").toString());
        }
        if(!block.isEmpty())blocks.add(block.toString());
        return Map.of("job",job,"group",groupId,"date",date,"sources",sources,"summaries",blocks,"coverage",coverage,"incomplete",!((List<?>)coverage.get("gaps")).isEmpty());
    });}

    public void finish(UUID id,UUID groupId,long epoch,List<String> sourceIds,Map<String,Object> coverage,String report){db.tx(()->{
        var current=db.one("select epoch,enabled,reply_enabled from group_config where id=? for update",groupId);
        var state=db.one("select state from daily_run where id=? for update",id);
        if(state==null||!"RUNNING".equals(state.get("state")))return null;
        if(current==null||!Boolean.TRUE.equals(current.get("enabled"))||!Boolean.TRUE.equals(current.get("reply_enabled"))||((Number)current.get("epoch")).longValue()!=epoch){db.exec("update daily_run set state='CANCELLED',error_code='STALE_EPOCH',finished_at=now() where id=?",id);return null;}
        db.exec("update daily_run set state='GENERATED',report=?,coverage=?::jsonb,source_ids=?::jsonb,finished_at=now() where id=?",report,db.json(coverage),db.json(sourceIds),id);
        var parts=Texts.delivery(report);for(int i=0;i<parts.size();i++)db.exec("insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body) values(?,?,?,?,?,?,?) on conflict(result_key,part_index) do nothing",UUID.randomUUID(),groupId,"daily:"+id,id,epoch,i,parts.get(i));return null;
    });}
    public List<Map<String,Object>> summaries(UUID groupId,int limit){return db.list("select id,business_date,state,attempt,coverage,report,error_code,created_at,finished_at from daily_run where group_id=? order by business_date desc limit ?",groupId,Math.max(1,Math.min(limit,100)));}
}
