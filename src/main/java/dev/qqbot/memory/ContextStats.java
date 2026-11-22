/**
 * [INPUT]: 接收预算组装后的层计数与选择结果。
 * [OUTPUT]: 提供分层估算；无注入时画像层计数为零。
 * [POS]: 诊断契约，仅记录标识和用量，不保存上下文正文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextStats(
        int estimatedInputTokens,
        Map<String, Integer> layerTokens,
        List<String> selectedKeys,
        List<String> droppedKeys,
        List<String> reasons,
        String manualHash) {
    public ContextStats {
        var layers = new LinkedHashMap<>(layerTokens);
        layers.putIfAbsent("profile", 0);
        layerTokens = Map.copyOf(layers);
        selectedKeys = List.copyOf(selectedKeys);
        droppedKeys = List.copyOf(droppedKeys);
        reasons = List.copyOf(reasons);
    }
}
