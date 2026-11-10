package cn.edu.approval.common;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Current identity and independent body/statistics capabilities; never grants by admin role. */
@Component
@Profile("db & !migrate")
public class BusinessAccess {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public BusinessAccess(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Call again after acquiring the business lock, in a READ_COMMITTED transaction. */
  public Map<String, Object> requireActive(AuthPrincipal principal) {
    if (principal == null) throw new AuthFailure(401, "UNAUTHENTICATED", 0);
    var rows =
        jdbc.queryForList(
            "SELECT u.* FROM `user` u JOIN auth_session s ON s.user_id=u.id "
                + "WHERE u.id=? AND s.id=? AND s.client_type=? AND s.revoked_at IS NULL "
                + "AND s.absolute_expires_at>? AND s.last_seen_at>?",
            principal.userId(),
            principal.sessionId(),
            principal.clientType(),
            now(),
            now().minusMinutes(30));
    if (rows.isEmpty()) throw new AuthFailure(401, "UNAUTHENTICATED", 0);
    Map<String, Object> user = rows.get(0);
    if ("DISABLED".equals(user.get("status"))) throw new AuthFailure(401, "UNAUTHENTICATED", 0);
    if (!"ACTIVE".equals(user.get("status"))) throw new AuthFailure(403, "ACCOUNT_NOT_ACTIVE", 0);
    Object forced = user.get("must_change_password");
    if (Boolean.TRUE.equals(forced) || (forced instanceof Number number && number.intValue() != 0))
      throw new AuthFailure(403, "PASSWORD_CHANGE_REQUIRED", 0);
    return user;
  }

  public void requireAdmin(AuthPrincipal principal) {
    requireActive(principal);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='SYS_ADMIN'",
            Long.class,
            principal.userId())
        == 0) throw new AuthFailure(403, "FORBIDDEN", 0);
  }

  public boolean hasAbility(AuthPrincipal principal, String ability, String departmentId) {
    requireActive(principal);
    if (!List.of("READ_BODY", "STATISTICS").contains(ability)) return false;
    Long managers =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM department WHERE id=? AND enabled=1 AND manager_user_id=?",
            Long.class,
            departmentId,
            principal.userId());
    if (managers != null && managers > 0) return true;
    Long grants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM data_grant WHERE user_id=? AND ability=? AND revoked_at IS NULL AND valid_from<=? AND valid_until>? AND (scope='ENTERPRISE' OR (scope='DEPARTMENT' AND department_id=?))",
            Long.class,
            principal.userId(),
            ability,
            now(),
            now(),
            departmentId);
    return grants != null && grants > 0;
  }

  public boolean canRead(AuthPrincipal principal, Map<String, Object> application) {
    requireActive(principal);
    if (principal.userId().equals(String.valueOf(application.get("applicant_id")))) return true;
    if ("DRAFT".equals(application.get("status"))) return false;
    return hasAbility(principal, "READ_BODY", String.valueOf(application.get("dept_id")));
  }

  /** Alias is a hard-coded repository identifier, never an incoming sort/filter expression. */
  public SqlScope readScope(AuthPrincipal principal, String alias) {
    requireActive(principal);
    if (!alias.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("Invalid repository alias");
    List<Object> arguments = new ArrayList<>();
    arguments.add(principal.userId());
    arguments.add(principal.userId());
    arguments.add(principal.userId());
    arguments.add(now());
    arguments.add(now());
    String sql =
        "("
            + alias
            + ".applicant_id=? OR ("
            + alias
            + ".status<>'DRAFT' AND (EXISTS (SELECT 1 FROM department d WHERE d.id="
            + alias
            + ".dept_id AND d.enabled=1 AND d.manager_user_id=?) OR EXISTS (SELECT 1 FROM data_grant g WHERE g.user_id=? AND g.ability='READ_BODY' AND g.revoked_at IS NULL AND g.valid_from<=? AND g.valid_until>? AND (g.scope='ENTERPRISE' OR (g.scope='DEPARTMENT' AND g.department_id="
            + alias
            + ".dept_id))))))";
    return new SqlScope(sql, List.copyOf(arguments));
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  public record SqlScope(String sql, List<Object> args) {}
}
