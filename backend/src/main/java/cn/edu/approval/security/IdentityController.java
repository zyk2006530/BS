package cn.edu.approval.security;

import cn.edu.approval.identity.AuthPrincipal;
import cn.edu.approval.identity.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db & !migrate")
@RequestMapping("/api/v1")
public class IdentityController {
  private final AuthService service;
  private final WebCredentials web;

  public IdentityController(AuthService service, WebCredentials web) {
    this.service = service;
    this.web = web;
  }

  @GetMapping("/auth/csrf")
  Map<String, Object> csrf(HttpServletRequest request, HttpServletResponse response) {
    return web.issue(response, web.sid(request));
  }

  @GetMapping("/departments")
  List<Map<String, Object>> departments() {
    return service.departments();
  }

  @PostMapping({"/auth/register", "/native-auth/register"})
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> register(@RequestBody Registration body, HttpServletRequest request) {
    return service.register(
        body.username(),
        body.name(),
        body.email(),
        body.password(),
        body.requestedDepartmentId(),
        request.getRemoteAddr());
  }

  @PostMapping({"/auth/login", "/native-auth/login"})
  Map<String, Object> login(
      @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    boolean nativeClient = request.getRequestURI().equals("/api/v1/native-auth/login");
    Map<String, Object> result =
        new LinkedHashMap<>(
            service.login(
                body.username(),
                body.password(),
                request.getRemoteAddr(),
                nativeClient ? "MINI_PROGRAM" : "WEB",
                (String) request.getAttribute("credentialToken")));
    if (!nativeClient) {
      String token = (String) result.remove("token");
      web.setCookie(response, "sid", token, 7 * 24 * 60 * 60);
      result.putAll(web.issue(response, token));
    }
    return result;
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request, HttpServletResponse response) {
    service.logout((String) request.getAttribute("credentialToken"));
    if (request.getHeader("Authorization") == null) {
      web.setCookie(response, "sid", "", 0);
      web.setCookie(response, "csrf_ctx", "", 0);
    }
  }

  @GetMapping({"/auth/me", "/profile"})
  Map<String, Object> profile(HttpServletRequest request) {
    Map<String, Object> result = service.profile(principal(request));
    if (principal(request).mustChangePassword()) {
      return Map.of(
          "userId",
          principal(request).userId(),
          "status",
          result.get("status"),
          "mustChangePassword",
          true);
    }
    return result;
  }

  @PatchMapping("/profile")
  Map<String, Object> update(@RequestBody ProfileUpdate body, HttpServletRequest request) {
    return service.updateProfile(
        principal(request), body.name(), body.email(), body.expectedVersion());
  }

  @PostMapping("/profile/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void password(
      @RequestBody PasswordChange body, HttpServletRequest request, HttpServletResponse response) {
    service.changePassword(principal(request), body.oldPassword(), body.newPassword());
    if (request.getHeader("Authorization") == null) {
      web.setCookie(response, "sid", "", 0);
      web.setCookie(response, "csrf_ctx", "", 0);
    }
  }

  @PostMapping("/profile/confirmation-request")
  Map<String, Object> resubmit(@RequestBody ConfirmationRequest body, HttpServletRequest request) {
    return service.resubmit(
        principal(request),
        body.requestedDepartmentId(),
        body.expectedVersion(),
        body.expectedConfigVersion());
  }

  private static AuthPrincipal principal(HttpServletRequest request) {
    return (AuthPrincipal) request.getAttribute("identity");
  }

  public record Registration(
      String username, String name, String email, String password, String requestedDepartmentId) {}

  public record Login(String username, String password) {}

  public record ProfileUpdate(String expectedVersion, String name, String email) {}

  public record PasswordChange(String oldPassword, String newPassword) {}

  public record ConfirmationRequest(
      String requestedDepartmentId, String expectedVersion, String expectedConfigVersion) {}
}
