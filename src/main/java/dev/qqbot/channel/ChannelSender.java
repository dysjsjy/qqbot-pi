package dev.qqbot.channel;

import dev.qqbot.BotProperties;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ChannelSender {
    public enum State { ACCEPTED, RETRYABLE, FAILED, UNKNOWN }
    public record Delivery(State state,String platformId,String code){}
    private final BotProperties properties;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public ChannelSender(BotProperties properties,ObjectMapper json){this.properties=properties;this.json=json;}
    public Delivery send(String channel,String groupId,String text,String requestId) {
        var gateway=properties.gateway(channel);
        if(!gateway.enabled())return new Delivery(State.FAILED,null,"GATEWAY_DISABLED");
        try {
            boolean qq=channel.equals("QQ");
            Object payload=qq?Map.of("group_id",Long.parseLong(groupId),"message",List.of(Map.of("type","text","data",Map.of("text",text)))):Map.of("groupId",groupId,"text",text,"requestId",requestId);
            String endpoint=gateway.baseUrl().replaceAll("/+$","")+(qq?"/send_group_msg":"/api/bot/send");
            var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+gateway.token()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==429)return new Delivery(State.RETRYABLE,null,"RATE_LIMITED");
            if(response.statusCode()==401||response.statusCode()==403)return new Delivery(State.FAILED,null,"GATEWAY_AUTH_FAILED");
            if(response.statusCode()<200||response.statusCode()>=300)return new Delivery(State.UNKNOWN,null,"HTTP_RESULT_UNKNOWN");
            var body=json.readTree(response.body());
            if(qq){
                if(body.path("retcode").asInt(-1)!=0||!"ok".equals(body.path("status").asText()))return new Delivery(State.FAILED,null,"ONEBOT_REJECTED");
                String id=body.path("data").path("message_id").asText("");
                return id.isBlank()?new Delivery(State.UNKNOWN,null,"MISSING_RECEIPT"):new Delivery(State.ACCEPTED,id,null);
            }
            return switch(body.path("status").asText()){
                case "accepted" -> body.path("messageId").asText("").isBlank()?new Delivery(State.UNKNOWN,null,"MISSING_RECEIPT"):new Delivery(State.ACCEPTED,body.path("messageId").asText(),null);
                case "rejected" -> new Delivery(State.FAILED,null,"BRIDGE_REJECTED");
                case "retryable" -> new Delivery(State.RETRYABLE,null,"BRIDGE_BUSY");
                default -> new Delivery(State.UNKNOWN,null,"BRIDGE_RESULT_UNKNOWN");
            };
        }catch(ConnectException|HttpConnectTimeoutException e){return new Delivery(State.RETRYABLE,null,"CONNECT_FAILED");}
        catch(InterruptedException e){Thread.currentThread().interrupt();return new Delivery(State.UNKNOWN,null,"SEND_INTERRUPTED");}
        catch(Exception e){return new Delivery(State.UNKNOWN,null,"SEND_RESULT_UNKNOWN");}
    }
}
