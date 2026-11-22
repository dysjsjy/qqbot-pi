/**
 * [INPUT]: 依赖官方 QQ 长连接的诊断快照。
 * [OUTPUT]: 向管理员返回连接状态。
 * [POS]: HTTP 只读边界由长连接组件提供状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import org.springframework.web.bind.annotation.*;

@RestController
public class OfficialQqConnectionController {
    private final OfficialQqWebSocket gateway;

    public OfficialQqConnectionController(OfficialQqWebSocket gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/api/admin/qq/official/connection")
    public OfficialQqWebSocket.Status connection() {
        return gateway.status();
    }
}
