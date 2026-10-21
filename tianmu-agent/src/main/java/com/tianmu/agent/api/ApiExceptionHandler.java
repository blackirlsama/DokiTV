package com.tianmu.agent.api;

import com.tianmu.agent.core.AgentException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, String>> domain(AgentException e) {
        HttpStatus status = switch (e.code()) {
            case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "RUN_NOT_FOUND", "RESULT_NOT_FOUND", "SESSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "CLARIFICATION_CONFLICT", "STORAGE_FENCED" -> HttpStatus.CONFLICT;
            case "EVENTS_EXPIRED" -> HttpStatus.GONE;
            case "STORAGE_UNAVAILABLE", "OBJECT_STORE_UNAVAILABLE", "ARTIFACT_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Request validation failed"));
    }
}
