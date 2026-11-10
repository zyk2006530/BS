package cn.edu.approval.security;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import cn.edu.approval.identity.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

final class IdentityRequestFilter extends OncePerRequestFilter {
  private static final int MAX_JSON = 1024 * 1024;
  private final AuthService service;
  private final WebCredentials web;
  private final ObjectMapper mapper;

  IdentityRequestFilter(AuthService service, WebCredentials web, ObjectMapper mapper) {
    this.service = service;
    this.web = web;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Request-Id", UUID.randomUUID().toString());
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    try {
      String path = request.getRequestURI();
      String authorization = request.getHeader("Authorization");
      String sid = web.sid(request);
      boolean cookies = request.getHeader("Cookie") != null;
      boolean nativeAnonymous = path.startsWith("/api/v1/native-auth/");
      boolean webAnonymous =
          path.equals("/api/v1/auth/login")
              || path.equals("/api/v1/auth/register")
              || path.equals("/api/v1/auth/csrf");
      if (webAnonymous && authorization != null) {
        throw new AuthFailure(400, "AMBIGUOUS_CREDENTIALS", 0);
      }
      if ((authorization != null && cookies)
          || (nativeAnonymous && (cookies || authorization != null))) {
        throw new AuthFailure(400, "AMBIGUOUS_CREDENTIALS", 0);
      }
      if (request.getHeaders("Authorization").asIterator().hasNext()
          && java.util.Collections.list(request.getHeaders("Authorization")).size() > 1) {
        throw new AuthFailure(400, "AMBIGUOUS_CREDENTIALS", 0);
      }
      String token = sid;
      String clientType = "WEB";
      if (authorization != null) {
        if (!authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
          throw new AuthFailure(401, "UNAUTHENTICATED", 0);
        }
        token = authorization.substring(7);
        clientType = "MINI_PROGRAM";
      }
      boolean write =
          !(request.getMethod().equals("GET")
              || request.getMethod().equals("HEAD")
              || request.getMethod().equals("OPTIONS"));
      if (write && !nativeAnonymous && authorization == null) web.verify(request, sid);
      // Uploads are streamed by the file module after authentication, never buffered as JSON.
      boolean upload = path.equals("/api/v1/files") && request.getMethod().equals("POST");
      if (write && !upload) {
        if (request.getContentLengthLong() > MAX_JSON)
          throw new AuthFailure(413, "BODY_TOO_LARGE", 0);
        byte[] body = request.getInputStream().readNBytes(MAX_JSON + 1);
        if (body.length > MAX_JSON) throw new AuthFailure(413, "BODY_TOO_LARGE", 0);
        request = new BufferedRequest(request, body);
      }
      boolean businessRoute =
          path.startsWith("/api/v1/admin/")
              || path.equals("/api/v1/applications")
              || path.startsWith("/api/v1/applications/")
              || path.equals("/api/v1/files")
              || path.startsWith("/api/v1/files/");
      boolean protectedRoute =
          businessRoute
              || path.equals("/api/v1/auth/me")
              || path.equals("/api/v1/profile")
              || path.startsWith("/api/v1/profile/");
      if (protectedRoute) {
        if (token == null) throw new AuthFailure(401, "UNAUTHENTICATED", 0);
        AuthPrincipal principal = service.authenticate(token, clientType);
        if (principal.mustChangePassword()
            && !path.equals("/api/v1/auth/me")
            && !path.equals("/api/v1/profile/password")) {
          throw new AuthFailure(403, "PASSWORD_CHANGE_REQUIRED", 0);
        }
        if (businessRoute && !"ACTIVE".equals(principal.status())) {
          throw new AuthFailure(403, "ACCOUNT_NOT_ACTIVE", 0);
        }
        request.setAttribute("identity", principal);
      }
      request.setAttribute("credentialToken", token);
      request.setAttribute("credentialClient", clientType);
      chain.doFilter(request, response);
    } catch (AuthFailure ex) {
      ApiErrors.write(response, mapper, ex.status(), ex.code(), ex.retryAfter());
    } catch (DataAccessException ex) {
      ApiErrors.write(response, mapper, 503, "SERVICE_UNAVAILABLE", 0);
    }
  }

  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous JSON only");
        }
      };
    }
  }
}
