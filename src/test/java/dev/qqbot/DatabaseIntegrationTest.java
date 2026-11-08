package dev.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.channel.*;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.schedule.DailySummaryService;
import dev.qqbot.schedule.DailySummaryWorker;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs against either real PostgreSQL/pgvector or the documented local PGlite socket. */
@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class DatabaseIntegrationTest {
    static Db db;static BotProperties props;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"),ZoneOffset.UTC);
    DailySummaryService daily;IngestService ingest;MemoryService memory;EmbeddingClient embeddings;
    @BeforeAll static void database(){
        var ds=new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),"postgres","");
        if(!"true".equals(System.getenv("TEST_DATABASE_PREMIGRATED")))Flyway.configure().dataSource(ds).load().migrate();
        db=new Db(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),new ObjectMapper().findAndRegisterModules());
        var gateway=new BotProperties.Gateway(true,"http://localhost","token","10001","");
        props=new BotProperties("admin","",Path.of("target/test-state"),false,new BotProperties.Pi("node","fixture","mock","mock","TEST_KEY","",60,""),new BotProperties.Embedding("http://localhost","","test",3,0.5),gateway,gateway);
    }
    @BeforeEach void reset(){db.exec("truncate group_config cascade");daily=new DailySummaryService(db,clock);ingest=new IngestService(db,daily);embeddings=mock(EmbeddingClient.class);when(embeddings.embed(anyList())).thenAnswer(i->((List<?>)i.getArgument(0)).stream().map(x->new float[]{1,0,0}).toList());memory=new MemoryService(db,embeddings,props);}
    UUID group(String name){UUID id=UUID.randomUUID();db.exec("insert into group_config(id,channel,account_id,external_id,name,enabled,collection_since,admin_ids) values(?,'QQ','10001',?,?,true,?,'admin')",id,name,name,clock.instant().minus(Duration.ofDays(2)));return id;}
    GroupIngestRequest event(String group,String key,String text,boolean mention){return new GroupIngestRequest("QQ","10001",group,key,"user","小明",Instant.now(),text,mention,false);}
    long count(String table){return ((Number)db.one("select count(*) n from "+table).get("n")).longValue();}
    void raw(UUID group,Instant at,String text){db.exec("insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind) values(?,?,?,'u','成员',?,?,?,0,'TEXT')",UUID.randomUUID(),group,UUID.randomUUID().toString(),text,at,clock.instant().minusSeconds(1));}

    @Test void collectionDeduplicatesAndDoesNotReplyToOrdinaryText(){
        group("12345");
        for(int i=0;i<100;i++){final int n=i;for(int j=0;j<3;j++)db.tx(()->ingest.ingest(new GroupIngestRequest("QQ","10001","12345","fixed"+n,"user"+n,"成员",Instant.now(),"普通消息"+n,n<10,false)));}
        assertEquals(100,count("group_message"));assertEquals(10,count("agent_run"));
        db.tx(()->ingest.ingest(event("not-authorized","x","秘密",true)));assertEquals(100,count("group_message"));
        var self=event("12345","self","自己的消息",true);db.tx(()->ingest.ingest(new GroupIngestRequest(self.channel(),self.accountId(),self.groupId(),self.messageId(),self.senderId(),self.senderName(),self.occurredAt(),self.text(),true,true)));assertEquals(100,count("group_message"));
    }
    @Test void commandsUseExactPrefixAndNewPreservesMemory(){
        group("12345");db.tx(()->ingest.ingest(event("12345","a","/help",false)));assertEquals(0,count("agent_run"));assertEquals(1,count("outbox"));
        db.tx(()->ingest.ingest(event("12345","b","正文 /new",false)));assertEquals(0,count("agent_run"));
        db.tx(()->ingest.ingest(event("12345","c","/memory 安排",false)));assertEquals(1,count("agent_run"));
        db.tx(()->ingest.ingest(event("12345","d","/new",false)));assertEquals("CANCELLED",db.one("select state from agent_run").get("state"));assertEquals(4,count("group_message"));
        db.tx(()->ingest.ingest(event("12345","e","/summary yesterday",false)));assertEquals(0,count("daily_run"));
    }
    @Test void vectorsArePersistentScopedAndClearInvalidatesRunningResult(){
        UUID a=group("12345"),b=group("23456");raw(a,Instant.now(),"群A方案");raw(b,Instant.now(),"群B方案");assertEquals(2,memory.indexPending(50));
        assertEquals(2,count("memory_chunk"));var found=memory.search(a,"方案",5);assertEquals(1,found.size());assertEquals("群A方案",found.get(0).body());
        db.tx(()->ingest.ingest(event("12345","q","/memory 方案",false)));
        var worker=new AgentWorker(db,mock(PiRuntime.class),memory);var job=worker.claim();UUID run=(UUID)job.get("id");
        db.tx(()->{memory.clear(a);return null;});worker.complete(run,a,(UUID)job.get("conversation_id"),job,"旧结果",null);
        assertEquals("CANCELLED",db.one("select state from agent_run where id=?",run).get("state"));assertTrue(memory.search(a,"方案",5).isEmpty());assertEquals(1,count("memory_chunk"));assertEquals(0,count("outbox"));worker.close();
    }
    @Test void cancellationCannotBeResurrectedByCompletion(){
        UUID a=group("12345");db.tx(()->ingest.ingest(event("12345","q","问题",true)));
        var worker=new AgentWorker(db,mock(PiRuntime.class),memory);var job=worker.claim();UUID run=(UUID)job.get("id");
        db.tx(()->ingest.ingest(event("12345","cancel","/cancel",false)));
        worker.complete(run,a,(UUID)job.get("conversation_id"),job,"迟到答案",null);
        assertEquals("CANCELLED",db.one("select state from agent_run where id=?",run).get("state"));assertEquals(0,((Number)db.one("select count(*) n from outbox where agent_run_id=?",run).get("n")).intValue());worker.close();
    }
    @Test void yesterdayWindowIsHalfOpenAndSnapshotIsFrozen(){
        UUID a=group("12345");raw(a,Instant.parse("2026-11-05T16:00:00Z"),"昨日开始");raw(a,Instant.parse("2026-11-06T15:59:59Z"),"昨日结束");raw(a,Instant.parse("2026-11-06T16:00:00Z"),"今天");
        UUID id=daily.trigger(a,"WEB");assertEquals(id,daily.trigger(a,"COMMAND"));var data=daily.process(id);assertEquals(2,((List<?>)data.get("sources")).size());
        raw(a,Instant.parse("2026-11-06T10:00:00Z"),"迟到文本");db.exec("update daily_run set state='QUEUED',attempt=2 where id=?",id);
        var retry=daily.process(id);assertEquals(data.get("sources"),retry.get("sources"));
    }
    @Test void scheduleFiresOnceAndAdvancesToTomorrow(){
        UUID a=group("12345");db.exec("insert into daily_schedule(group_id,enabled,next_run_at) values(?,true,?)",a,clock.instant());daily.scheduleDue();daily.scheduleDue();assertEquals(1,count("daily_run"));
        assertEquals(Instant.parse("2026-11-08T01:00:00Z"),((java.sql.Timestamp)db.one("select next_run_at from daily_schedule").get("next_run_at")).toInstant());
    }
    @Test void noDataAndInputLimitDoNotInvokeGeneration(){
        UUID a=group("12345");UUID id=daily.trigger(a,"WEB");assertNull(daily.process(id));assertEquals("NO_DATA",db.one("select state from daily_run where id=?",id).get("state"));
        UUID b=group("23456");for(int i=0;i<1001;i++)raw(b,clock.instant().minus(Duration.ofHours(12)),"消息"+i);UUID over=daily.trigger(b,"WEB");assertNull(daily.process(over));assertEquals("LIMIT_EXCEEDED",db.one("select state from daily_run where id=?",over).get("state"));
    }
    @Test void unknownDeliveryIsNeverRetriedAndBlocksFollowingParts(){
        UUID a=group("12345");var sender=mock(ChannelSender.class);when(sender.send(anyString(),anyString(),anyString(),anyString())).thenReturn(new ChannelSender.Delivery(ChannelSender.State.UNKNOWN,null,"TIMEOUT"));
        for(int i=0;i<2;i++)db.exec("insert into outbox(id,group_id,result_key,epoch,part_index,body) values(?,?,'result',0,?,'文本')",UUID.randomUUID(),a,i);
        var worker=new OutboxWorker(db,sender,new GroupStore(db));worker.tick();worker.tick();verify(sender,times(1)).send(anyString(),anyString(),anyString(),anyString());
        assertEquals("UNKNOWN",db.one("select state from outbox where part_index=0").get("state"));assertEquals("CANCELLED",db.one("select state from outbox where part_index=1").get("state"));
    }
    @Test void oldConversationGenerationCannotSend(){
        UUID a=group("12345"),c=UUID.randomUUID();db.exec("insert into conversation(id,group_id,sender_id,generation) values(?,?,'u',2)",c,a);
        db.exec("insert into outbox(id,group_id,result_key,conversation_id,generation,epoch,part_index,body) values(?,?,'result',?,1,0,0,'旧答案')",UUID.randomUUID(),a,c);
        var sender=mock(ChannelSender.class);var worker=new OutboxWorker(db,sender,new GroupStore(db));worker.tick();verifyNoInteractions(sender);assertEquals("CANCELLED",db.one("select state from outbox").get("state"));
    }
    @Test void dailyWorkerProcessesEveryBlockAndPublishesOneReport()throws Exception{
        UUID a=group("12345");for(int i=0;i<100;i++)raw(a,clock.instant().minus(Duration.ofHours(12)),"关键讨论"+i+"事实".repeat(100));
        UUID id=daily.trigger(a,"WEB");var pi=mock(PiRuntime.class);
        when(pi.generate(any(),anyString(),isNull(),eq(false),anyInt())).thenAnswer(invocation->{
            String prompt=invocation.getArgument(1);var ref=java.util.regex.Pattern.compile("\\[([0-9a-f-]{36})\\]").matcher(prompt);assertTrue(ref.find());return new PiRuntime.Result("结论和待办，负责人未明确 ["+ref.group(1)+"]",null);
        });
        var worker=new DailySummaryWorker(db,daily,pi);worker.tick();
        try{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while("QUEUED".equals(db.one("select state from daily_run where id=?",id).get("state"))||"RUNNING".equals(db.one("select state from daily_run where id=?",id).get("state"))){if(System.nanoTime()>deadline)fail("daily worker timed out");Thread.sleep(20);}
            var run=db.one("select * from daily_run where id=?",id);assertEquals("GENERATED",run.get("state"));assertEquals(100,((List<?>)run.get("source_ids")).size());
            verify(pi,atLeast(2)).generate(any(),anyString(),isNull(),eq(false),anyInt());assertTrue(count("outbox")>0);
        }finally{worker.close();}
    }
    @Test void retentionErasesExpiredSourcesReportsAndTemporaryContext(){
        UUID a=group("12345"),old=UUID.randomUUID();
        db.exec("insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind) values(?,?,'old','u','u','expired',now()-interval '31 days',0,'TEXT')",old,a);
        raw(a,Instant.now(),"fresh");
        UUID id=daily.trigger(a,"WEB");db.exec("update daily_run set state='DELIVERED',report='expired report',source_ids=?::jsonb where id=?",db.json(List.of(old.toString())),id);
        db.exec("insert into conversation(id,group_id,sender_id,checkpoint_ref) values(?,?,'u','invalid-old-checkpoint')",UUID.randomUUID(),a);
        memory.cleanup();assertEquals(1,count("group_message"));assertEquals("fresh",db.one("select body from group_message").get("body"));assertNull(db.one("select report from daily_run where id=?",id).get("report"));assertNull(db.one("select checkpoint_ref from conversation").get("checkpoint_ref"));
    }
}
