package cn.edu.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

final class ApiErrors {
  private ApiErrors() {}

  static void write(
      HttpServletResponse response, ObjectMapper mapper, int status, String code, int retryAfter)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    String requestId = response.getHeader("X-Request-Id");
    if (requestId == null) {
      requestId = UUID.randomUUID().toString();
      response.setHeader("X-Request-Id", requestId);
    }
    if (retryAfter > 0) response.setHeader("Retry-After", Integer.toString(retryAfter));
    mapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "code",
            code,
            "message",
            "Request could not be completed",
            "fieldErrors",
            Map.of(),
            "requestId",
            requestId,
            "retryable",
            status == 503));
  }
}
