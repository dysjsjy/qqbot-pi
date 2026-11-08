package dev.qqbot.admin;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("message","请求参数无效，请检查群标识、时间和字段长度"));}
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("message","记录已存在或数据已变更，请刷新"));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","依赖服务暂不可用，请稍后重试"));}
}
