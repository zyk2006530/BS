package cn.edu.approval.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
  private JdbcTemplate jdbc;
  private PlatformTransactionManager manager;
  private AuthService service;
  private Map<String, Object> user;
  private Map<String, Object> session;
  private final AuthPrincipal principal = new AuthPrincipal("1", "7", "ACTIVE", false, "WEB");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    service = new AuthService(jdbc, manager, Clock.fixed(NOW, ZoneOffset.UTC));
    user = new HashMap<>();
    user.put("id", 1L);
    user.put("username", "alice");
    user.put("name", "Alice");
    user.put("email", "alice@example.com");
    user.put("requested_department_id", 1L);
    user.put("department_id", 1L);
    user.put("status", "ACTIVE");
    user.put("must_change_password", 0);
    user.put("row_version", 1L);
    session = new HashMap<>();
    session.put("id", 7L);
    session.put("user_id", 1L);
    session.put("client_type", "WEB");
    session.put("last_seen_at", utc(NOW.minusSeconds(60)));
    session.put("absolute_expires_at", utc(NOW.plusSeconds(3600)));
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM `user`")) return List.of(user);
              if (sql.contains("FROM auth_session")) return List.of(session);
              if (sql.contains("FROM department")) return List.of(Map.of("id", 1L));
              return List.of();
            });
    when(jdbc.queryForObject(contains("SELECT counter"), eq(Integer.class), any(Object[].class)))
        .thenReturn(0);
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @Test
  void permissionSummarySeparatesAdministrationFromIndependentDataAbilities() {
    when(jdbc.queryForList(contains("SELECT r.code"), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("SYS_ADMIN", "FINANCE_APPROVER"));
    when(jdbc.queryForList(
            contains("SELECT DISTINCT ability"), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("STATISTICS"));
    Object summary = service.profile(principal).get("permissions");
    assertEquals(
        Map.of(
            "roles",
            List.of("SYS_ADMIN", "FINANCE_APPROVER"),
            "canManage",
            true,
            "canCreateDraft",
            true,
            "hasBodyScope",
            false,
            "hasStatisticsScope",
            true),
        summary);
    user.put("must_change_password", 1);
    assertEquals(
        Map.of(
            "roles",
            List.of(),
            "canManage",
            false,
            "canCreateDraft",
            false,
            "hasBodyScope",
            false,
            "hasStatisticsScope",
            false),
        service.profile(principal).get("permissions"));
  }

  @Test
  void utcDateTimeStringsAreIndependentOfJvmDefaultZone() {
    session.put("last_seen_at", "2026-11-10T05:59:00.000000");
    session.put("absolute_expires_at", "2026-11-10T07:00:00.000000");
    assertDoesNotThrow(() -> service.profile(principal));
    session.put("absolute_expires_at", "2026-11-10T06:00:00.000000");
    assertThrows(AuthFailure.class, () -> service.profile(principal));
  }

  @Test
  void normalizationPreservesAliasesAndUnicodePasswordBoundaries() {
    assertEquals("alice_1", AuthService.username("  ALICE_1  "));
    assertEquals("a.b+tag@example.com", AuthService.email(" A.B+Tag@EXAMPLE.COM "));
    assertDoesNotThrow(() -> AuthService.validatePassword("😀".repeat(8)));
    assertDoesNotThrow(() -> AuthService.validatePassword("😀".repeat(64)));
    assertDoesNotThrow(() -> AuthService.validatePassword("        "));
    assertThrows(AuthFailure.class, () -> AuthService.validatePassword("😀".repeat(7)));
    assertThrows(AuthFailure.class, () -> AuthService.validatePassword("😀".repeat(65)));
    assertThrows(AuthFailure.class, () -> AuthService.validatePassword("1234567\uD800"));
    assertThrows(AuthFailure.class, () -> AuthService.email("用户@example.com"));
    assertThrows(AuthFailure.class, () -> AuthService.email("a@K.example.com"));
    assertThrows(AuthFailure.class, () -> AuthService.username("Kabc"));
    assertThrows(AuthFailure.class, () -> AuthService.username("a-bc"));
  }

  @Test
  void registerCreatesPendingAndInboxInOneCommitWithoutRoles() {
    when(jdbc.queryForObject(contains("CAST(id AS CHAR)"), eq(String.class), any(Object[].class)))
        .thenReturn("1");
    Map<String, Object> result =
        service.register(" Alice ", "Alice", "A@EXAMPLE.COM", "password", "1", "ip");
    assertEquals("PENDING", result.get("status"));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
    assertTrue(
        sql.getAllValues().stream().anyMatch(value -> value.contains("INSERT INTO inbox_state")));
    assertFalse(sql.getAllValues().stream().anyMatch(value -> value.contains("user_role")));
    verify(manager, times(2)).commit(any());
  }

  @Test
  void duplicateRegistrationRollsBackUserAndInboxAndReturns409() {
    when(jdbc.update(contains("INSERT INTO `user`"), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("private SQL"));
    AuthFailure failure =
        assertThrows(
            AuthFailure.class,
            () -> service.register("alice", "Alice", "a@example.com", "password", "1", "ip"));
    assertEquals(409, failure.status());
    verify(manager).rollback(any());
    verify(jdbc, never()).update(contains("INSERT INTO inbox_state"), any(Object[].class));
  }

  @Test
  void failedLoginCounterCommitsInsteadOfRollingBack() {
    user.put("password_hash", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode("password"));
    AuthFailure failure =
        assertThrows(
            AuthFailure.class, () -> service.login("alice", "incorrect", "ip", "WEB", null));
    assertEquals(401, failure.status());
    verify(jdbc, times(2)).update(contains("counter=counter+1"), any(Object[].class));
    verify(manager, times(2)).commit(any());
    verify(manager, never()).rollback(any());
  }

  @Test
  void accountAndIpLimitsHaveRetryAfterAndNoCredentialVerification() {
    when(jdbc.queryForObject(contains("SELECT counter"), eq(Integer.class), any(Object[].class)))
        .thenReturn(20);
    AuthFailure failure =
        assertThrows(
            AuthFailure.class, () -> service.login("alice", "password", "ip", "WEB", null));
    assertEquals(429, failure.status());
    assertTrue(failure.retryAfter() > 0);
    verify(jdbc, never()).queryForList(contains("FROM `user`"), any(Object[].class));
  }

  @Test
  void loginReturnsOpaqueTokenButOnlyStoresItsSha256AndRotatesPrevious() {
    user.put("password_hash", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode("password"));
    Map<String, Object> result = service.login(" ALICE ", "password", "ip", "WEB", "x".repeat(43));
    String token = result.get("token").toString();
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("INSERT INTO auth_session"), args.capture());
    assertArrayEquals(AuthService.digest(token), (byte[]) args.getValue()[1]);
    assertEquals(utc(NOW.plusSeconds(604800)), args.getValue()[4]);
    verify(jdbc).update(contains("WHERE token_hash=? AND revoked_at IS NULL"), any(Object[].class));
  }

  @Test
  void exactIdleAndAbsoluteBoundariesAndClientMismatchAreRejected() {
    session.put("last_seen_at", utc(NOW.minusSeconds(1800)));
    assertEquals(401, assertThrows(AuthFailure.class, () -> service.profile(principal)).status());
    session.put("last_seen_at", utc(NOW));
    session.put("absolute_expires_at", utc(NOW));
    assertThrows(AuthFailure.class, () -> service.profile(principal));
    session.put("absolute_expires_at", utc(NOW.plusSeconds(1)));
    session.put("client_type", "MINI_PROGRAM");
    assertThrows(AuthFailure.class, () -> service.profile(principal));
  }

  @Test
  void currentUserAndSessionOverrideStalePrincipalForEveryWrite() {
    session.put("revoked_at", utc(NOW));
    assertThrows(
        AuthFailure.class, () -> service.updateProfile(principal, "Other", "b@example.com", "1"));
    verify(jdbc, never()).update(contains("SET name="), any(Object[].class));
    session.remove("revoked_at");
    user.put("status", "DISABLED");
    assertThrows(AuthFailure.class, () -> service.profile(principal));
    user.put("status", "ACTIVE");
    user.put("must_change_password", 1);
    assertEquals(
        403,
        assertThrows(
                AuthFailure.class,
                () -> service.updateProfile(principal, "Other", "b@example.com", "1"))
            .status());
  }

  @Test
  void passwordChangeChecksOldPasswordAndRevokesAllSessions() {
    user.put("password_hash", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode("password"));
    service.changePassword(principal, "password", "new-password");
    verify(jdbc).update(contains("must_change_password=?"), any(Object[].class));
    verify(jdbc).update(contains("WHERE user_id=? AND revoked_at IS NULL"), any(Object[].class));
  }

  @Test
  void managementHooksRequireExistingTransactionAndRevokeAll() {
    assertThrows(IllegalStateException.class, () -> service.resetPassword("1", "temporary"));
    assertThrows(IllegalStateException.class, () -> service.disable("1"));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      service.resetPassword("1", "temporary");
      service.disable("1");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verify(jdbc, times(2))
        .update(contains("WHERE user_id=? AND revoked_at IS NULL"), any(Object[].class));
    verify(jdbc).update(contains("SET status='DISABLED'"), any(Object[].class));
  }

  @Test
  void authenticateTouchesIdleTimeButNeverAbsoluteExpiry() {
    AuthPrincipal authenticated = service.authenticate("x".repeat(43), "WEB");
    assertEquals("1", authenticated.userId());
    assertEquals("7", authenticated.sessionId());
    verify(jdbc).update(contains("SET last_seen_at=? WHERE id=?"), any(Object[].class));
    verify(jdbc, never()).update(contains("SET absolute_expires_at="), any(Object[].class));
    org.mockito.InOrder locks = inOrder(jdbc);
    locks.verify(jdbc).queryForList(contains("WHERE token_hash=?"), any(Object[].class));
    locks
        .verify(jdbc)
        .queryForList(contains("FROM `user` WHERE id=? FOR UPDATE"), any(Object[].class));
    locks
        .verify(jdbc)
        .queryForList(contains("FROM auth_session WHERE id=? FOR UPDATE"), any(Object[].class));
  }

  @Test
  void optimisticProfileVersionRejectsStaleWrite() {
    assertEquals(
        400,
        assertThrows(
                AuthFailure.class,
                () -> service.updateProfile(principal, "Other", "b@example.com", null))
            .status());
    assertEquals(
        400,
        assertThrows(
                AuthFailure.class, () -> service.updateProfile(principal, "Other", "b@example.com"))
            .status());
    AuthFailure failure =
        assertThrows(
            AuthFailure.class,
            () -> service.updateProfile(principal, "Other", "b@example.com", "2"));
    assertEquals(409, failure.status());
    verify(jdbc, never()).update(contains("SET name="), any(Object[].class));
    assertDoesNotThrow(() -> service.updateProfile(principal, "Other", "b@example.com", "1"));
    verify(jdbc)
        .update(contains("SET name=?,email=?,row_version=row_version+1"), any(Object[].class));
  }

  @Test
  void accountFailureLimitStopsSixthAttemptAndCommits() {
    when(jdbc.queryForObject(contains("SELECT counter"), eq(Integer.class), any(Object[].class)))
        .thenReturn(0, 5);
    AuthFailure failure =
        assertThrows(
            AuthFailure.class, () -> service.login("alice", "password", "ip", "WEB", null));
    assertEquals(429, failure.status());
    verify(manager, times(2)).commit(any());
    verify(manager, never()).rollback(any());
    verify(jdbc, never()).queryForList(contains("FROM `user`"), any(Object[].class));
  }

  @Test
  void rejectedResubmissionUsesConfigFirstAndReturnsBothVersions() {
    user.put("status", "REJECTED");
    when(jdbc.queryForMap(contains("FROM system_config WHERE id=1 FOR UPDATE")))
        .thenReturn(Map.of("row_version", 3L));
    when(jdbc.queryForObject(contains("CAST(row_version AS CHAR)"), eq(String.class)))
        .thenReturn("4");
    Map<String, Object> result = service.resubmit(principal, "1", "1", "3");
    assertEquals("4", result.get("configVersion"));
    org.mockito.InOrder locks = inOrder(jdbc);
    locks.verify(jdbc).queryForMap(contains("FROM system_config WHERE id=1 FOR UPDATE"));
    locks
        .verify(jdbc)
        .queryForList(contains("FROM `user` WHERE id=? FOR UPDATE"), any(Object[].class));
    verify(jdbc).update(contains("status='PENDING',rejection_reason=NULL"), any(Object[].class));
    verify(jdbc)
        .update(
            contains("UPDATE system_config SET row_version=row_version+1"), any(Object[].class));
  }

  @Test
  void databaseErrorsAreSanitizedAndRepeatedLogoutIsHarmless() {
    when(jdbc.queryForList(contains("FROM auth_session"), any(Object[].class)))
        .thenReturn(List.of());
    assertDoesNotThrow(() -> service.logout("x".repeat(43)));
    assertDoesNotThrow(() -> service.logout(null));
    when(jdbc.queryForList(anyString()))
        .thenThrow(new DataAccessResourceFailureException("secret host password SQL"));
    AuthFailure failure = assertThrows(AuthFailure.class, () -> service.departments());
    assertEquals(503, failure.status());
    assertFalse(failure.getMessage().contains("secret"));
    assertNull(failure.getCause());
  }
}
