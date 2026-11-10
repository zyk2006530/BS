package cn.edu.approval.organization;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Organization administration. No business-body or workflow execution authority is implied. */
@Service
@Profile("db & !migrate")
public class OrganizationService {
  private static final Set<String> ROLES =
      Set.of("EMPLOYEE", "DEPT_HEAD", "FINANCE_APPROVER", "ENTERPRISE_HEAD", "SYS_ADMIN");
  private static final Set<String> APPROVAL_ROLES =
      Set.of("DEPT_HEAD", "FINANCE_APPROVER", "ENTERPRISE_HEAD");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final ResponsibilityGuard guard;
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

  public OrganizationService(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      Clock clock,
      ObjectMapper mapper,
      ResponsibilityGuard guard) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.mapper = mapper;
    this.guard = guard;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Map<String, Object> config(AuthPrincipal p) {
    return read(
        p,
        () -> {
          Map<String, Object> config =
              jdbc.queryForMap(
                  "SELECT CAST(row_version AS CHAR) AS configVersion,CAST(active_policy_id AS CHAR) AS activePolicyVersionId FROM system_config WHERE id=1");
          config.put("dependencyGuard", "BASELINE_FAIL_CLOSED");
          return config;
        });
  }

  public Map<String, Object> users(
      AuthPrincipal p, String status, String departmentId, String page, String pageSize) {
    return read(
        p,
        () -> {
          List<Object> args = new ArrayList<>();
          String where = " WHERE 1=1";
          if (status != null) {
            if (!Set.of("PENDING", "REJECTED", "ACTIVE", "DISABLED").contains(status))
              throw invalid();
            where += " AND u.status=?";
            args.add(status);
          }
          if (departmentId != null) {
            where += " AND u.department_id=?";
            args.add(id(departmentId));
          }
          long total = count("SELECT COUNT(*) FROM `user` u" + where, args.toArray());
          Page paging = page(page, pageSize);
          args.add(paging.size());
          args.add(paging.offset());
          List<Map<String, Object>> rows =
              jdbc.queryForList(
                  "SELECT CAST(u.id AS CHAR) AS userId,u.username,u.name,u.email,u.status,CAST(u.department_id AS CHAR) AS departmentId,CAST(u.requested_department_id AS CHAR) AS requestedDepartmentId,CAST(u.row_version AS CHAR) AS rowVersion,u.must_change_password AS mustChangePassword,u.rejection_reason AS rejectionReason FROM `user` u"
                      + where
                      + " ORDER BY u.id LIMIT ? OFFSET ?",
                  args.toArray());
          for (Map<String, Object> row : rows) {
            row.put("mustChangePassword", truth(row.get("mustChangePassword")));
            row.put("roles", roles(row.get("userId").toString()));
            row.put(
                "managerDepartmentCount",
                Long.toString(
                    count(
                        "SELECT COUNT(*) FROM department WHERE manager_user_id=?",
                        row.get("userId"))));
          }
          return listing(rows, total, paging);
        });
  }

  public Map<String, Object> departments(AuthPrincipal p, String page, String pageSize) {
    return read(
        p,
        () -> {
          Page paging = page(page, pageSize);
          List<Map<String, Object>> rows =
              jdbc.queryForList(
                  "SELECT CAST(id AS CHAR) AS departmentId,name,enabled,CAST(manager_user_id AS CHAR) AS managerUserId,CAST(row_version AS CHAR) AS rowVersion FROM department ORDER BY id LIMIT ? OFFSET ?",
                  paging.size(),
                  paging.offset());
          for (Map<String, Object> row : rows) {
            row.put("enabled", truth(row.get("enabled")));
          }
          return listing(rows, count("SELECT COUNT(*) FROM department"), paging);
        });
  }

