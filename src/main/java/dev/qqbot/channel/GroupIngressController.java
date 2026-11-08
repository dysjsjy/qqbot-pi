package dev.qqbot.channel;

import dev.qqbot.BotProperties;
import dev.qqbot.message.GroupIngestRequest;
import dev.qqbot.message.IngestService;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ingress")
public class GroupIngressController {
    private final IngestService ingest;
    private final BotProperties properties;
    private final ConnectionMonitor connections;
    public GroupIngressController(IngestService ingest, BotProperties properties,ConnectionMonitor connections) {
        this.ingest=ingest;
        this.properties=properties;
        this.connections=connections;
    }

    public record Heartbeat(@jakarta.validation.constraints.NotBlank String accountId,boolean online){}
    @PostMapping("/{channel}/heartbeat") public Object heartbeat(@PathVariable String channel,@RequestHeader(value="X-Bot-Token",required=false)String token,@Valid @RequestBody Heartbeat body){
        String platform=channel.equalsIgnoreCase("qq")?"QQ":channel.equalsIgnoreCase("weixin")?"WEIXIN":"";
        if(platform.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var gateway=properties.gateway(platform);
        if(!gateway.enabled()||gateway.token().isBlank()||token==null||!MessageDigest.isEqual(gateway.token().getBytes(java.nio.charset.StandardCharsets.UTF_8),token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!gateway.accountId().equals(body.accountId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        connections.heartbeat(platform,body.accountId(),body.online());return Map.of("ok",true);
    }

    @PostMapping("/{channel}")
    public Object receive(@PathVariable String channel,
                          @RequestHeader(value="X-Bot-Token", required=false) String token,
                          @Valid @RequestBody GroupIngestRequest request) throws Exception {
        String platform=channel.equalsIgnoreCase("qq") ? "QQ" : channel.equalsIgnoreCase("weixin") ? "WEIXIN" : "";
        if(platform.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        BotProperties.Gateway gateway=properties.gateway(platform);
        if(!gateway.enabled() || gateway.token().isBlank() || token==null || !MessageDigest.isEqual(gateway.token().getBytes(),token.getBytes())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if(!platform.equalsIgnoreCase(request.channel()) || !gateway.accountId().equals(request.accountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var receipt=ingest.ingest(request);
        return Map.of("accepted",receipt.accepted(),"duplicate",receipt.duplicate(),
                "messageId",receipt.messageId()==null?"":receipt.messageId().toString());
    }
}
