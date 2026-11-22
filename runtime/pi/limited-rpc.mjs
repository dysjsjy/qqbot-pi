import { readFileSync } from "node:fs";
import { ModelRuntime } from "./node_modules/@earendil-works/pi-coding-agent/dist/core/model-runtime.js";
import { setupCli } from "./node_modules/@earendil-works/pi-coding-agent/dist/cli/setup.js";
import { main } from "./node_modules/@earendil-works/pi-coding-agent/dist/main.js";

const packageInfo = JSON.parse(readFileSync(new URL("./node_modules/@earendil-works/pi-coding-agent/package.json", import.meta.url), "utf8"));
const outputLimit = Number(process.env.QQBOT_PI_MAX_OUTPUT_TOKENS);
const systemArgument = process.argv.indexOf("--system-prompt");
if (packageInfo.version !== "1.5.0" || !Number.isSafeInteger(outputLimit) || outputLimit < 1 || systemArgument < 0 || !process.argv[systemArgument + 1]) {
    throw new Error("PI_OUTPUT_LIMIT_UNSUPPORTED");
}
const systemPrompt = readFileSync(process.argv[systemArgument + 1], "utf8");
const originalStreamSimple = ModelRuntime.prototype.streamSimple;
ModelRuntime.prototype.streamSimple = function (model, context, options) {
    const requested = options?.maxTokens;
    const maxTokens = Number.isSafeInteger(requested) && requested > 0 ? Math.min(requested, outputLimit) : outputLimit;
    const messages = [{ role: "system", content: systemPrompt, timestamp: 0 }, ...context.messages.filter(message => message.role !== "system")];
    return originalStreamSimple.call(this, model, { messages }, { ...options, maxTokens });
};
const rawWrite = process.stdout.write.bind(process.stdout);
rawWrite(JSON.stringify({ type: "qqbot_runtime_ready", maxOutputTokens: outputLimit }) + "\n");
const reportUsage = () => {
    try {
        const cpu = process.cpuUsage(), memory = process.memoryUsage();
        rawWrite(JSON.stringify({ type: "qqbot_runtime_usage", cpuUserUs: cpu.user, cpuSystemUs: cpu.system, rssBytes: memory.rss, heapUsedBytes: memory.heapUsed }) + "\n");
    } catch {}
};
reportUsage();
setInterval(reportUsage, 500).unref();
setupCli();
await main(process.argv.slice(2));
