package cn.edu.approval.application;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Specific safe field failures must precede the identity advice's catch-all handler. */
@RestControllerAdvice
@Profile("db & !migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FixedBusinessErrors {
  @ExceptionHandler(FixedBusinessFailure.class)
  public ResponseEntity<Map<String, Object>> invalid(
      FixedBusinessFailure failure, HttpServletResponse response) {
    String requestId = response.getHeader("X-Request-Id");
    if (requestId == null) {
      requestId = UUID.randomUUID().toString();
      response.setHeader("X-Request-Id", requestId);
    }
    return ResponseEntity.status(failure.status())
        .header("Cache-Control", "no-store")
        .body(
            Map.of(
                "code",
                failure.code(),
                "message",
                "Fixed business validation rejected",
                "fieldErrors",
                failure.fieldErrors(),
                "requestId",
                requestId,
                "retryable",
                failure.status() == 503));
  }
}
