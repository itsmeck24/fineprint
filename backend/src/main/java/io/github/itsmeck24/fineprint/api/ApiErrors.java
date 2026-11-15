package io.github.itsmeck24.fineprint.api;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

// errors this code raises on purpose carry a message written for the user, so those are returned.
// anything else keeps spring's default body, which does not include exception text.
@RestControllerAdvice
@ConditionalOnWebApplication
public class ApiErrors {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        String reason = e.getReason() == null ? "" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("status", e.getStatusCode().value(), "message", reason));
    }
}
