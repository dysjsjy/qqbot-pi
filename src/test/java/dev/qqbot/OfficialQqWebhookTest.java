package dev.qqbot;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.qqbot.channel.*;
import dev.qqbot.message.IngestService;
import dev.qqbot.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({OfficialQqController.class, GroupIngressController.class})
@Import({SecurityConfig.class, IngressSizeLimit.class, OfficialQqEvents.class})
@TestPropertySource(
        properties = {
            "bot.qq.enabled=true",
            "bot.qq.official.app-id=app-id",
            "bot.qq.official.app-secret=test-secret",
            "bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
        })
class OfficialQqWebhookTest {
    @Autowired MockMvc mvc;
    @MockitoBean IngestService ingest;
    @MockitoBean ConnectionMonitor connections;
    OfficialQqSignature signatures = new OfficialQqSignature("test-secret");

    String event() {
        return "{\"op\":0,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":{\"id\":\"source\",\"group_openid\":\"group-openid\",\"author\":{\"member_openid\":\"member-openid\"},\"content\":\" /help\",\"timestamp\":\""
                + Instant.now()
                + "\"}}";
    }

    @Test
    void challengeUsesPlatformTimestampAndPlainToken() throws Exception {
        String body =
                "{\"op\":13,\"d\":{\"plain_token\":\"challenge\",\"event_ts\":\"1725442341\"}}";
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plain_token").value("challenge"))
                .andExpect(
                        jsonPath("$.signature")
                                .value(
                                        signatures.sign(
                                                "1725442341",
                                                "challenge".getBytes(StandardCharsets.UTF_8))));
        verifyNoInteractions(ingest);
    }

    @Test
    void authenticatedMessageUsesOfficialOpenidsAndAck() throws Exception {
        when(ingest.ingest(any()))
                .thenReturn(new IngestService.Receipt(null, null, false, true, true));
        String body = event(), ts = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .header("X-Signature-Timestamp", ts)
                                .header(
                                        "X-Signature-Ed25519",
                                        signatures.sign(ts, body.getBytes(StandardCharsets.UTF_8)))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.op").value(12));
        verify(ingest)
                .ingest(
                        argThat(
                                r ->
                                        r.mentionsBot()
                                                && r.accountId().equals("app-id")
                                                && r.groupId().equals("group-openid")
                                                && r.senderId().equals("member-openid")
                                                && r.text().equals(" /help")));
    }

    @Test
    void missingOrTamperedSignatureAndWrongAppIdAreRejected() throws Exception {
        String body = event();
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "other-app")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .header("X-Signature-Timestamp", "123")
                                .header(
                                        "X-Signature-Ed25519",
                                        signatures.sign(
                                                "123", "{}".getBytes(StandardCharsets.UTF_8)))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ingest);
    }

    @Test
    void authenticatedPrivateMessagesAcceptUserOpenidAndLegacyAuthorId() throws Exception {
        when(ingest.ingest(any()))
                .thenReturn(new IngestService.Receipt(null, null, false, true, false));
        for (String field : java.util.List.of("user_openid", "id")) {
            String body =
                    "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"source-"
                            + field
                            + "\",\"author\":{\""
                            + field
                            + "\":\"private-"
                            + field
                            + "\"},\"content\":\"你好\",\"timestamp\":\""
                            + Instant.now()
                            + "\"}}";
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            mvc.perform(
                            post("/callbacks/qq/official")
                                    .header("X-Bot-Appid", "app-id")
                                    .header("X-Signature-Timestamp", timestamp)
                                    .header(
                                            "X-Signature-Ed25519",
                                            signatures.sign(
                                                    timestamp,
                                                    body.getBytes(StandardCharsets.UTF_8)))
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.op").value(12));
            verify(ingest)
                    .ingest(
                            argThat(
                                    request ->
                                            request.privateChat()
                                                    && request.mentionsBot()
                                                    && request.groupId().equals("private-" + field)
                                                    && request.senderId().equals("private-" + field)
                                                    && request.text().equals("你好")));
        }
    }

    @Test
    void privateMessageCannotBypassWebhookSignature() throws Exception {
        String body =
                "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"source\",\"author\":{\"user_openid\":\"private-user\"},\"content\":\"你好\",\"timestamp\":\""
                        + Instant.now()
                        + "\"}}";
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ingest);
    }

    @Test
    void removedPersonalQqAndGenericQqIngressReturnNotFound() throws Exception {
        mvc.perform(post("/callbacks/qq/onebot").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/ingress/qq/heartbeat")
                                .header("X-Bot-Token", "legacy")
                                .contentType("application/json")
                                .content("{\"accountId\":\"app-id\",\"online\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/qq/official/events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ingest);
    }

    @Test
    void oversizedEventRejectedBeforeJsonOrSignatureHandling() throws Exception {
        mvc.perform(
                        post("/callbacks/qq/official")
                                .contentType("application/json")
                                .content("x".repeat(131073)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(ingest);
    }

    @Test
    void challengeCannotBeUsedToSignAnArbitraryEvent() throws Exception {
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "app-id")
                                .contentType("application/json")
                                .content(
                                        "{\"op\":13,\"d\":{\"plain_token\":\"{\\\"op\\\":0}\",\"event_ts\":\"1725442341\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingest);
    }
}
