package dev.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.channel.ChannelSender;
import dev.qqbot.memory.EmbeddingClient;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpAdapterTest {
    HttpServer server;String base;ObjectMapper json=new ObjectMapper();
    @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();}
    @AfterEach void close(){server.stop(0);}
    BotProperties props(){var g=new BotProperties.Gateway(true,base,"token","account","");return new BotProperties("admin","",Path.of("target"),false,null,new BotProperties.Embedding(base+"/embeddings","","test",3,0.5),g,g);}
    @Test void onebotUsesTextSegmentsAndActualPlatformReceipt(){
        var request=new AtomicReference<String>();server.createContext("/send_group_msg",e->{assertEquals("Bearer token",e.getRequestHeaders().getFirst("Authorization"));request.set(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] body="{\"retcode\":0,\"status\":\"ok\",\"data\":{\"message_id\":42}}".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        var result=new ChannelSender(props(),json).send("QQ","12345","[CQ:at,qq=all] literal","id");assertEquals(ChannelSender.State.ACCEPTED,result.state());assertEquals("42",result.platformId());assertTrue(request.get().contains("\"type\":\"text\""));
    }
    @Test void successHttpWithoutReceiptIsUnknown(){
        server.createContext("/api/bot/send",e->{byte[] body="{}".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        assertEquals(ChannelSender.State.UNKNOWN,new ChannelSender(props(),json).send("WEIXIN","group","text","id").state());
    }
    @Test void embeddingsHonorIndexesAndRejectMalformedVectors(){
        var body=new AtomicReference<>("{\"data\":[{\"index\":1,\"embedding\":[0,1,0]},{\"index\":0,\"embedding\":[1,0,0]}]}");
        server.createContext("/embeddings",e->{e.getRequestBody().readAllBytes();byte[] bytes=body.get().getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        var client=new EmbeddingClient(props(),json);var result=client.embed(List.of("a","b"));assertArrayEquals(new float[]{1,0,0},result.get(0));assertArrayEquals(new float[]{0,1,0},result.get(1));
        body.set("{\"data\":[{\"index\":0,\"embedding\":[0,0,0]}]}");assertThrows(IllegalStateException.class,()->client.embed(List.of("a")));
    }
}
