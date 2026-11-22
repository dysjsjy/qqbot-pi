/**
 * [INPUT]: 接收 QQ HTTPS 回调和管理员诊断请求。
 * [OUTPUT]: 返回 QQ 回调确认和近期事件诊断。
 * [POS]: HTTP 边界先验签，再把事件交给共享入站服务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import com.fasterxml.jackson.databind.*;
import dev.qqbot.BotProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated QQ Open Platform HTTPS webhook. */
@RestController
public class OfficialQqController {
    private final BotProperties props;
    private final ObjectMapper json;
    private final OfficialQqEvents events;
    private final OfficialQqSignature signatures;

    public OfficialQqController(BotProperties props, ObjectMapper json, OfficialQqEvents events) {
        this.props = props;
        this.json = json;
        this.events = events;
        signatures =
                props.qq().enabled()
                        ? new OfficialQqSignature(props.qq().official().appSecret())
                        : null;
    }

    @GetMapping("/api/admin/qq/official/events")
    public List<OfficialQqEvents.RecentEvent> events() {
        return events.events();
    }

    @PostMapping("/callbacks/qq/official")
    public Map<String, Object> receive(
            @RequestBody byte[] raw,
            @RequestHeader(value = "X-Bot-Appid", required = false) String appId,
            @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature)
            throws Exception {
        if (signatures == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!props.qq().accountId().equals(appId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        JsonNode event;
        try {
            event = json.readTree(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QQ_EVENT");
        }
        if (event == null || !event.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QQ_EVENT");
        }
        var data = event.path("d");
        if (event.path("op").asInt(-1) == 13) {
            String plain = data.path("plain_token").asText(""),
                    ts = data.path("event_ts").asText("");
            // Challenge tokens are opaque tokens, never arbitrary JSON. Do not expose a
            // signing oracle that could sign an attacker-supplied ordinary event body.
            if (!plain.matches("[A-Za-z0-9_+/=-]{1,4096}") || !ts.matches("[0-9]{1,20}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            return Map.of(
                    "plain_token",
                    plain,
                    "signature",
                    signatures.sign(ts, plain.getBytes(StandardCharsets.UTF_8)));
        }
        if (!signatures.verify(timestamp, raw, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        // Return QQ's explicit acknowledgement only after the ingestion transaction has committed.
        events.dispatch(event, "webhook");
        return Map.of("op", 12);
    }
}
