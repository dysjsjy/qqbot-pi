/**
 * [INPUT]: 依赖ManagementQueries、管理员会话与 CSRF。
 * [OUTPUT]: 提供认证身份、安全令牌与组合状态。
 * [POS]: 系统 HTTP 入口；不直接查询数据库。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.management.ManagementQueries;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class SystemController {
    private final ManagementQueries queries;

    public SystemController(ManagementQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @GetMapping("/me")
    public Map<String, String> me(Authentication auth) {
        return Map.of("username", auth.getName());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return queries.status();
    }
}
