package dev.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.pi.PiRuntime;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PiRuntimeTest {
    @TempDir Path state;
    BotProperties props(String cli,int timeout,String url){var gateway=new BotProperties.Gateway(false,"","","","");return new BotProperties("admin","",state,false,new BotProperties.Pi("node",cli,"local-test","test-model","TEST_MODEL_KEY","local-dummy",timeout,url),null,gateway,gateway);}
    @Test void readsSplitUtf8AndIgnoresPrivateOutputAndSavesCheckpoint()throws Exception{
        byte[] bytes="中文🙂\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("中文🙂",PiRuntime.readFrame(new FilterInputStream(new ByteArrayInputStream(bytes)){@Override public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(1,len));}}));
        try(var runtime=new RuntimeHandle(new PiRuntime(props("src/test/resources/pi-fixture.cjs",5,""),new ObjectMapper()))){
            var result=runtime.pi.generate(UUID.randomUUID(),"normal",null,true);assertEquals("中文回答🙂",result.text());assertEquals("checkpoint",Files.readString(Path.of(result.checkpoint())));
            var restored=runtime.pi.generate(UUID.randomUUID(),"normal",result.checkpoint(),true);assertEquals(result.text(),restored.text());
        }
    }
    @Test void timeoutAndCancellationStopChild()throws Exception{
        var pi=new PiRuntime(props("src/test/resources/pi-fixture.cjs",1,""),new ObjectMapper());
        try{
            assertThrows(IllegalStateException.class,()->pi.generate(UUID.randomUUID(),"slow",null,false));
            UUID id=UUID.randomUUID();var work=CompletableFuture.runAsync(()->pi.generate(id,"slow",null,false));
            Thread.sleep(300);pi.cancel(id);assertThrows(ExecutionException.class,()->work.get(3,TimeUnit.SECONDS));
            assertTrue(Files.list(state).noneMatch(p->p.getFileName().toString().startsWith("run-")));
        }finally{pi.close();}
    }
    @Test void realPinnedPiUsesLocalModelHttpAndRestoresNativeSession()throws Exception{
        Path cli=Path.of("runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js");
        Assumptions.assumeTrue(Files.isRegularFile(cli),"Run npm ci in runtime/pi for real pi protocol smoke");
        var requests=new CopyOnWriteArrayList<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requests.add(body);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            String payload="data: "+"{\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"真实pi本地协议验证🙂\"},\"finish_reason\":null}]}"+"\n\ndata: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\ndata: [DONE]\n\n";
            exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));exchange.close();
        });server.start();
        var pi=new PiRuntime(props(cli.toString(),20,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1"),new ObjectMapper());
        try{
            var first=pi.generate(UUID.randomUUID(),"first-marker",null,true);assertEquals("真实pi本地协议验证🙂",first.text());assertNotNull(first.checkpoint());
            var second=pi.generate(UUID.randomUUID(),"second-marker",first.checkpoint(),true);assertEquals(first.text(),second.text());
            assertEquals(2,requests.size());assertTrue(requests.get(1).contains("first-marker"));assertFalse(requests.get(0).contains("tools"));
        }finally{pi.close();server.stop(0);}
    }
    private record RuntimeHandle(PiRuntime pi)implements AutoCloseable{public void close(){pi.close();}}
}