  public Map<String, Object> grants(
      AuthPrincipal p, String userId, String ability, String scope, String page, String pageSize) {
    return read(
        p,
        () -> {
          String where = " WHERE 1=1";
          List<Object> args = new ArrayList<>();
          if (userId != null) {
            where += " AND user_id=?";
            args.add(id(userId));
          }
          if (ability != null) {
            ability(ability);
            where += " AND ability=?";
            args.add(ability);
          }
          if (scope != null) {
            scope(scope);
            where += " AND scope=?";
            args.add(scope);
          }
          long total = count("SELECT COUNT(*) FROM data_grant" + where, args.toArray());
          Page paging = page(page, pageSize);
          args.add(paging.size());
          args.add(paging.offset());
          return listing(
              jdbc.queryForList(
                  "SELECT CAST(id AS CHAR) AS grantId,CAST(user_id AS CHAR) AS userId,ability,scope,CAST(department_id AS CHAR) AS departmentId,DATE_FORMAT(valid_from,'%Y-%m-%dT%H:%i:%s.%fZ') AS validFrom,DATE_FORMAT(valid_until,'%Y-%m-%dT%H:%i:%s.%fZ') AS validUntil,DATE_FORMAT(revoked_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS revokedAt,CAST(granter_id AS CHAR) AS granterId,reason FROM data_grant"
                      + where
                      + " ORDER BY id DESC LIMIT ? OFFSET ?",
                  args.toArray()),
              total,
              paging);
        });
  }

  public Map<String, Object> audit(
      AuthPrincipal p,
      String actorId,
      String targetType,
      String action,
      String page,
      String pageSize) {
    return read(
        p,
        () -> {
          String where = " WHERE 1=1";
          List<Object> args = new ArrayList<>();
          if (actorId != null) {
            where += " AND actor_id=?";
            args.add(id(actorId));
          }
          if (targetType != null) {
            if (!Set.of("USER", "DEPARTMENT", "DATA_GRANT").contains(targetType)) throw invalid();
            where += " AND target_type=?";
            args.add(targetType);
          }
          if (action != null) {
            if (!action.matches("[A-Z_]{1,64}")) throw invalid();
            where += " AND action=?";
            args.add(action);
          }
          long total = count("SELECT COUNT(*) FROM audit_record" + where, args.toArray());
          Page paging = page(page, pageSize);
          args.add(paging.size());
          args.add(paging.offset());
          return listing(
              jdbc.queryForList(
                  "SELECT CAST(id AS CHAR) AS auditId,CAST(actor_id AS CHAR) AS actorId,target_type AS targetType,CAST(target_id AS CHAR) AS targetId,action,before_values AS beforeValues,after_values AS afterValues,reason,request_id AS requestId,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS createdAt FROM audit_record"
                      + where
                      + " ORDER BY id DESC LIMIT ? OFFSET ?",
                  args.toArray()),
              total,
              paging);
        });
  }

