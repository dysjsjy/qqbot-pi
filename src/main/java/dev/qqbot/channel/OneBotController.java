package dev.qqbot.channel;

import com.fasterxml.jackson.databind.*;
import dev.qqbot.BotProperties;
import dev.qqbot.message.*;
import dev.qqbot.persistence.Db;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** OneBot 11 HTTP POST events, message format must be array. */
@RestController
public class OneBotController {
    private final BotProperties props;private final ObjectMapper json;private final IngestService ingest;private final Validator validator;private final ConnectionMonitor connections;
    public OneBotController(BotProperties props,ObjectMapper json,IngestService ingest,Validator validator,ConnectionMonitor connections){this.props=props;this.json=json;this.ingest=ingest;this.validator=validator;this.connections=connections;}
    @PostMapping("/callbacks/qq/onebot")
    public Map<String,Object> receive(@RequestBody byte[] raw,@RequestHeader(value="Authorization",required=false)String authorization,
        @RequestHeader(value="X-Signature",required=false)String signature)throws Exception{
        var gateway=props.qq();if(!gateway.enabled())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!gateway.webhookSecret().isBlank()){
            var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(gateway.webhookSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
            String expected="sha1="+HexFormat.of().formatHex(mac.doFinal(raw));
            if(!equal(expected,signature))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }else if(!equal("Bearer "+gateway.token(),authorization))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        JsonNode event=json.readTree(raw);
        if(!gateway.accountId().equals(event.path("self_id").asText()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if("meta_event".equals(event.path("post_type").asText())&&"heartbeat".equals(event.path("meta_event_type").asText())){
            connections.heartbeat("QQ",gateway.accountId(),event.path("status").path("online").asBoolean(false)&&event.path("status").path("good").asBoolean(true));return Map.of("ok",true);
        }
        if(!"message".equals(event.path("post_type").asText())||!"group".equals(event.path("message_type").asText()))return Map.of("ignored",true);
        if(!event.path("message").isArray())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ONEBOT_MESSAGE_MUST_BE_ARRAY");
        StringBuilder text=new StringBuilder();boolean mention=false;
        for(var segment:event.path("message")){
            if("text".equals(segment.path("type").asText()))text.append(segment.path("data").path("text").asText());
            if("at".equals(segment.path("type").asText())&&gateway.accountId().equals(segment.path("data").path("qq").asText()))mention=true;
        }
        String sender=event.path("user_id").asText(),name=event.path("sender").path("card").asText("");
        if(name.isBlank())name=event.path("sender").path("nickname").asText(sender);
        var request=new GroupIngestRequest("QQ",gateway.accountId(),event.path("group_id").asText(),event.path("message_id").asText(),sender,name,
            Instant.ofEpochSecond(event.path("time").asLong()),text.toString(),mention,gateway.accountId().equals(sender),text.toString().isBlank()?"NON_TEXT":"TEXT");
        if(!validator.validate(request).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_ONEBOT_EVENT");
        var receipt=ingest.ingest(request);return Map.of("accepted",receipt.accepted(),"duplicate",receipt.duplicate());
    }
    private static boolean equal(String a,String b){return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}
