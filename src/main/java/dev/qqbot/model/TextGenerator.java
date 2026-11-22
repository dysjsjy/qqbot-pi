/**
 * [INPUT]: 接收带作用域与限额的生成请求、计量观察者或运行 ID。
 * [OUTPUT]: 提供生成结果和取消入口。
 * [POS]: 业务生成端口；原生会话调用留在供应商适配器，不绕开计量。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.model;

import java.util.UUID;

public interface TextGenerator {
    record Result(String text, String checkpoint, ModelUsage usage) {
        public Result(String text, String checkpoint) {
            this(text, checkpoint, ModelUsage.unknown());
        }
    }

    /** 生成单次请求的结果，并通知观察者分发状态和用量。 */
    Result generate(GenerationRequest request, GenerationObserver observer);

    /** 取消指定运行；未知运行不改变状态。 */
    void cancel(UUID runId);
}
