/**
 * [INPUT]: 依赖管理控制器的业务、校验与数据库异常。
 * [OUTPUT]: 提供中文 HTTP 错误，不泄漏底层异常内容。
 * [POS]: 常规管理接口的异常边界；记忆专属接口使用 MemoryApiErrors。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Return deliberate admin validation messages, never SQL, request bodies or exception details. */
@RestControllerAdvice(
        assignableTypes = {
            SystemController.class,
            GroupAdminController.class,
            MemoryAdminController.class,
            ScheduleAdminController.class,
            ActivityAdminController.class,
            RunAdminController.class,
            LogController.class,
            ReplyPolicyController.class
        })
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        String message = failure.getReason();
        if (message == null) {
            message =
                    switch (failure.getStatusCode().value()) {
                        case 400 -> "请求参数无效，请检查输入内容";
                        case 401 -> "登录已失效，请重新登录";
                        case 403 -> "无权执行此操作，请检查登录状态";
                        case 404 -> "请求的记录不存在，可能已被删除，请刷新列表";
                        case 409 -> "记录状态已变更，请刷新后重试";
                        default -> "服务暂时无法处理请求（" + failure.getStatusCode().value() + "），请稍后重试";
                    };
        }
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "数据冲突，请刷新群列表后重试"));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数无效，请检查输入内容"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "依赖服务暂不可用，请稍后重试"));
    }
}
