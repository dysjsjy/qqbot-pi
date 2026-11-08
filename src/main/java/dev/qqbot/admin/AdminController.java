package dev.qqbot.admin;

import dev.qqbot.BotProperties;
import dev.qqbot.memory.MemoryService;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    public record GroupInput(@NotBlank @Pattern(regexp="QQ|WEIXIN")String channel,@NotBlank @Size(max=160)String accountId,
      @NotBlank @Size(max=160)String externalId,@NotBlank @Size(max=200)String name,boolean enabled,boolean replyEnabled,
      @Size(max=2000)String adminIds,long revision){}
    public record ScheduleInput(boolean enabled,@NotBlank @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d")String localTime){}
    public record ClearInput(@NotBlank String confirmName){}
    private final Db db;private final MemoryService memory;private final DailySummaryService summaries;private final BotProperties properties;private final java.time.Clock clock;
    public AdminController(Db db,MemoryService memory,DailySummaryService summaries,BotProperties properties,java.time.Clock clock){this.db=db;this.memory=memory;this.summaries=summaries;this.properties=properties;this.clock=clock;}

    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken());}
    @GetMapping("/me") public Map<String,String> me(Authentication auth){return Map.of("username",auth.getName());}
    @GetMapping("/status") public Map<String,Object> status(){
        var counts=db.one("select (select count(*) from group_config where enabled) groups,(select count(*) from group_message where index_state='PENDING') pending_index,(select count(*) from group_message where index_state='FAILED') failed_index,(select count(*) from agent_run where state in ('QUEUED','RUNNING')) active_runs,(select count(*) from outbox where state='UNKNOWN') unknown_deliveries");
        return Map.of("counts",counts,"gateways",Map.of("QQ",Map.of("enabled",properties.qq().enabled(),"configured",!properties.qq().baseUrl().isBlank()),"WEIXIN",Map.of("enabled",properties.weixin().enabled(),"configured",!properties.weixin().baseUrl().isBlank())));
    }
    @GetMapping("/groups") public List<Map<String,Object>> groups(){return db.list("select g.*,(select count(*) from group_message m where m.group_id=g.id and m.epoch=g.epoch) message_count,(select count(*) from memory_chunk c where c.group_id=g.id and c.epoch=g.epoch) memory_count from group_config g order by channel,name");}
    @PostMapping("/groups") @Transactional public Map<String,Object> create(@Valid @RequestBody GroupInput input){
        validateGroup(input,null);
        var gateway=properties.gateway(input.channel());UUID id=UUID.randomUUID();
        db.exec("insert into group_config(id,channel,account_id,external_id,name,enabled,reply_enabled,admin_ids,collection_since,connection_state) values(?,?,?,?,?,?,?,?,case when ? then now() else null end,?)",
                id,input.channel(),input.accountId(),input.externalId(),input.name(),input.enabled(),input.replyEnabled(),input.adminIds()==null?"":input.adminIds(),input.enabled(),"UNKNOWN");
        var row=db.one("select * from group_config where channel=? and account_id=? and external_id=?",input.channel(),input.accountId(),input.externalId());
        UUID actual=(UUID)row.get("id");db.exec("insert into daily_schedule(group_id,enabled,local_time,zone,next_run_at) values(?,false,'09:00','Asia/Shanghai',now()+interval '1 day') on conflict(group_id) do nothing",actual);db.audit("GROUP_UPSERT",actual);return row;
    }
    @PatchMapping("/groups/{id}") @Transactional public Map<String,Object> update(@PathVariable UUID id,@Valid @RequestBody GroupInput input){
        validateGroup(input,id);var old=db.one("select revision,external_id,channel,account_id,enabled from group_config where id=? for update",id);if(old==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!Objects.equals(old.get("channel"),input.channel())||!Objects.equals(old.get("account_id"),input.accountId())||!Objects.equals(old.get("external_id"),input.externalId()))throw new ResponseStatusException(HttpStatus.CONFLICT,"群与账号标识不可更改，请新增群配置");
        if(Boolean.TRUE.equals(old.get("enabled"))&&!input.enabled())db.exec("insert into collection_gap(id,group_id,starts_at,reason) values(?,?,now(),'GROUP_DISABLED')",UUID.randomUUID(),id);
        if(!Boolean.TRUE.equals(old.get("enabled"))&&input.enabled())db.exec("update collection_gap set ends_at=now() where group_id=? and ends_at is null",id);
        var updated=db.exec("update group_config set channel=?,account_id=?,external_id=?,name=?,enabled=?,reply_enabled=?,admin_ids=?,revision=revision+1,collection_since=case when ? and collection_since is null then now() else collection_since end where id=? and revision=?",
                input.channel(),input.accountId(),input.externalId(),input.name(),input.enabled(),input.replyEnabled(),input.adminIds()==null?"":input.adminIds(),input.enabled(),id,input.revision());
        if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"群配置已被其他操作更新，请刷新");
        if(!input.enabled()){db.exec("update daily_schedule set enabled=false,revision=revision+1 where group_id=?",id);db.exec("update agent_run set state='CANCELLED',finished_at=now() where group_id=? and state in ('QUEUED','RUNNING')",id);db.exec("update daily_run set state='CANCELLED',finished_at=now() where group_id=? and state in ('QUEUED','RUNNING')",id);db.exec("update outbox set state='CANCELLED' where group_id=? and state='PENDING'",id);}
        if(!input.replyEnabled()){db.exec("update agent_run set state='CANCELLED',finished_at=now() where group_id=? and state in ('QUEUED','RUNNING')",id);db.exec("update daily_run set state='CANCELLED',finished_at=now() where group_id=? and state in ('QUEUED','RUNNING','GENERATED')",id);db.exec("update outbox set state='CANCELLED' where group_id=? and state='PENDING'",id);}
        db.audit("GROUP_UPDATE",id);return db.one("select * from group_config where id=?",id);
    }
    @GetMapping("/groups/{id}/memory/status") public Map<String,Object> memoryStatus(@PathVariable UUID id){var row=db.one("select (select count(*) from group_message where group_id=? and epoch=(select epoch from group_config where id=?) and index_state='PENDING') pending,(select count(*) from group_message where group_id=? and epoch=(select epoch from group_config where id=?) and index_state='FAILED') failed,(select count(*) from memory_chunk where group_id=? and epoch=(select epoch from group_config where id=?)) indexed,(select max(created_at) from memory_chunk where group_id=?) last_indexed",id,id,id,id,id,id,id);return row;}
    @PostMapping("/groups/{id}/memory/search") public List<MemoryService.Source> search(@PathVariable UUID id,@RequestBody Map<String,String> input){if(input.getOrDefault("question","").length()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return memory.search(id,input.getOrDefault("question",""),5);}
    @PostMapping("/groups/{id}/memory/retry") public Map<String,Object> retryIndex(@PathVariable UUID id){int count=db.exec("update group_message set index_state='PENDING',index_attempts=0,error_code=null where group_id=? and index_state='FAILED' and epoch=(select epoch from group_config where id=?)",id,id);db.audit("MEMORY_RETRY",id);return Map.of("requeued",count);}
    @DeleteMapping("/groups/{id}/memory") @Transactional public void clear(@PathVariable UUID id,@Valid @RequestBody ClearInput input){var row=db.one("select name from group_config where id=?",id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(!Objects.equals(row.get("name"),input.confirmName()))throw new ResponseStatusException(HttpStatus.CONFLICT,"确认名称不匹配");memory.clear(id);}
    @GetMapping("/groups/{id}/schedule") public Map<String,Object> schedule(@PathVariable UUID id){var row=db.one("select * from daily_schedule where group_id=?",id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return row;}
    @PutMapping("/groups/{id}/schedule") @Transactional public Map<String,Object> schedule(@PathVariable UUID id,@Valid @RequestBody ScheduleInput input){
        if(db.one("select id from group_config where id=? and enabled",id)==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先启用群采集");
        LocalTime time=LocalTime.parse(input.localTime());var now=java.time.ZonedDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));var next=now.toLocalDate().atTime(time).atZone(ZoneId.of("Asia/Shanghai"));
        db.exec("insert into daily_schedule(group_id,enabled,local_time,zone,next_run_at) values(?,?,?,'Asia/Shanghai',?) on conflict(group_id) do update set enabled=excluded.enabled,local_time=excluded.local_time,next_run_at=excluded.next_run_at,revision=daily_schedule.revision+1",id,input.enabled(),time,next.toInstant());db.audit("SCHEDULE_UPDATE",id);return schedule(id);
    }
    @PostMapping("/groups/{id}/summary/yesterday") public Map<String,Object> trigger(@PathVariable UUID id){UUID run=summaries.trigger(id,"WEB");return Map.of("runId",run);}
    @GetMapping("/runs") public List<Map<String,Object>> runs(@RequestParam(required=false)UUID groupId,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){limit=Math.max(1,Math.min(100,limit));offset=Math.max(0,offset);return groupId==null?db.list("select id,group_id,business_date,state,attempt,coverage,error_code,created_at,finished_at from daily_run order by created_at desc limit ? offset ?",limit,offset):db.list("select id,group_id,business_date,state,attempt,coverage,error_code,created_at,finished_at from daily_run where group_id=? order by created_at desc limit ? offset ?",groupId,limit,offset);}
    @GetMapping("/runs/{id}") public Map<String,Object> run(@PathVariable UUID id){var row=db.one("select * from daily_run where id=?",id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return row;}
    @PostMapping("/runs/{id}/retry") @Transactional public Map<String,Object> retry(@PathVariable UUID id){var row=db.one("select * from daily_run where id=? for update",id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);var group=db.one("select epoch,enabled from group_config where id=?",row.get("group_id"));if(group==null||!Boolean.TRUE.equals(group.get("enabled"))||!Objects.equals(group.get("epoch"),row.get("epoch")))throw new ResponseStatusException(HttpStatus.CONFLICT,"来源记录已失效");String state=(String)row.get("state");if(!Set.of("FAILED","INTERRUPTED").contains(state)||((Number)row.get("attempt")).intValue()>=2)throw new ResponseStatusException(HttpStatus.CONFLICT,"任务不可重试");db.exec("update daily_run set state='QUEUED',attempt=attempt+1,error_code=null,finished_at=null where id=?",id);return Map.of("runId",id,"state","QUEUED");}
    private void validateGroup(GroupInput input,UUID id){
        db.one("select pg_advisory_xact_lock(11072028)");
        var gateway=properties.gateway(input.channel());
        if(input.enabled()&&(!gateway.enabled()||!gateway.accountId().equals(input.accountId())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号必须匹配已启用的平台网关");
        if(input.channel().equals("QQ")&&!input.externalId().matches("[1-9][0-9]{4,15}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"QQ群号无效");
        if(input.enabled()&&((Number)db.one("select count(*) n from group_config where enabled and id<>coalesce(cast(? as uuid),'00000000-0000-0000-0000-000000000000'::uuid)",id).get("n")).intValue()>=5)throw new ResponseStatusException(HttpStatus.CONFLICT,"MVP最多启用5个群");
    }

}
