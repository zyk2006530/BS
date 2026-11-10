package cn.edu.approval.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OrganizationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
  private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private JdbcTemplate jdbc;
  private PlatformTransactionManager manager;
  private ResponsibilityGuard guard;
  private OrganizationService service;
  private Map<String, Object> config;
  private Map<String, Object> admin;
  private Map<String, Object> target;
  private Map<String, Object> session;
  private Map<String, Object> department;
  private List<String> targetRoles;
  private List<String> adminRoles;
  private List<String> sqlOrder;
  private final AuthPrincipal principal = new AuthPrincipal("10", "77", "ACTIVE", false, "WEB");

  @BeforeEach
  void setup() {
    jdbc = mock(JdbcTemplate.class);
    manager = mock(PlatformTransactionManager.class);
    guard = mock(ResponsibilityGuard.class);
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    service =
        new OrganizationService(
            jdbc, manager, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), guard);
    config = new HashMap<>();
    config.put("id", 1);
    config.put("row_version", 4L);
    config.put("active_policy_id", null);
    admin = user(10L, "ACTIVE");
    target = user(2L, "PENDING");
    session = new HashMap<>();
    session.put("user_id", 10L);
    session.put("client_type", "WEB");
    session.put("last_seen_at", LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
    session.put(
        "absolute_expires_at", LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
    department = new HashMap<>();
    department.put("id", 1L);
    department.put("row_version", 3L);
    department.put("enabled", 1);
    department.put("manager_user_id", null);
    department.put("name", "Research");
    targetRoles = new ArrayList<>(List.of("EMPLOYEE"));
    adminRoles = new ArrayList<>(List.of("SYS_ADMIN"));
    sqlOrder = new ArrayList<>();
    when(jdbc.queryForMap(anyString()))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              sqlOrder.add(sql);
              return config;
            });
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
        .thenReturn(0L);
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
        .thenReturn("5");
    when(jdbc.queryForMap(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              sqlOrder.add(sql);
              if (sql.contains("system_config")) return config;
              if (sql.contains("`user`")) return target;
              return Map.of();
            });
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              Object[] args = (Object[]) call.getRawArguments()[1];
              sqlOrder.add(sql + (args.length > 0 ? ":" + args[0] : ""));
              if (sql.contains("FROM `user`"))
                return List.of("10".equals(args[0].toString()) ? admin : target);
              if (sql.contains("FROM auth_session")) return List.of(session);
              if (sql.contains("FROM department")) return List.of(department);
              if (sql.contains("FROM data_grant")) return List.of(Map.of("id", 5L, "user_id", 2L));
              return List.of();
            });
    when(jdbc.queryForList(
            anyString(), org.mockito.ArgumentMatchers.eq(String.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              Object[] args = (Object[]) call.getRawArguments()[2];
              return "10".equals(args[0].toString()) ? adminRoles : targetRoles;
            });
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(String.class), any(Object[].class)))
        .thenReturn("5");
  }

  private static Map<String, Object> user(long id, String status) {
    Map<String, Object> user = new HashMap<>();
    user.put("id", id);
    user.put("status", status);
    user.put("row_version", 2L);
    user.put("department_id", 1L);
    user.put("must_change_password", 0);
    return user;
  }

  private AuthFailure failure(Runnable operation, int status, String code) {
    AuthFailure failure = assertThrows(AuthFailure.class, operation::run);
    assertEquals(status, failure.status());
    assertEquals(code, failure.code());
    return failure;
  }

  @Test
  void confirmLocksConfigThenUsersNumericallyThenSessionAndCommitsAudit() {
    Map<String, Object> result =
        service.confirm(principal, "2", "1", "2", "4", "confirm department", REQUEST_ID);
    assertEquals("3", result.get("rowVersion"));
    assertEquals("5", result.get("configVersion"));
    List<String> lockingQueries =
        sqlOrder.stream().filter(sql -> sql.contains("FOR UPDATE")).toList();
    assertTrue(lockingQueries.get(0).contains("system_config"));
    assertTrue(lockingQueries.get(1).endsWith(":2"));
    assertTrue(lockingQueries.get(2).endsWith(":10"));
    assertTrue(lockingQueries.get(3).contains("auth_session"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO user_role"), any(Object[].class));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO audit_record"), any(Object[].class));
    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("UPDATE system_config"), any(Object[].class));
    verify(manager).commit(any());
  }

  @Test
  void adminRevocationAfterConfigLockCannotUseCachedPrincipal() {
    adminRoles.clear();
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        403,
        "FORBIDDEN");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(manager).rollback(any());
  }

  @Test
  void nonAdminCannotDiscoverMissingUserDepartmentOrGrantBeforePrivilegeCheck() {
    adminRoles.clear();
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        403,
        "FORBIDDEN");
    failure(
        () -> service.confirm(principal, "999", "1", "2", "4", "confirm", REQUEST_ID),
        403,
        "FORBIDDEN");
    failure(
        () ->
            service.patchDepartment(
                principal, "999", null, null, null, false, "3", "4", "change", REQUEST_ID),
        403,
        "FORBIDDEN");
    failure(
        () -> service.revokeGrant(principal, "999", "4", "revoke", REQUEST_ID), 403, "FORBIDDEN");
    assertFalse(
        sqlOrder.stream()
            .anyMatch(
                sql ->
                    sql.endsWith(":999")
                        || sql.contains("FROM department")
                        || sql.contains("FROM data_grant")));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void currentAccountAndSessionRestrictionsRevalidated() {
    admin.put("must_change_password", 1);
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        403,
        "FORBIDDEN");
    admin.put("must_change_password", 0);
    session.put("revoked_at", LocalDateTime.now());
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        401,
        "INVALID_CREDENTIALS");
    session.remove("revoked_at");
    session.put("user_id", 2L);
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        401,
        "INVALID_CREDENTIALS");
  }

  @Test
  void configAndObjectVersionsCannotBeOmittedOrStale() {
    failure(
        () -> service.confirm(principal, "2", "1", "2", "3", "reason", REQUEST_ID),
        409,
        "VERSION_CONFLICT");
    failure(
        () -> service.confirm(principal, "2", "1", null, "4", "reason", REQUEST_ID),
        400,
        "INVALID_INPUT");
    failure(
        () -> service.confirm(principal, "2", "1", "1", "4", "reason", REQUEST_ID),
        409,
        "VERSION_CONFLICT");
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void rejectRequiresPendingStateAndNonblankReason() {
    failure(() -> service.reject(principal, "2", "2", "4", " ", REQUEST_ID), 400, "INVALID_INPUT");
    target.put("status", "ACTIVE");
    failure(
        () -> service.reject(principal, "2", "2", "4", "reason", REQUEST_ID),
        409,
        "USER_NOT_PENDING");
  }

  @Test
  void confirmationNeverGrantsAdministrator() {
    service.confirm(principal, "2", "1", "2", "4", "accepted", REQUEST_ID);
    ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO user_role"), values.capture());
    assertEquals("EMPLOYEE", values.getValue()[3]);
  }

  @Test
  void idempotentRoleInsertUsesOnlySelectAndInsertPrivileges() {
    service.confirm(principal, "2", "1", "2", "4", "accepted", REQUEST_ID);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), values.capture());
    String insert =
        sql.getAllValues().stream()
            .filter(query -> query.startsWith("INSERT INTO user_role"))
            .findFirst()
            .orElseThrow();
    assertTrue(insert.contains("LEFT JOIN user_role existing"));
    assertTrue(insert.contains("existing.user_id IS NULL"));
    assertFalse(insert.contains("UPDATE"));
    assertFalse(insert.contains("IGNORE"));
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.startsWith("UPDATE user_role"), any(Object[].class));
  }

  @Test
  void lastActiveAdministratorCannotBeDisabledOrLoseRole() {
    target.put("status", "ACTIVE");
    targetRoles.add("SYS_ADMIN");
    failure(
        () -> service.patchUser(principal, "2", "DISABLED", null, "2", "4", "disable", REQUEST_ID),
        409,
        "LAST_ACTIVE_ADMIN");
    failure(
        () ->
            service.replaceRoles(
                principal, "2", List.of("EMPLOYEE"), "2", "4", "remove admin", REQUEST_ID),
        409,
        "LAST_ACTIVE_ADMIN");
  }

  @Test
  void responsibleUserCannotChangeDepartmentOrLoseApprovalRole() {
    target.put("status", "ACTIVE");
    targetRoles.add("FINANCE_APPROVER");
    doThrow(new AuthFailure(409, "USER_HAS_RESPONSIBILITIES", 0))
        .when(guard)
        .requireUserClear("2", null);
    failure(
        () -> service.patchUser(principal, "2", null, "3", "2", "4", "transfer", REQUEST_ID),
        409,
        "USER_HAS_RESPONSIBILITIES");
    failure(
        () ->
            service.replaceRoles(
                principal, "2", List.of("EMPLOYEE"), "2", "4", "remove role", REQUEST_ID),
        409,
        "USER_HAS_RESPONSIBILITIES");
  }

  @Test
  void disabledUserRevokesAllSessionsAndWritesAudit() {
    target.put("status", "ACTIVE");
    service.patchUser(principal, "2", "DISABLED", null, "2", "4", "disable", REQUEST_ID);
    verify(guard).requireUserClear("2", null);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE auth_session SET revoked_at"),
            any(Object[].class));
  }

  @Test
  void departmentManagerAssignmentAddsRoleAndReturnsAffectedVersions() {
    target.put("status", "ACTIVE");
    Map<String, Object> result =
        service.patchDepartment(
            principal, "1", null, null, "2", false, "3", "4", "assign manager", REQUEST_ID);
    assertEquals("4", result.get("rowVersion"));
    assertTrue(result.containsKey("userVersions"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO user_role"), any(Object[].class));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE `user` SET row_version"),
            any(Object[].class));
  }

  @Test
  void managerMustBeConfirmedInSameDepartmentAndRoleCannotBeSelfGranted() {
    failure(
        () ->
            service.patchDepartment(
                principal, "1", null, null, "2", false, "3", "4", "assign", REQUEST_ID),
        409,
        "INVALID_DEPARTMENT_MANAGER");
    target.put("status", "ACTIVE");
    failure(
        () ->
            service.replaceRoles(
                principal, "2", List.of("EMPLOYEE", "DEPT_HEAD"), "2", "4", "role", REQUEST_ID),
        409,
        "MANAGER_ROLE_MUST_MATCH_RELATION");
  }

  @Test
  void enterpriseGrantRequiresExplicitConfirmationAndValidPeriod() {
    failure(
        () ->
            service.createGrant(
                principal,
                "2",
                "READ_BODY",
                "ENTERPRISE",
                null,
                NOW.toString(),
                NOW.plusSeconds(60).toString(),
                false,
                "4",
                "grant",
                REQUEST_ID),
        400,
        "INVALID_INPUT");
    failure(
        () ->
            service.createGrant(
                principal,
                "2",
                "READ_BODY",
                "DEPARTMENT",
                "1",
                NOW.toString(),
                NOW.toString(),
                false,
                "4",
                "grant",
                REQUEST_ID),
        400,
        "INVALID_INPUT");
    failure(
        () ->
            service.createGrant(
                principal,
                "2",
                "STATISTICS",
                "ENTERPRISE",
                "1",
                NOW.toString(),
                NOW.plusSeconds(60).toString(),
                true,
                "4",
                "grant",
                REQUEST_ID),
        400,
        "INVALID_INPUT");
  }

  @Test
  void independentGrantAbilitiesRemainDistinctAndRevocationIsHistorical() {
    target.put("status", "ACTIVE");
    Map<String, Object> result =
        service.createGrant(
            principal,
            "2",
            "STATISTICS",
            "ENTERPRISE",
            null,
            NOW.toString(),
            NOW.plusSeconds(60).toString(),
            true,
            "4",
            "statistics only",
            REQUEST_ID);
    assertEquals("5", result.get("grantId"));
    ArgumentCaptor<Object[]> grantArgs = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO data_grant"), grantArgs.capture());
    assertEquals("STATISTICS", grantArgs.getValue()[1]);
    assertEquals("ENTERPRISE", grantArgs.getValue()[2]);
    service.revokeGrant(principal, "5", "4", "revoked", REQUEST_ID);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE data_grant SET revoked_at"),
            any(Object[].class));
    verify(jdbc, never())
        .update(
            org.mockito.ArgumentMatchers.contains("DELETE FROM data_grant"), any(Object[].class));
  }

  @Test
  void passwordResetRevokesSessionsWithoutAuditingSecretOrHash() {
    service.resetPassword(principal, "2", "Temporary-秘密-42", "2", "4", "reset account", REQUEST_ID);
    ArgumentCaptor<Object[]> auditArgs = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO audit_record"), auditArgs.capture());
    String all = java.util.Arrays.toString(auditArgs.getValue());
    assertFalse(all.contains("Temporary"));
    assertFalse(all.contains("argon2"));
    assertFalse(all.contains("password_hash"));
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("UPDATE auth_session SET revoked_at"),
            any(Object[].class));
  }

  @Test
  void auditFailureRollsBackBusinessAndNeverAdvancesConfiguration() {
    doThrow(new DataAccessResourceFailureException("raw SQL secret"))
        .when(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO audit_record"), any(Object[].class));
    failure(
        () -> service.confirm(principal, "2", "1", "2", "4", "confirm", REQUEST_ID),
        503,
        "DATABASE_UNAVAILABLE");
    verify(manager).rollback(any());
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("UPDATE system_config"), any(Object[].class));
  }

  @Test
  void adminReadRequiresCurrentRoleAndUsesBoundedPagination() {
    adminRoles.clear();
    failure(() -> service.users(principal, null, null, "1", "20"), 403, "FORBIDDEN");
    adminRoles.add("SYS_ADMIN");
    failure(() -> service.users(principal, null, null, "1", "101"), 400, "INVALID_INPUT");
  }

  @Test
  void baselineGuardFailsClosedForPolicyOrUnfinishedInstancesWithoutStudentTables() {
    BaselineResponsibilityGuard baseline = new BaselineResponsibilityGuard(jdbc);
    failure(() -> baseline.requireUserClear("2", 7L), 409, "RESPONSIBILITY_CHECK_UNAVAILABLE");
    when(jdbc.queryForObject(
            org.mockito.ArgumentMatchers.contains("FROM application"),
            org.mockito.ArgumentMatchers.eq(Long.class),
            any(Object[].class)))
        .thenReturn(1L);
    failure(
        () -> baseline.requireDepartmentClear("1", null), 409, "RESPONSIBILITY_CHECK_UNAVAILABLE");
  }
}
