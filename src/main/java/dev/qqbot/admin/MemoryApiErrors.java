/**
 * [INPUT]: 依赖记忆及画像 HTTP 接口的业务与请求错误。
 * [OUTPUT]: 返回稳定业务错误或不含底层细节的通用错误。
 * [POS]: 记忆专属管理接口的错误转换边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(
        assignableTypes = {
            MemorySettingsController.class,
            MemoryUsageController.class,
            MemoryArchiveController.class,
            MemberProfileAdminController.class
        })
public class MemoryApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        String message = failure.getReason();
        return ResponseEntity.status(failure.getStatusCode())
                .body(Map.of("message", message == null ? "记忆记录不可用，请刷新后重试" : message));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数无效，请检查输入内容"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "记忆设置已变更，请刷新后重试"));
    }
}
