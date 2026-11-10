package cn.edu.approval.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Database identity boundary. HTTP owns origin/CSRF, credential selection and account gates. */
@Service
@Profile("db & !migrate")
public class AuthService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final TransactionTemplate independent;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
  private final String dummyHash;

  public AuthService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    dummyHash = encoder.encode(randomToken());
  }

  public Map<String, Object> register(
      String username,
      String name,
      String email,
      String password,
      String requestedDepartmentId,
      String ip) {
    return safe(
        () -> {
          consumeIp("register", ip, 10);
          String normalized = username(username);
          String normalizedEmail = email(email);
          validateName(name);
          validatePassword(password);
          String department = id(requestedDepartmentId);
          String hash = encoder.encode(password);
          return tx.execute(
              s -> {
                enabledDepartment(department);
                Instant now = clock.instant();
                jdbc.update(
                    "INSERT INTO `user` (username,name,email,password_hash,requested_department_id,"
                        + "status,must_change_password,row_version,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'PENDING',0,1,?,?)",
                    normalized,
                    name,
                    normalizedEmail,
                    hash,
                    department,
                    stamp(now),
                    stamp(now));
                String userId =
                    jdbc.queryForObject(
                        "SELECT CAST(id AS CHAR) FROM `user` WHERE username=?",
                        String.class,
                        normalized);
                jdbc.update(
                    "INSERT INTO inbox_state (user_id,last_delivery_seq,revision,updated_at)"
                        + " VALUES (?,0,0,?)",
                    userId,
                    stamp(now));
                return Map.of("userId", userId, "status", "PENDING", "mustChangePassword", false);
              });
        });
  }

  public Map<String, Object> login(
      String username, String password, String ip, String clientType, String previousToken) {
    return safe(
        () -> {
          client(clientType);
          consumeIp("login", ip, 20);
          String normalized =
              username != null && username.chars().allMatch(character -> character <= 0x7f)
                  ? username.strip().toLowerCase(Locale.ROOT)
                  : "";
          // Account bucket serializes attempts across nodes. Return a failure value, then throw
          // AFTER
          // commit: failed credentials must not roll back their own durable failure counter.
          Object result =
              independent.execute(
                  s -> {
                    Instant now = clock.instant();
                    Bucket bucket = bucket("account:" + normalized, now, 900);
                    int failures = lockBucket(bucket);
                    if (failures >= 5) return limited(bucket, now);
                    List<Map<String, Object>> candidates =
                        jdbc.queryForList("SELECT id FROM `user` WHERE username=?", normalized);
                    List<Map<String, Object>> previous = tokenRows(previousToken);
                    List<String> ids = new ArrayList<>();
                    if (!candidates.isEmpty()) ids.add(candidates.get(0).get("id").toString());
                    if (!previous.isEmpty()) ids.add(previous.get(0).get("user_id").toString());
                    ids.stream()
                        .distinct()
                        .sorted(
                            (a, b) ->
                                new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b)))
                        .forEach(
                            userId ->
                                jdbc.queryForList(
                                    "SELECT id FROM `user` WHERE id=? FOR UPDATE", userId));
                    List<Map<String, Object>> users =
                        jdbc.queryForList(
                            "SELECT * FROM `user` WHERE username=? FOR UPDATE", normalized);
                    Map<String, Object> user = users.isEmpty() ? null : users.get(0);
                    boolean validInput = validPassword(password);
                    boolean matches =
                        encoder.matches(
                            validInput ? password : "invalid-password",
                            user == null ? dummyHash : user.get("password_hash").toString());
                    if (!matches
                        || !validInput
                        || user == null
                        || "DISABLED".equals(user.get("status"))) {
                      jdbc.update(
                          "UPDATE rate_limit_bucket SET counter=counter+1 WHERE bucket_key=? AND window_start=?",
                          bucket.key(),
                          stamp(bucket.start()));
                      return unauthorized();
                    }
                    String userId = user.get("id").toString();
                    if (!previous.isEmpty()) {
                      jdbc.update(
                          "UPDATE auth_session SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                          stamp(now),
                          digest(previousToken));
                    }
                    String token = randomToken();
                    jdbc.update(
                        "INSERT INTO auth_session (user_id,token_hash,created_at,last_seen_at,"
                            + "absolute_expires_at,client_type) VALUES (?,?,?,?,?,?)",
                        userId,
                        digest(token),
                        stamp(now),
                        stamp(now),
                        stamp(now.plusSeconds(604800)),
                        clientType);
                    jdbc.update(
                        "DELETE FROM rate_limit_bucket WHERE bucket_key=? AND window_start=?",
                        bucket.key(),
                        stamp(bucket.start()));
                    return Map.of(
                        "token",
                        token,
                        "userId",
                        userId,
                        "status",
                        user.get("status"),
                        "mustChangePassword",
                        truth(user.get("must_change_password")));
                  });
          if (result instanceof AuthFailure failure) throw failure;
          @SuppressWarnings("unchecked")
          Map<String, Object> response = (Map<String, Object>) result;
          return response;
        });
  }

  public AuthPrincipal authenticate(String token, String clientType) {
    return safe(
        () ->
            tx.execute(
                s -> {
                  client(clientType);
                  List<Map<String, Object>> sessions = tokenRows(token);
                  if (sessions.isEmpty()) throw unauthorized();
                  Map<String, Object> located = sessions.get(0);
                  Map<String, Object> user = lockedUser(located.get("user_id").toString());
                  Map<String, Object> session = lockedSession(located.get("id").toString());
                  validateSession(user, session, clientType);
                  jdbc.update(
                      "UPDATE auth_session SET last_seen_at=? WHERE id=?",
                      stamp(clock.instant()),
                      session.get("id"));
                  return new AuthPrincipal(
                      user.get("id").toString(),
                      session.get("id").toString(),
                      user.get("status").toString(),
                      truth(user.get("must_change_password")),
                      clientType);
                }));
  }

  public void logout(String token) {
    safe(
        () ->
            tx.execute(
                s -> {
                  List<Map<String, Object>> rows = tokenRows(token);
                  if (!rows.isEmpty()) {
                    lockedUser(rows.get(0).get("user_id").toString());
                    jdbc.update(
                        "UPDATE auth_session SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                        stamp(clock.instant()),
                        digest(token));
                  }
                  return null;
                }));
  }

  public Map<String, Object> profile(AuthPrincipal p) {
    return safe(() -> tx.execute(s -> publicProfile(current(p))));
  }

  public Map<String, Object> updateProfile(AuthPrincipal p, String name, String email) {
    throw invalid();
  }

  /** HTTP must use this overload when exposing the planned versioned PATCH contract. */
  public Map<String, Object> updateProfile(
      AuthPrincipal p, String name, String email, String expectedVersion) {
    return safe(
        () -> {
          validateName(name);
          String normalized = email(email);
          return tx.execute(
              s -> {
                Map<String, Object> user = current(p);
                requirePasswordChanged(user);
                version(user.get("row_version"), expectedVersion);
                jdbc.update(
                    "UPDATE `user` SET name=?,email=?,row_version=row_version+1,updated_at=? WHERE id=?",
                    name,
                    normalized,
                    stamp(clock.instant()),
                    p.userId());
                return publicProfile(lockedUser(p.userId()));
              });
        });
  }

  public void changePassword(AuthPrincipal p, String oldPassword, String newPassword) {
    safe(
        () -> {
          validatePassword(newPassword);
          String hash = encoder.encode(newPassword);
          return tx.execute(
              s -> {
                Map<String, Object> user = current(p);
                if (!validPassword(oldPassword)
                    || !encoder.matches(oldPassword, user.get("password_hash").toString()))
                  throw unauthorized();
                setPassword(p.userId(), hash, false);
                return null;
              });
        });
  }

  public List<Map<String, Object>> departments() {
    return safe(
        () ->
            jdbc.queryForList(
                "SELECT CAST(id AS CHAR) AS id,name FROM department WHERE enabled=1 ORDER BY id"));
  }

  /** Versioned confirmation request, not an administrative status API. */
  public Map<String, Object> resubmit(
      AuthPrincipal p,
      String requestedDepartmentId,
      String expectedVersion,
      String expectedConfigVersion) {
    return safe(
        () ->
            tx.execute(
                s -> {
                  Map<String, Object> config =
                      jdbc.queryForMap(
                          "SELECT row_version FROM system_config WHERE id=1 FOR UPDATE");
                  Map<String, Object> user = current(p);
                  requirePasswordChanged(user);
                  version(config.get("row_version"), expectedConfigVersion);
                  version(user.get("row_version"), expectedVersion);
                  if (!"REJECTED".equals(user.get("status"))) throw conflict();
                  String department = id(requestedDepartmentId);
                  enabledDepartment(department);
                  jdbc.update(
                      "UPDATE `user` SET requested_department_id=?,status='PENDING',rejection_reason=NULL,"
                          + "row_version=row_version+1,updated_at=? WHERE id=?",
                      department,
                      stamp(clock.instant()),
                      p.userId());
                  jdbc.update(
                      "UPDATE system_config SET row_version=row_version+1,updated_at=? WHERE id=1",
                      stamp(clock.instant()));
                  Map<String, Object> result = publicProfile(lockedUser(p.userId()));
                  result.put(
                      "configVersion",
                      jdbc.queryForObject(
                          "SELECT CAST(row_version AS CHAR) FROM system_config WHERE id=1",
                          String.class));
                  return result;
                }));
  }

  /**
   * TASK04-only hook: caller must already hold config/user locks and validate versions,
   * administrator, last-admin, dependencies and audit in the SAME transaction. Not an HTTP API.
   */
  void resetPassword(String userId, String temporaryPassword) {
    requireTransaction();
    validatePassword(temporaryPassword);
    lockedUser(id(userId));
    setPassword(userId, encoder.encode(temporaryPassword), true);
  }

  /** TASK04-only hook; same transaction/preconditions as resetPassword. */
  void disable(String userId) {
    requireTransaction();
    lockedUser(id(userId));
    jdbc.update(
        "UPDATE `user` SET status='DISABLED',row_version=row_version+1,updated_at=? WHERE id=?",
        stamp(clock.instant()),
        userId);
    revokeAll(userId);
  }

  private void setPassword(String userId, String hash, boolean forced) {
    jdbc.update(
        "UPDATE `user` SET password_hash=?,must_change_password=?,row_version=row_version+1,"
            + "updated_at=? WHERE id=?",
        hash,
        forced ? 1 : 0,
        stamp(clock.instant()),
        userId);
    revokeAll(userId);
  }

  private void revokeAll(String userId) {
    jdbc.update(
        "UPDATE auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
        stamp(clock.instant()),
        userId);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Identity management hook requires enclosing management transaction");
  }

  private Map<String, Object> current(AuthPrincipal p) {
    if (p == null) throw unauthorized();
    Map<String, Object> user = lockedUser(id(p.userId()));
    Map<String, Object> session = lockedSession(id(p.sessionId()));
    validateSession(user, session, p.clientType());
    return user;
  }

  private Map<String, Object> lockedUser(String userId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM `user` WHERE id=? FOR UPDATE", userId);
    if (rows.isEmpty()) throw unauthorized();
    return rows.get(0);
  }

  private Map<String, Object> lockedSession(String sessionId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id,user_id,client_type,revoked_at,"
                + "DATE_FORMAT(last_seen_at,'%Y-%m-%dT%H:%i:%s.%f') AS last_seen_at,"
                + "DATE_FORMAT(absolute_expires_at,'%Y-%m-%dT%H:%i:%s.%f') AS absolute_expires_at "
                + "FROM auth_session WHERE id=? FOR UPDATE",
            sessionId);
    if (rows.isEmpty()) throw unauthorized();
    return rows.get(0);
  }

  private void validateSession(Map<String, Object> user, Map<String, Object> session, String type) {
    Instant now = clock.instant();
    if (!user.get("id").toString().equals(session.get("user_id").toString())
        || !session.get("client_type").equals(type)
        || session.get("revoked_at") != null
        || "DISABLED".equals(user.get("status"))
        || !now.isBefore(instant(session.get("absolute_expires_at")))
        || !now.isBefore(instant(session.get("last_seen_at")).plusSeconds(1800)))
      throw unauthorized();
  }

  private List<Map<String, Object>> tokenRows(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return List.of();
    return jdbc.queryForList(
        "SELECT id,user_id FROM auth_session WHERE token_hash=?", digest(token));
  }

  private Map<String, Object> publicProfile(Map<String, Object> user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("userId", user.get("id").toString());
    for (String key : List.of("username", "name", "email", "status"))
      result.put(key, user.get(key));
    result.put("requestedDepartmentId", user.get("requested_department_id").toString());
    result.put(
        "departmentId",
        user.get("department_id") == null ? null : user.get("department_id").toString());
    result.put("rejectionReason", user.get("rejection_reason"));
    result.put("rowVersion", user.get("row_version").toString());
    result.put("mustChangePassword", truth(user.get("must_change_password")));
    if ("REJECTED".equals(user.get("status")))
      result.put(
          "configVersion",
          jdbc.queryForObject(
              "SELECT CAST(row_version AS CHAR) FROM system_config WHERE id=1", String.class));
    return result;
  }

  private void enabledDepartment(String department) {
    // Current locking read prevents a concurrent disable from accepting a stale enabled value.
    if (jdbc.queryForList(
            "SELECT id FROM department WHERE id=? AND enabled=1 FOR SHARE", department)
        .isEmpty()) throw invalid();
  }

  private void consumeIp(String action, String ip, int limit) {
    AuthFailure failure =
        independent.execute(
            s -> {
              Instant now = clock.instant();
              Bucket bucket = bucket("ip:" + action + ":" + (ip == null ? "unknown" : ip), now, 60);
              int count = lockBucket(bucket);
              if (count >= limit) return limited(bucket, now);
              jdbc.update(
                  "UPDATE rate_limit_bucket SET counter=counter+1 WHERE bucket_key=? AND window_start=?",
                  bucket.key(),
                  stamp(bucket.start()));
              return null;
            });
    if (failure != null) throw failure;
  }

  private int lockBucket(Bucket bucket) {
    jdbc.update(
        "INSERT INTO rate_limit_bucket (bucket_key,window_start,counter,expires_at) VALUES (?,?,0,?)"
            + " ON DUPLICATE KEY UPDATE counter=counter",
        bucket.key(),
        stamp(bucket.start()),
        stamp(bucket.end()));
    Integer count =
        jdbc.queryForObject(
            "SELECT counter FROM rate_limit_bucket WHERE bucket_key=? AND window_start=? FOR UPDATE",
            Integer.class,
            bucket.key(),
            stamp(bucket.start()));
    return count == null ? 0 : count;
  }

  private static Bucket bucket(String key, Instant now, int seconds) {
    Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    return new Bucket(digest(key), start, start.plusSeconds(seconds));
  }

  private record Bucket(byte[] key, Instant start, Instant end) {}

  private static AuthFailure limited(Bucket bucket, Instant now) {
    int retry = (int) Math.max(1, bucket.end().getEpochSecond() - now.getEpochSecond());
    return new AuthFailure(429, "RATE_LIMITED", "Too many attempts", retry);
  }

  private <T> T safe(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DuplicateKeyException exception) {
      throw conflict();
    } catch (DataAccessException | org.springframework.transaction.TransactionException exception) {
      throw new AuthFailure(503, "DATABASE_UNAVAILABLE", "Service temporarily unavailable");
    }
  }

  static String username(String value) {
    if (value == null || !value.chars().allMatch(character -> character <= 0x7f)) throw invalid();
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9_]{4,64}")) throw invalid();
    return normalized;
  }

  static String email(String value) {
    if (value == null || !value.chars().allMatch(character -> character <= 0x7f)) throw invalid();
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (normalized.length() > 254
        || !normalized.matches(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+"))
      throw invalid();
    return normalized;
  }

  static void validateName(String value) {
    if (value == null
        || value.isBlank()
        || !validUnicode(value)
        || value.codePointCount(0, value.length()) > 80) throw invalid();
  }

  static void validatePassword(String value) {
    if (!validPassword(value)) throw invalid();
  }

  private static boolean validPassword(String value) {
    return value != null
        && validUnicode(value)
        && value.codePointCount(0, value.length()) >= 8
        && value.codePointCount(0, value.length()) <= 64;
  }

  private static boolean validUnicode(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }

  private static String id(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]{0,19}")
        || new java.math.BigInteger(value)
                .compareTo(new java.math.BigInteger("18446744073709551615"))
            > 0) throw invalid();
    return value;
  }

  private static void version(Object actual, String expected) {
    if (!actual.toString().equals(id(expected))) throw conflict();
  }

  private static void requirePasswordChanged(Map<String, Object> user) {
    if (truth(user.get("must_change_password")))
      throw new AuthFailure(403, "PASSWORD_CHANGE_REQUIRED", "Password change required");
  }

  private static void client(String type) {
    if (!"WEB".equals(type) && !"MINI_PROGRAM".equals(type)) throw invalid();
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static java.time.LocalDateTime stamp(Instant instant) {
    return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
  }

  private static Instant instant(Object value) {
    if (value instanceof String text)
      return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
    if (value instanceof java.time.LocalDateTime local)
      return local.toInstant(java.time.ZoneOffset.UTC);
    throw new IllegalStateException("Unsupported database timestamp type");
  }

  private static boolean truth(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
  }

  private static AuthFailure unauthorized() {
    return new AuthFailure(401, "INVALID_CREDENTIALS", "Invalid credentials or session");
  }

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_INPUT", "Invalid identity input");
  }

  private static AuthFailure conflict() {
    return new AuthFailure(409, "IDENTITY_CONFLICT", "Identity or version conflict");
  }
}