  public Map<String, Object> confirm(
      AuthPrincipal p,
      String userId,
      String departmentId,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    String department = id(departmentId);
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          Map<String, Object> user = ctx.user(target);
          version(user.get("row_version"), expectedVersion);
          if (!"PENDING".equals(user.get("status"))) throw conflict("USER_NOT_PENDING");
          enabledDepartment(department);
          jdbc.update(
              "UPDATE `user` SET status='ACTIVE',department_id=?,rejection_reason=NULL,row_version=row_version+1,updated_at=? WHERE id=?",
              department,
              now(),
              target);
          addRole(target, "EMPLOYEE");
          return finish(
              ctx,
              "USER",
              target,
              "USER_CONFIRMED",
              snapshot(user),
              userSnapshot(target),
              reason,
              requestId,
              "rowVersion",
              increment(user.get("row_version")));
        });
  }

  public Map<String, Object> reject(
      AuthPrincipal p,
      String userId,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    text(reason, 500);
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          Map<String, Object> user = ctx.user(target);
          version(user.get("row_version"), expectedVersion);
          if (!"PENDING".equals(user.get("status"))) throw conflict("USER_NOT_PENDING");
          jdbc.update(
              "UPDATE `user` SET status='REJECTED',rejection_reason=?,row_version=row_version+1,updated_at=? WHERE id=?",
              reason,
              now(),
              target);
          return finish(
              ctx,
              "USER",
              target,
              "USER_REJECTED",
              snapshot(user),
              userSnapshot(target),
              reason,
              requestId,
              "rowVersion",
              increment(user.get("row_version")));
        });
  }

  public Map<String, Object> patchUser(
      AuthPrincipal p,
      String userId,
      String status,
      String departmentId,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    if (status == null && departmentId == null) throw invalid();
    if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) throw invalid();
    if (departmentId != null) id(departmentId);
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          Map<String, Object> user = ctx.user(target);
          version(user.get("row_version"), expectedVersion);
          if (!Set.of("ACTIVE", "DISABLED").contains(user.get("status")))
            throw conflict("CONFIRMATION_REQUIRED");
          String nextStatus = status == null ? user.get("status").toString() : status;
          String nextDepartment =
              departmentId == null ? string(user.get("department_id")) : departmentId;
          enabledDepartment(id(nextDepartment));
          boolean restrictive =
              !nextStatus.equals(user.get("status")) && "DISABLED".equals(nextStatus)
                  || !nextDepartment.equals(string(user.get("department_id")));
          if (restrictive) guard.requireUserClear(target, ctx.config().get("active_policy_id"));
          if ("DISABLED".equals(nextStatus)) protectLastAdmin(target, false);
          jdbc.update(
              "UPDATE `user` SET status=?,department_id=?,row_version=row_version+1,updated_at=? WHERE id=?",
              nextStatus,
              nextDepartment,
              now(),
              target);
          if ("DISABLED".equals(nextStatus)) revokeSessions(target);
          return finish(
              ctx,
              "USER",
              target,
              "USER_UPDATED",
              snapshot(user),
              userSnapshot(target),
              reason,
              requestId,
              "rowVersion",
              increment(user.get("row_version")));
        });
  }

  public Map<String, Object> replaceRoles(
      AuthPrincipal p,
      String userId,
      List<String> requestedRoles,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    if (requestedRoles == null
        || requestedRoles.size() > ROLES.size()
        || requestedRoles.stream().anyMatch(role -> role == null || !ROLES.contains(role))
        || new LinkedHashSet<>(requestedRoles).size() != requestedRoles.size()) throw invalid();
    Set<String> next = new LinkedHashSet<>(requestedRoles);
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          Map<String, Object> user = ctx.user(target);
          version(user.get("row_version"), expectedVersion);
          if (!"ACTIVE".equals(user.get("status"))) throw conflict("ACTIVE_USER_REQUIRED");
          Set<String> previous = new LinkedHashSet<>(roles(target));
          if (previous.stream()
              .anyMatch(role -> APPROVAL_ROLES.contains(role) && !next.contains(role)))
            guard.requireUserClear(target, ctx.config().get("active_policy_id"));
          if (next.contains("DEPT_HEAD")
              != (count("SELECT COUNT(*) FROM department WHERE manager_user_id=?", target) > 0))
            throw conflict("MANAGER_ROLE_MUST_MATCH_RELATION");
          protectLastAdmin(target, next.contains("SYS_ADMIN"));
          for (String role : previous) if (!next.contains(role)) removeRole(target, role);
          for (String role : next) if (!previous.contains(role)) addRole(target, role);
          bumpUser(target);
          return finish(
              ctx,
              "USER",
              target,
              "USER_ROLES_REPLACED",
              Map.of("roles", previous),
              Map.of("roles", next),
              reason,
              requestId,
              "rowVersion",
              increment(user.get("row_version")));
        });
  }

  public Map<String, Object> resetPassword(
      AuthPrincipal p,
      String userId,
      String temporaryPassword,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    password(temporaryPassword);
    // Hash without holding management locks; the result never enters audit/response.
    String hash = encoder.encode(temporaryPassword);
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          Map<String, Object> user = ctx.user(target);
          version(user.get("row_version"), expectedVersion);
          jdbc.update(
              "UPDATE `user` SET password_hash=?,must_change_password=1,row_version=row_version+1,updated_at=? WHERE id=?",
              hash,
              now(),
              target);
          revokeSessions(target);
          return finish(
              ctx,
              "USER",
              target,
              "PASSWORD_RESET",
              Map.of("mustChangePassword", truth(user.get("must_change_password"))),
              Map.of("mustChangePassword", true, "sessionsRevoked", true),
              reason,
              requestId,
              "rowVersion",
              increment(user.get("row_version")));
        });
  }

  public Map<String, Object> createDepartment(
      AuthPrincipal p,
      String name,
      Boolean enabled,
      String managerUserId,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    text(name, 80);
    if (managerUserId != null) throw conflict("CREATE_DEPARTMENT_BEFORE_ASSIGNING_MANAGER");
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(),
        ctx -> {
          jdbc.update(
              "INSERT INTO department (name,enabled,manager_user_id,row_version,created_at,updated_at) VALUES (?,?,NULL,1,?,?)",
              name,
              enabled == null || enabled ? 1 : 0,
              now(),
              now());
          String department =
              jdbc.queryForObject("SELECT CAST(LAST_INSERT_ID() AS CHAR)", String.class);
          return finish(
              ctx,
              "DEPARTMENT",
              department,
              "DEPARTMENT_CREATED",
              Map.of(),
              Map.of("name", name, "enabled", enabled == null || enabled),
              reason,
              requestId,
              "rowVersion",
              "1");
        });
  }

  public Map<String, Object> patchDepartment(
      AuthPrincipal p,
      String departmentId,
      String name,
      Boolean enabled,
      String managerUserId,
      Boolean clearManager,
      String expectedVersion,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String department = id(departmentId);
    if (name != null) text(name, 80);
    if (managerUserId != null) id(managerUserId);
    if (Boolean.TRUE.equals(clearManager) && managerUserId != null) throw invalid();
    // Discover affected managers only AFTER config lock, before taking any user/session lock.
    return safe(
        () ->
            tx.execute(
                s -> {
                  Map<String, Object> config = lockConfig();
                  preflightAdmin(p);
                  Map<String, Object> previous = department(department);
                  String oldManager = string(previous.get("manager_user_id"));
                  String nextManager =
                      Boolean.TRUE.equals(clearManager)
                          ? null
                          : managerUserId == null ? oldManager : managerUserId;
                  Set<String> affected = new LinkedHashSet<>();
                  if (oldManager != null) affected.add(oldManager);
                  if (nextManager != null) affected.add(nextManager);
                  Context ctx = authorizeLocked(p, config, affected);
                  version(config.get("row_version"), expectedConfigVersion);
                  reason(reason);
                  version(previous.get("row_version"), expectedVersion);
                  boolean nextEnabled = enabled == null ? truth(previous.get("enabled")) : enabled;
                  if (!nextEnabled) {
                    if (nextManager != null) throw conflict("DISABLED_DEPARTMENT_HAS_MANAGER");
                    guard.requireDepartmentClear(department, config.get("active_policy_id"));
                  }
                  if (nextManager != null) {
                    Map<String, Object> user = ctx.user(nextManager);
                    if (!"ACTIVE".equals(user.get("status"))
                        || !department.equals(string(user.get("department_id")))
                        || truth(user.get("must_change_password")))
                      throw conflict("INVALID_DEPARTMENT_MANAGER");
                  }
                  jdbc.update(
                      "UPDATE department SET name=?,enabled=?,manager_user_id=?,row_version=row_version+1,updated_at=? WHERE id=?",
                      name == null ? previous.get("name") : name,
                      nextEnabled ? 1 : 0,
                      nextManager,
                      now(),
                      department);
                  if (oldManager != null && !oldManager.equals(nextManager)) {
                    guard.requireUserClear(oldManager, config.get("active_policy_id"));
                    if (count("SELECT COUNT(*) FROM department WHERE manager_user_id=?", oldManager)
                        == 0) removeRole(oldManager, "DEPT_HEAD");
                    bumpUser(oldManager);
                  }
                  if (nextManager != null && !nextManager.equals(oldManager)) {
                    addRole(nextManager, "DEPT_HEAD");
                    bumpUser(nextManager);
                  }
                  Map<String, Object> after = new LinkedHashMap<>();
                  after.put("name", name == null ? previous.get("name") : name);
                  after.put("enabled", nextEnabled);
                  after.put("managerUserId", nextManager);
                  Map<String, Object> result =
                      finish(
                          ctx,
                          "DEPARTMENT",
                          department,
                          "DEPARTMENT_UPDATED",
                          departmentSnapshot(previous),
                          after,
                          reason,
                          requestId,
                          "rowVersion",
                          increment(previous.get("row_version")));
                  Map<String, Object> userVersions = new LinkedHashMap<>();
                  for (String user : affected)
                    userVersions.put(
                        user,
                        jdbc.queryForObject(
                            "SELECT CAST(row_version AS CHAR) FROM `user` WHERE id=?",
                            String.class,
                            user));
                  result.put("userVersions", userVersions);
                  return result;
                }));
  }

  public Map<String, Object> createGrant(
      AuthPrincipal p,
      String userId,
      String ability,
      String scope,
      String departmentId,
      String validFrom,
      String validUntil,
      Boolean enterpriseConfirmed,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String target = id(userId);
    ability(ability);
    scope(scope);
    if ("ENTERPRISE".equals(scope)) {
      if (!Boolean.TRUE.equals(enterpriseConfirmed) || departmentId != null) throw invalid();
    } else id(departmentId);
    Instant from = instant(validFrom);
    Instant until = instant(validUntil);
    if (!until.isAfter(from) || !until.isAfter(clock.instant())) throw invalid();
    return change(
        p,
        expectedConfigVersion,
        reason,
        Set.of(target),
        ctx -> {
          if (!"ACTIVE".equals(ctx.user(target).get("status")))
            throw conflict("ACTIVE_USER_REQUIRED");
          if (departmentId != null) enabledDepartment(departmentId);
          jdbc.update(
              "INSERT INTO data_grant (user_id,ability,scope,department_id,valid_from,valid_until,granter_id,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
              target,
              ability,
              scope,
              departmentId,
              stamp(from),
              stamp(until),
              p.userId(),
              reason,
              now());
          String grant = jdbc.queryForObject("SELECT CAST(LAST_INSERT_ID() AS CHAR)", String.class);
          return finish(
              ctx,
              "DATA_GRANT",
              grant,
              "DATA_GRANT_CREATED",
              Map.of(),
              Map.of(
                  "userId",
                  target,
                  "ability",
                  ability,
                  "scope",
                  scope,
                  "validFrom",
                  from.toString(),
                  "validUntil",
                  until.toString(),
                  "enterpriseConfirmed",
                  Boolean.TRUE.equals(enterpriseConfirmed)),
              reason,
              requestId,
              "grantId",
              grant);
        });
  }

  public Map<String, Object> revokeGrant(
      AuthPrincipal p,
      String grantId,
      String expectedConfigVersion,
      String reason,
      String requestId) {
    String grant = id(grantId);
    return safe(
        () ->
            tx.execute(
                s -> {
                  Map<String, Object> config = lockConfig();
                  preflightAdmin(p);
                  List<Map<String, Object>> rows =
                      jdbc.queryForList("SELECT * FROM data_grant WHERE id=?", grant);
                  if (rows.isEmpty()) throw missing();
                  Map<String, Object> current = rows.get(0);
                  Context ctx =
                      authorizeLocked(p, config, Set.of(current.get("user_id").toString()));
                  version(config.get("row_version"), expectedConfigVersion);
                  reason(reason);
                  if (current.get("revoked_at") != null) throw conflict("GRANT_ALREADY_REVOKED");
                  jdbc.update("UPDATE data_grant SET revoked_at=? WHERE id=?", now(), grant);
                  return finish(
                      ctx,
                      "DATA_GRANT",
                      grant,
                      "DATA_GRANT_REVOKED",
                      Map.of("revoked", false),
                      Map.of("revoked", true),
                      reason,
                      requestId,
                      "grantId",
                      grant);
                }));
  }

  private Map<String, Object> change(
      AuthPrincipal p,
      String expectedConfigVersion,
      String reason,
      Set<String> targets,
      Function<Context, Map<String, Object>> operation) {
    return safe(
        () ->
            tx.execute(
                s -> {
                  Map<String, Object> config = lockConfig();
                  preflightAdmin(p);
                  Context ctx = authorizeLocked(p, config, targets);
                  version(config.get("row_version"), expectedConfigVersion);
                  reason(reason);
                  return operation.apply(ctx);
                }));
  }

  private void preflightAdmin(AuthPrincipal p) {
    if (p == null) throw unauthorized();
    // A nonlocking gate avoids revealing target existence without disturbing numeric user-lock
    // order.
    List<Map<String, Object>> users =
        jdbc.queryForList("SELECT * FROM `user` WHERE id=?", id(p.userId()));
    if (users.isEmpty()) throw unauthorized();
    Map<String, Object> actor = users.get(0);
    if (!"ACTIVE".equals(actor.get("status"))
        || truth(actor.get("must_change_password"))
        || !roles(p.userId()).contains("SYS_ADMIN")) throw forbidden();
    // authorizeLocked still revalidates actor, session and role after all sorted user locks.
  }

  private Map<String, Object> lockConfig() {
    return jdbc.queryForMap("SELECT * FROM system_config WHERE id=1 FOR UPDATE");
  }

  private Context authorizeLocked(
      AuthPrincipal p, Map<String, Object> config, Collection<String> targets) {
    if (p == null) throw unauthorized();
    Set<String> ids = new LinkedHashSet<>(targets);
    ids.add(id(p.userId()));
    Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    ids.stream()
        .sorted((a, b) -> new BigInteger(a).compareTo(new BigInteger(b)))
        .forEach(
            userId -> {
              List<Map<String, Object>> rows =
                  jdbc.queryForList("SELECT * FROM `user` WHERE id=? FOR UPDATE", userId);
              if (rows.isEmpty()) throw missing();
              users.put(userId, rows.get(0));
            });
    validateAdmin(p, users.get(p.userId()), true);
    return new Context(p, config, users);
  }

  private void validateAdmin(AuthPrincipal p, Map<String, Object> user, boolean lockSession) {
    if (!"ACTIVE".equals(user.get("status")) || truth(user.get("must_change_password")))
      throw forbidden();
    String sql =
        "SELECT user_id,client_type,revoked_at,DATE_FORMAT(last_seen_at,'%Y-%m-%dT%H:%i:%s.%f') AS last_seen_at,DATE_FORMAT(absolute_expires_at,'%Y-%m-%dT%H:%i:%s.%f') AS absolute_expires_at FROM auth_session WHERE id=?"
            + (lockSession ? " FOR UPDATE" : "");
    List<Map<String, Object>> sessions = jdbc.queryForList(sql, id(p.sessionId()));
    if (sessions.isEmpty()) throw unauthorized();
    Map<String, Object> session = sessions.get(0);
    if (!p.userId().equals(string(session.get("user_id")))
        || !p.clientType().equals(session.get("client_type"))
        || session.get("revoked_at") != null
        || !clock.instant().isBefore(databaseInstant(session.get("last_seen_at")).plusSeconds(1800))
        || !clock.instant().isBefore(databaseInstant(session.get("absolute_expires_at"))))
      throw unauthorized();
    if (!roles(p.userId()).contains("SYS_ADMIN")) throw forbidden();
  }

  private <T> T read(AuthPrincipal p, java.util.function.Supplier<T> reader) {
    return safe(
        () ->
            tx.execute(
                s -> {
                  if (p == null) throw unauthorized();
                  List<Map<String, Object>> users =
                      jdbc.queryForList("SELECT * FROM `user` WHERE id=?", id(p.userId()));
                  if (users.isEmpty()) throw unauthorized();
                  validateAdmin(p, users.get(0), false);
                  return reader.get();
                }));
  }

  private Map<String, Object> finish(
      Context ctx,
      String targetType,
      String target,
      String action,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason,
      String requestId,
      String versionKey,
      String value) {
    jdbc.update(
        "INSERT INTO audit_record (actor_id,target_type,target_id,action,before_values,after_values,reason,request_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        ctx.actor().userId(),
        targetType,
        target,
        action,
        json(before),
        json(after),
        reason,
        uuid(requestId),
        now());
    jdbc.update(
        "UPDATE system_config SET row_version=row_version+1,updated_at=? WHERE id=1", now());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("targetId", target);
    result.put(versionKey, value);
    result.put("configVersion", increment(ctx.config().get("row_version")));
    return result;
  }

  private void protectLastAdmin(String target, boolean retainsAdmin) {
    if (retainsAdmin || !roles(target).contains("SYS_ADMIN")) return;
    if (count(
            "SELECT COUNT(*) FROM `user` u JOIN user_role ur ON ur.user_id=u.id JOIN role r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND r.code='SYS_ADMIN' AND u.id<>?",
            target)
        == 0) throw conflict("LAST_ACTIVE_ADMIN");
  }

  private void enabledDepartment(String departmentId) {
    if (jdbc.queryForList(
            "SELECT id FROM department WHERE id=? AND enabled=1 FOR SHARE", departmentId)
        .isEmpty()) throw conflict("DEPARTMENT_DISABLED_OR_MISSING");
  }

  private Map<String, Object> department(String departmentId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM department WHERE id=?", departmentId);
    if (rows.isEmpty()) throw missing();
    return rows.get(0);
  }

  private List<String> roles(String userId) {
    return jdbc.queryForList(
        "SELECT r.code FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=? ORDER BY r.code",
        String.class,
        userId);
  }

  private void addRole(String userId, String role) {
    jdbc.update(
        "INSERT INTO user_role (user_id,role_id,created_at) SELECT ?,r.id,? FROM role r LEFT JOIN user_role existing ON existing.role_id=r.id AND existing.user_id=? WHERE r.code=? AND existing.user_id IS NULL",
        userId,
        now(),
        userId,
        role);
  }

  private void removeRole(String userId, String role) {
    jdbc.update(
        "DELETE ur FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code=?",
        userId,
        role);
  }

  private void bumpUser(String userId) {
    jdbc.update(
        "UPDATE `user` SET row_version=row_version+1,updated_at=? WHERE id=?", now(), userId);
  }

  private void revokeSessions(String userId) {
    jdbc.update(
        "UPDATE auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
        now(),
        userId);
  }

  private Map<String, Object> userSnapshot(String userId) {
    return snapshot(
        jdbc.queryForMap(
            "SELECT status,department_id,row_version,must_change_password FROM `user` WHERE id=?",
            userId));
  }

  private Map<String, Object> snapshot(Map<String, Object> user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", user.get("status"));
    result.put("departmentId", string(user.get("department_id")));
    result.put("rowVersion", string(user.get("row_version")));
    result.put("mustChangePassword", truth(user.get("must_change_password")));
    return result;
  }

  private Map<String, Object> departmentSnapshot(Map<String, Object> department) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", department.get("name"));
    result.put("enabled", truth(department.get("enabled")));
    result.put("managerUserId", string(department.get("manager_user_id")));
    return result;
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private String json(Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Audit serialization unavailable");
    }
  }

  private <T> T safe(java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DuplicateKeyException ex) {
      throw conflict("UNIQUE_CONFLICT");
    } catch (DataAccessException | TransactionException ex) {
      throw new AuthFailure(503, "DATABASE_UNAVAILABLE", 0);
    }
  }

  private LocalDateTime now() {
    return stamp(clock.instant());
  }

  private static LocalDateTime stamp(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant databaseInstant(Object value) {
    if (value instanceof LocalDateTime date) return date.toInstant(ZoneOffset.UTC);
    return LocalDateTime.parse(value.toString()).toInstant(ZoneOffset.UTC);
  }

  private static Instant instant(String value) {
    try {
      if (value == null || !value.endsWith("Z")) throw invalid();
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException ex) {
      throw invalid();
    }
  }

  private static String id(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]{0,19}")
        || new BigInteger(value).compareTo(new BigInteger("18446744073709551615")) > 0)
      throw invalid();
    return value;
  }

  private static String uuid(String value) {
    if (value == null) return null;
    try {
      return java.util.UUID.fromString(value).toString();
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
  }

  private static void version(Object current, String expected) {
    if (!current.toString().equals(id(expected))) throw conflict("VERSION_CONFLICT");
  }

  private static void text(String value, int max) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max)
      throw invalid();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
      } else if (Character.isLowSurrogate(c)) throw invalid();
    }
  }

  private static void reason(String value) {
    text(value, 2000);
  }

  private static void password(String value) {
    text(value, 64);
    if (value.codePointCount(0, value.length()) < 8) throw invalid();
  }

  private static void ability(String value) {
    if (!Set.of("READ_BODY", "STATISTICS").contains(value == null ? "" : value)) throw invalid();
  }

  private static void scope(String value) {
    if (!Set.of("DEPARTMENT", "ENTERPRISE").contains(value == null ? "" : value)) throw invalid();
  }

  private static boolean truth(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static String increment(Object value) {
    return new BigInteger(value.toString()).add(BigInteger.ONE).toString();
  }

  private static Page page(String page, String size) {
    try {
      int p = page == null ? 1 : Integer.parseInt(page);
      int s = size == null ? 20 : Integer.parseInt(size);
      if (p < 1 || s < 1 || s > 100) throw invalid();
      return new Page(p, s, Math.multiplyExact((long) p - 1, s));
    } catch (NumberFormatException | ArithmeticException ex) {
      throw invalid();
    }
  }

  private static Map<String, Object> listing(
      List<Map<String, Object>> items, long total, Page page) {
    return Map.of(
        "items",
        items,
        "total",
        Long.toString(total),
        "page",
        Integer.toString(page.number()),
        "pageSize",
        Integer.toString(page.size()));
  }

  private record Page(int number, int size, long offset) {}

  private record Context(
      AuthPrincipal actor, Map<String, Object> config, Map<String, Map<String, Object>> users) {
    Map<String, Object> user(String id) {
      return users.get(id);
    }
  }

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_INPUT", 0);
  }

  private static AuthFailure missing() {
    return new AuthFailure(404, "NOT_FOUND", 0);
  }

  private static AuthFailure unauthorized() {
    return new AuthFailure(401, "INVALID_CREDENTIALS", 0);
  }

  private static AuthFailure forbidden() {
    return new AuthFailure(403, "FORBIDDEN", 0);
  }

  private static AuthFailure conflict(String code) {
    return new AuthFailure(409, code, 0);
  }
}
