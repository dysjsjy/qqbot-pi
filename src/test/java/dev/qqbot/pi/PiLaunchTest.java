/**
 * [INPUT]: 使用固定 CLI 配置、合成环境和临时检查点。
 * [OUTPUT]: 验证命令隔离、环境白名单、配置格式和历史会话复制。
 * [POS]: Pi 启动材料回归；不启动 Node 进程。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import dev.qqbot.pi.PiLaunch.Workspace;
import dev.qqbot.pi.PiRuntime.Call;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiLaunchTest {
    private static final String API_KEY_ENV = "PI_LAUNCH_TEST_KEY";
    private static final String TEST_KEY = "test-key";
    private static final String MODEL_ID = "test-model";
    private static final String PROVIDER = "test-provider";
    private static final int CONTEXT_TOKENS = 8192;
    private static final String SYSTEM_PROMPT = "限定为本地测试";
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path state;

    @Test
    void selectEnvironment_filtersParentAndUsesExplicitConfiguration() {
        Workspace workspace = Workspace.at(state.resolve("run"));
        var inherited =
                Map.of("PATH", "node-path", "HOME", "parent-home", API_KEY_ENV, "parent-key");

        Map<String, String> selected = launcher().selectEnvironment(inherited, workspace, 900);

        assertEquals("node-path", selected.get("PATH"));
        assertEquals(workspace.root().toString(), selected.get("HOME"));
        assertEquals(workspace.agentHome().toString(), selected.get("PI_CODING_AGENT_DIR"));
        assertEquals("900", selected.get("QQBOT_PI_MAX_OUTPUT_TOKENS"));
        assertEquals(TEST_KEY, selected.get(API_KEY_ENV));
        assertFalse(selected.containsValue("parent-key"));
        assertFalse(selected.containsValue("parent-home"));
    }

    @Test
    void buildCommand_disablesSessionsAndToolsForEphemeralRuns() {
        var launcher = launcher();
        var launch = launcher.planLaunch(call(PiSession.ephemeral()));

        var command = launcher.buildCommand(Workspace.at(state.resolve("run")), launch);

        assertTrue(command.contains("--no-session"));
        assertTrue(command.contains("--no-tools"));
        assertTrue(command.contains("--no-extensions"));
        assertFalse(command.contains("--fork"));
    }

    @Test
    void planLaunch_rejectsPreviousFileOutsideCheckpointDirectory() throws Exception {
        Path foreign = Files.writeString(state.resolve("outside.jsonl"), "outside");
        Call call = call(new PiSession(true, foreign.toString()));

        var failure = assertThrows(IllegalStateException.class, () -> launcher().planLaunch(call));

        assertEquals("CHECKPOINT_INVALID", failure.getMessage());
        assertFalse(Files.exists(state.resolve("checkpoints")));
    }

    @Test
    void prepareWorkspace_forksAnIndependentCheckpointAndWritesTypedConfiguration()
            throws Exception {
        Path parent = Files.createDirectory(state.resolve("checkpoints"));
        Path previous = Files.writeString(parent.resolve("original.jsonl"), "historical");
        Workspace workspace = Workspace.at(Files.createDirectory(state.resolve("run")));
        var launcher = launcher();
        var launch = launcher.planLaunch(call(new PiSession(true, previous.toString())));

        ProcessBuilder builder = launcher.prepareWorkspace(workspace, launch, SYSTEM_PROMPT);

        assertTrue(builder.command().contains("--fork"));
        assertTrue(builder.command().contains(workspace.restoredCheckpoint().toString()));
        assertEquals(Files.readString(previous), Files.readString(workspace.restoredCheckpoint()));
        Files.writeString(workspace.restoredCheckpoint(), "new");
        assertEquals("historical", Files.readString(previous));
        assertEquals(SYSTEM_PROMPT, Files.readString(workspace.systemPrompt()));
        assertModelsConfiguration(workspace);
    }

    private void assertModelsConfiguration(Workspace workspace) throws Exception {
        var models = workspace.agentHome().resolve("models.json");
        var provider = json.readTree(models.toFile()).at("/providers/" + PROVIDER);
        assertEquals("$" + API_KEY_ENV, provider.path("apiKey").asText());
        assertEquals(MODEL_ID, provider.at("/models/0/id").asText());
        assertEquals(CONTEXT_TOKENS, provider.at("/models/0/contextWindow").asInt());
        assertFalse(provider.at("/models/0/compat/supportsDeveloperRole").asBoolean(true));
    }

    private PiLaunch launcher() {
        var settings =
                new BotProperties.Pi(
                        "node",
                        "fixture.cjs",
                        PROVIDER,
                        MODEL_ID,
                        API_KEY_ENV,
                        TEST_KEY,
                        10,
                        "http://localhost");
        return new PiLaunch(settings, CONTEXT_TOKENS, json, new PiCheckpoints(state));
    }

    private static Call call(PiSession session) {
        return Call.nativeSession(UUID.randomUUID(), "test", session, 10);
    }
}
