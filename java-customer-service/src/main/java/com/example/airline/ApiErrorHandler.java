package com.example.airline;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "请求格式或参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        LOG.error("Request failed ({})", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "服务处理失败，请稍后重试"));
    }
}
