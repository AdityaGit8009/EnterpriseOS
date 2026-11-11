package com.enterpriseos;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;

@RestControllerAdvice
public class ApiExceptionAdvice {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> application(ApiException ex) {
        String code = switch (ex.status()) {
            case 400 -> "VALIDATION_FAILED"; case 401 -> "UNAUTHENTICATED";
            case 403 -> "ACCESS_DENIED"; case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT"; case 429 -> "RATE_LIMITED"; default -> "REQUEST_FAILED";
        };
        return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage(), "code", code));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> constraint(DataIntegrityViolationException ex) {
        return ResponseEntity.status(409).body(Map.of("message", "A record conflicts with existing data or an invalid reference.", "code", "CONFLICT"));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "Check the request fields and format.", "code", "VALIDATION_FAILED"));
    }
}
