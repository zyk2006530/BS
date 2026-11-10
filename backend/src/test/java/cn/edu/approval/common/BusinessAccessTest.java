package cn.edu.approval.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessAccessTest {
  private JdbcTemplate jdbc;
  private BusinessAccess access;
  private final AuthPrincipal principal = new AuthPrincipal("42", "7", "ACTIVE", false, "WEB");

  @BeforeEach
  void setup() {
    jdbc = mock(JdbcTemplate.class);
    access =
        new BusinessAccess(
            jdbc, Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 42L, "status", "ACTIVE", "must_change_password", 0)));
  }

  @Test
  void currentStatusRatherThanPrincipalSnapshotIsAuthoritative() {
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("status", "PENDING", "must_change_password", 0)));
    assertEquals(
        403, assertThrows(AuthFailure.class, () -> access.requireActive(principal)).status());
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    assertEquals(
        401, assertThrows(AuthFailure.class, () -> access.requireActive(principal)).status());
  }

  @Test
  void forcedPasswordAndDisabledCannotUseBusinessApis() {
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("status", "ACTIVE", "must_change_password", true)));
    assertEquals(
        "PASSWORD_CHANGE_REQUIRED",
        assertThrows(AuthFailure.class, () -> access.requireActive(principal)).code());
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("status", "DISABLED", "must_change_password", 0)));
    assertEquals(
        401, assertThrows(AuthFailure.class, () -> access.requireActive(principal)).status());
  }

  @Test
  void otherDraftAlwaysPrivateEvenWithIndependentGrant() {
    assertFalse(
        access.canRead(principal, Map.of("applicant_id", 9L, "status", "DRAFT", "dept_id", 1L)));
    assertTrue(
        access.canRead(principal, Map.of("applicant_id", 42L, "status", "DRAFT", "dept_id", 1L)));
  }

  @Test
  void readScopeHasNoImplicitAdminFinanceOrStatisticsSource() {
    BusinessAccess.SqlScope scope = access.readScope(principal, "a");
    assertTrue(scope.sql().contains("status<>'DRAFT'"));
    assertTrue(scope.sql().contains("g.ability='READ_BODY'"));
    assertFalse(scope.sql().contains("SYS_ADMIN"));
    assertFalse(scope.sql().contains("STATISTICS"));
    assertFalse(scope.sql().contains("FINANCE_APPROVER"));
    assertEquals(5, scope.args().size());
    assertThrows(IllegalArgumentException.class, () -> access.readScope(principal, "a OR 1=1"));
  }

  @Test
  void activeUserWithoutAdminRoleIsNotAdministrator() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertEquals(
        403, assertThrows(AuthFailure.class, () -> access.requireAdmin(principal)).status());
  }
}
