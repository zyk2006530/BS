package cn.edu.approval.organization;

import cn.edu.approval.identity.AuthPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrative metadata endpoints, never an application-body or workflow interface. */
@RestController
@Profile("db & !migrate")
@RequestMapping("/api/v1/admin")
public class OrganizationController {
  private final OrganizationService service;

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  @GetMapping("/config")
  Map<String, Object> config(@RequestAttribute("identity") AuthPrincipal p) {
    return service.config(p);
  }

  @GetMapping("/users")
  Map<String, Object> users(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String departmentId,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize) {
    return service.users(p, status, departmentId, page, pageSize);
  }

  @GetMapping("/departments")
  Map<String, Object> departments(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize) {
    return service.departments(p, page, pageSize);
  }

  @GetMapping("/grants")
  Map<String, Object> grants(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) String ability,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize) {
    return service.grants(p, userId, ability, scope, page, pageSize);
  }

  @GetMapping("/audit-records")
  Map<String, Object> audit(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize) {
    return service.audit(p, actorId, targetType, action, page, pageSize);
  }

  @PostMapping("/users/{id}/confirm")
  Map<String, Object> confirm(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody Confirmation body,
      HttpServletResponse response) {
    return service.confirm(
        p,
        id,
        body.departmentId(),
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PostMapping("/users/{id}/reject")
  Map<String, Object> reject(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody Change body,
      HttpServletResponse response) {
    return service.reject(
        p,
        id,
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PatchMapping("/users/{id}")
  Map<String, Object> patch(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody UserPatch body,
      HttpServletResponse response) {
    return service.patchUser(
        p,
        id,
        body.status(),
        body.departmentId(),
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PutMapping("/users/{id}/roles")
  Map<String, Object> roles(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody Roles body,
      HttpServletResponse response) {
    return service.replaceRoles(
        p,
        id,
        body.roles(),
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PostMapping("/users/{id}/reset-password")
  Map<String, Object> reset(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody PasswordReset body,
      HttpServletResponse response) {
    return service.resetPassword(
        p,
        id,
        body.temporaryPassword(),
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PostMapping("/departments")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> createDepartment(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestBody DepartmentCreate body,
      HttpServletResponse response) {
    return service.createDepartment(
        p,
        body.name(),
        body.enabled(),
        body.managerUserId(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PatchMapping("/departments/{id}")
  Map<String, Object> patchDepartment(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody DepartmentPatch body,
      HttpServletResponse response) {
    return service.patchDepartment(
        p,
        id,
        body.name(),
        body.enabled(),
        body.managerUserId(),
        body.clearManager(),
        body.expectedVersion(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PostMapping("/grants")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> createGrant(
      @RequestAttribute("identity") AuthPrincipal p,
      @RequestBody GrantCreate body,
      HttpServletResponse response) {
    return service.createGrant(
        p,
        body.userId(),
        body.ability(),
        body.scope(),
        body.departmentId(),
        body.validFrom(),
        body.validUntil(),
        body.enterpriseConfirmed(),
        body.expectedConfigVersion(),
        body.reason(),
        requestId(response));
  }

  @PostMapping("/grants/{id}/revoke")
  Map<String, Object> revokeGrant(
      @RequestAttribute("identity") AuthPrincipal p,
      @PathVariable String id,
      @RequestBody GrantRevoke body,
      HttpServletResponse response) {
    return service.revokeGrant(
        p, id, body.expectedConfigVersion(), body.reason(), requestId(response));
  }

  private static String requestId(HttpServletResponse response) {
    return response.getHeader("X-Request-Id");
  }

  public record Confirmation(
      String departmentId, String expectedVersion, String expectedConfigVersion, String reason) {}

  public record Change(String expectedVersion, String expectedConfigVersion, String reason) {}

  public record UserPatch(
      String status,
      String departmentId,
      String expectedVersion,
      String expectedConfigVersion,
      String reason) {}

  public record Roles(
      List<String> roles, String expectedVersion, String expectedConfigVersion, String reason) {}

  public record PasswordReset(
      String temporaryPassword,
      String expectedVersion,
      String expectedConfigVersion,
      String reason) {}

  public record DepartmentCreate(
      String name,
      Boolean enabled,
      String managerUserId,
      String expectedConfigVersion,
      String reason) {}

  public record DepartmentPatch(
      String name,
      Boolean enabled,
      String managerUserId,
      Boolean clearManager,
      String expectedVersion,
      String expectedConfigVersion,
      String reason) {}

  public record GrantCreate(
      String userId,
      String ability,
      String scope,
      String departmentId,
      String validFrom,
      String validUntil,
      Boolean enterpriseConfirmed,
      String expectedConfigVersion,
      String reason) {}

  public record GrantRevoke(String expectedConfigVersion, String reason) {}
}
