package cn.edu.approval.security;

import cn.edu.approval.identity.AuthFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile("db & !migrate")
public class IdentityErrors {
  private final ObjectMapper mapper;

  public IdentityErrors(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @ExceptionHandler(AuthFailure.class)
  void auth(AuthFailure ex, HttpServletResponse response) throws IOException {
    ApiErrors.write(response, mapper, ex.status(), ex.code(), ex.retryAfter());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  void invalid(Exception ex, HttpServletResponse response) throws IOException {
    ApiErrors.write(response, mapper, 400, "INVALID_REQUEST", 0);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  void method(HttpServletResponse response) throws IOException {
    ApiErrors.write(response, mapper, 405, "METHOD_NOT_ALLOWED", 0);
  }

  @ExceptionHandler(DataAccessException.class)
  void database(HttpServletResponse response) throws IOException {
    ApiErrors.write(response, mapper, 503, "SERVICE_UNAVAILABLE", 0);
  }

  @ExceptionHandler(Exception.class)
  void unexpected(HttpServletResponse response) throws IOException {
    ApiErrors.write(response, mapper, 503, "SERVICE_UNAVAILABLE", 0);
  }
}
