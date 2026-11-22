/**
 * [INPUT]: 依赖部署配置、模型窗口、Pi 调用和原生检查点边界。
 * [OUTPUT]: 校验启动输入，准备隔离工作目录中的配置、命令及子进程环境。
 * [POS]: 子进程的启动材料边界；不持有运行槽、不调用 start。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import dev.qqbot.pi.PiRuntime.Call;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PiLaunch {
    private static final String PINNED_CLI =
            "runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js";
    private static final String OUTPUT_LIMIT_ADAPTER = "runtime/pi/limited-rpc.mjs";
    private static final int NATIVE_MODEL_OUTPUT_TOKENS = 4_096;
    private static final String HOME = "HOME";
    private static final String USER_PROFILE = "USERPROFILE";
    private static final String AGENT_DIRECTORY = "PI_CODING_AGENT_DIR";
    private static final String OUTPUT_LIMIT = "QQBOT_PI_MAX_OUTPUT_TOKENS";
    private static final List<String> PASSTHROUGH_ENVIRONMENT =
            List.of(
                    "PATH",
                    "Path",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "WINDIR",
                    "TEMP",
                    "TMP",
                    "COMSPEC",
                    "PATHEXT",
                    "HTTPS_PROXY",
                    "HTTP_PROXY",
                    "NO_PROXY");
    private static final List<String> RESTRICTED_RPC =
            List.of(
                    "--mode",
                    "rpc",
                    "--no-tools",
                    "--no-extensions",
                    "--no-mcp",
                    "--no-skills",
                    "--no-prompt-templates",
                    "--no-context-files",
                    "--no-approve");

    private final BotProperties.Pi config;
    private final int contextWindow;
    private final ObjectMapper json;
    private final PiCheckpoints checkpoints;

    PiLaunch(
            BotProperties.Pi config,
            int contextWindow,
            ObjectMapper json,
            PiCheckpoints checkpoints) {
        this.config = config;
        this.contextWindow = contextWindow;
        this.json = json;
        this.checkpoints = checkpoints;
    }

    Plan planLaunch(Call call) {
        Path cli = resolveCli(call.limits());
        Path checkpoint = checkpoints.findPrevious(call.session()).orElse(null);
        return new Plan(
                cli, checkpoint, call.session().isPersistent(), call.limits().outputTokens());
    }

    ProcessBuilder prepareWorkspace(Workspace workspace, Plan launch, String systemPrompt)
            throws IOException {
        // SIDE EFFECT: 进程启动前写入材料；失败路径由运行登记清理工作目录。
        writeAgentConfiguration(workspace.agentHome());
        Files.writeString(workspace.systemPrompt(), systemPrompt, StandardCharsets.UTF_8);
        restoreCheckpoint(workspace, launch);
        var builder = new ProcessBuilder(buildCommand(workspace, launch));
        builder.directory(workspace.root().toFile());
        applyEnvironment(builder, workspace, launch.outputTokens());
        return builder;
    }

    private Path resolveCli(Call.Limits limits) {
        Path configured = normalized(config.cli());
        if (!limits.hasOutputLimit() || !configured.equals(normalized(PINNED_CLI))) {
            return configured;
        }
        Path adapter = normalized(OUTPUT_LIMIT_ADAPTER);
        if (!Files.isRegularFile(adapter)) {
            throw new IllegalStateException("PI_OUTPUT_LIMIT_UNSUPPORTED");
        }
        return adapter;
    }

    private static Path normalized(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    private void restoreCheckpoint(Workspace workspace, Plan launch) throws IOException {
        if (launch.previous() != null) {
            checkpoints.restore(launch.previous(), workspace.restoredCheckpoint());
        }
    }

    List<String> buildCommand(Workspace workspace, Plan launch) {
        var command = new ArrayList<String>();
        command.add(config.node());
        command.add(launch.cli().toString());
        command.addAll(RESTRICTED_RPC);
        command.addAll(
                List.of(
                        "--system-prompt",
                        workspace.systemPrompt().toString(),
                        "--provider",
                        config.provider(),
                        "--model",
                        config.model(),
                        "--session-dir",
                        workspace.root().toString()));
        if (!launch.isPersistent()) {
            command.add("--no-session");
        } else if (launch.previous() != null) {
            command.addAll(List.of("--fork", workspace.restoredCheckpoint().toString()));
        }
        return List.copyOf(command);
    }

    private void applyEnvironment(
            ProcessBuilder builder, Workspace workspace, Integer outputTokens) {
        Map<String, String> target = builder.environment();
        Map<String, String> allowed =
                selectEnvironment(Map.copyOf(target), workspace, outputTokens);
        target.clear();
        target.putAll(allowed);
    }

    Map<String, String> selectEnvironment(
            Map<String, String> inherited, Workspace workspace, Integer outputTokens) {
        var allowed = new LinkedHashMap<String, String>();
        for (String key : PASSTHROUGH_ENVIRONMENT) {
            if (inherited.containsKey(key)) {
                allowed.put(key, inherited.get(key));
            }
        }
        allowed.put(HOME, workspace.root().toString());
        allowed.put(USER_PROFILE, workspace.root().toString());
        allowed.put(AGENT_DIRECTORY, workspace.agentHome().toString());
        allowed.put("PI_OFFLINE", "true");
        if (outputTokens != null) {
            allowed.put(OUTPUT_LIMIT, outputTokens.toString());
        }
        if (!config.apiKey().isBlank()) {
            allowed.put(config.keyEnv(), config.apiKey());
        }
        return Collections.unmodifiableMap(allowed);
    }

    private void writeAgentConfiguration(Path agentHome) throws IOException {
        Files.createDirectories(agentHome);
        var settings = new Settings(new Retry(false, new ProviderRetry(0)), new Compaction(false));
        writeJson(agentHome.resolve("settings.json"), settings);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            writeJson(agentHome.resolve("models.json"), providers());
        }
    }

    private Providers providers() {
        var model =
                new Model(
                        config.model(),
                        contextWindow,
                        NATIVE_MODEL_OUTPUT_TOKENS,
                        new Compatibility(false));
        var provider =
                new Provider(
                        config.baseUrl(),
                        "openai-completions",
                        "$" + config.keyEnv(),
                        List.of(model));
        return new Providers(Map.of(config.provider(), provider));
    }

    private void writeJson(Path file, Object payload) throws IOException {
        Files.writeString(file, json.writeValueAsString(payload));
    }

    record Plan(Path cli, Path previous, boolean isPersistent, Integer outputTokens) {}

    record Workspace(Path root, Path agentHome, Path systemPrompt) {
        static Workspace at(Path root) {
            return new Workspace(root, root.resolve("agent"), root.resolve("system.txt"));
        }

        Path restoredCheckpoint() {
            return root.resolve("restored.jsonl");
        }
    }

    private record Settings(Retry retry, Compaction compaction) {}

    private record Retry(boolean enabled, ProviderRetry provider) {}

    private record ProviderRetry(int maxRetries) {}

    private record Compaction(boolean enabled) {}

    private record Providers(Map<String, Provider> providers) {}

    private record Provider(String baseUrl, String api, String apiKey, List<Model> models) {}

    private record Model(String id, int contextWindow, int maxTokens, Compatibility compat) {}

    private record Compatibility(boolean supportsDeveloperRole) {}
}
