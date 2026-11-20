package dev.dineshrama.relay;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    var b = ResponseEntity.status(e.status);
    if (e.status == 429) b.header("Retry-After", "2");
    return b.body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST", "message", "Request body or parameter is invalid"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "code",
                "SERVICE_UNAVAILABLE",
                "message",
                "The operation could not be completed. Retry or inspect the server logs."));
  }
}
