package dev.qqbot;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import dev.qqbot.channel.OfficialQqEvents;
import dev.qqbot.message.*;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfficialQqMentionTest {
    final ObjectMapper json = new ObjectMapper();

    void check(
            String type,
            String content,
            List<Map<String, Object>> mentions,
            String configuredId,
            JsonNode readyUser,
            boolean expectedMention,
            String expectedText)
            throws Exception {
        var qq =
                new BotProperties.Qq(
                        true,
                        new BotProperties.OfficialQq(
                                "app-id",
                                "secret",
                                "http://unused",
                                "http://unused",
                                false,
                                "websocket",
                                configuredId));
        var props =
                new BotProperties(
                        "admin",
                        "",
                        Path.of("target"),
                        false,
                        null,
                        null,
                        qq,
                        new BotProperties.Gateway(false, "", "", "", ""),
                        new BotProperties.Rag(false));
        var ingest = mock(IngestService.class);
        when(ingest.ingest(any()))
                .thenReturn(new IngestService.Receipt(null, null, false, true, false));
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var controller = new OfficialQqEvents(props, ingest, validators.getValidator());
            if (readyUser != null) {
                controller.identifyBot(readyUser);
            }
            var data =
                    Map.of(
                            "id",
                            "source",
                            "group_openid",
                            "group",
                            "author",
                            Map.of("member_openid", "member"),
                            "content",
                            content,
                            "mentions",
                            mentions,
                            "timestamp",
                            Instant.now().toString());
            controller.dispatch(
                    json.valueToTree(Map.of("op", 0, "t", type, "d", data)), "websocket");
            verify(ingest)
                    .ingest(
                            argThat(
                                    r ->
                                            r.mentionsBot() == expectedMention
                                                    && r.text().equals(expectedText)));
        }
    }

    @Test
    void fullMessageRecognizesConfiguredSelfAndExtractsCommand() throws Exception {
        check(
                "GROUP_MESSAGE_CREATE",
                " <@!bot-openid> /help",
                List.of(),
                "bot-openid",
                null,
                true,
                "/help");
    }

    @Test
    void selfMentionInMiddleRemovesOnlySelfTokens() throws Exception {
        check(
                "GROUP_MESSAGE_CREATE",
                "<@other> hello <@bot-openid> world <@!bot-openid>",
                List.of(),
                "bot-openid",
                null,
                true,
                "<@other> hello  world");
    }

    @Test
    void otherBotMemberAllAndPlainAtAreNotSelfMentions() throws Exception {
        for (String text :
                List.of("<@other> hello", "<@all> hello", "@bot-openid hello", "hello")) {
            check(
                    "GROUP_MESSAGE_CREATE",
                    text,
                    List.of(Map.of("id", "other", "bot", true)),
                    "bot-openid",
                    null,
                    false,
                    text);
        }
    }

    @Test
    void authenticatedReadyIdentityAndMentionMetadataAreRecognized() throws Exception {
        var user = json.readTree("{\"id\":\"gateway-id\",\"member_openid\":\"group-bot-id\"}");
        check("GROUP_MESSAGE_CREATE", "<@group-bot-id> hello", List.of(), "", user, true, "hello");
        check(
                "GROUP_MESSAGE_CREATE",
                "hello",
                List.of(Map.of("id", "gateway-id")),
                "",
                user,
                true,
                "hello");
    }

    @Test
    void atEventAlreadyStrippedByPlatformPreservesMentionOfAnotherMember() throws Exception {
        check(
                "GROUP_AT_MESSAGE_CREATE",
                "<@other> hello",
                List.of(),
                "bot-openid",
                null,
                true,
                "<@other> hello");
        check("GROUP_AT_MESSAGE_CREATE", " /help", List.of(), "", null, true, " /help");
    }
}
