package cn.edu.approval.security;

import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Identity response contract; the existing skeleton health endpoint is deliberately unchanged. */
@RestControllerAdvice(
    basePackages = {
      "cn.edu.approval.security",
      "cn.edu.approval.organization",
      "cn.edu.approval.application",
      "cn.edu.approval.file"
    })
@Profile("db & !migrate")
public class IdentityResponses implements ResponseBodyAdvice<Object> {
  private final Clock clock;

  public IdentityResponses(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    if (method.hasMethodAnnotation(ExceptionHandler.class)) return false;
    String name = method.getContainingClass().getPackageName();
    return method.getContainingClass() == IdentityController.class
        || name.equals("cn.edu.approval.organization")
        || name.equals("cn.edu.approval.application")
        || name.equals("cn.edu.approval.file");
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body == null) return null;
    return Map.of(
        "data",
        body,
        "requestId",
        response.getHeaders().getFirst("X-Request-Id"),
        "serverTime",
        clock.instant().toString());
  }
}
