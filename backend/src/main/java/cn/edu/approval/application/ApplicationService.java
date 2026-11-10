package cn.edu.approval.application;

import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Only fixed-business draft storage and authorized metadata queries, never flow execution. */
@Service
@Profile("db & !migrate")
public class ApplicationService {
  private static final BigInteger MAX = new BigInteger("18446744073709551615");
  private final JdbcTemplate jdbc;
  private final BusinessAccess access;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final TransactionTemplate tx;

  public ApplicationService(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      BusinessAccess access,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = jdbc;
    this.access = access;
    this.mapper = mapper;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Map<String, Object> create(AuthPrincipal actor, String requestKey, JsonNode body) {
    whitelist(body, Set.of("type", "templateVersionId", "sourceApplicationId"));
    String type = text(body, "type", true);
    if (!Set.of("PURCHASE", "TRAVEL", "REIMBURSEMENT", "CUSTOM").contains(type)) throw invalid();
    for (String optionalId : List.of("templateVersionId", "sourceApplicationId")) {
      if (body.has(optionalId)) id(text(body, optionalId, true));
    }
    byte[] fingerprint = fingerprint("POST", "/api/v1/applications", "CREATE_DRAFT", body);
    String key = key(requestKey);
    return safe(
        () -> {
          try {
            return tx.execute(
                status -> {
                  Map<String, Object> user = access.requireActive(actor);
                  Map<String, Object> replay = replay(actor, "CREATE_DRAFT", key, fingerprint);
                  if (replay != null) return replay;
                  if (type.equals("CUSTOM")) throw unavailable();
                  if (body.has("templateVersionId")) throw invalid();
                  if (body.has("sourceApplicationId")) {
                    String sourceId = id(text(body, "sourceApplicationId", true));
                    Map<String, Object> source = application(sourceId, false);
                    if (source == null
                        || !actor.userId().equals(source.get("applicant_id").toString()))
                      throw missing();
                    if (!Set.of("REJECTED", "WITHDRAWN").contains(source.get("status")))
                      throw conflict();
                    // Formal extension snapshots are not implemented by TASK05. Never invent copied
                    // data.
                    throw unavailable();
                  }
                  Object department = user.get("department_id");
                  if (department == null) throw new AuthFailure(403, "ACCOUNT_NOT_CONFIRMED", 0);
                  var holder = new GeneratedKeyHolder();
                  LocalDateTime now = now();
                  jdbc.update(
                      connection -> {
                        var statement =
                            connection.prepareStatement(
                                "INSERT INTO application(type,applicant_id,dept_id,status,row_version,created_at,updated_at) VALUES (?,?,?,'DRAFT',1,?,?)",
                                Statement.RETURN_GENERATED_KEYS);
                        statement.setString(1, type);
                        statement.setString(2, actor.userId());
                        statement.setString(3, department.toString());
                        statement.setObject(4, now);
                        statement.setObject(5, now);
                        return statement;
                      },
                      holder);
                  String target = id(holder.getKey().toString());
                  jdbc.update(
                      "INSERT INTO application_draft(application_id,payload,draft_schema_version,updated_at) VALUES (?,CAST(? AS JSON),1,?)",
                      target,
                      "{}",
                      now);
                  Map<String, Object> result = detail(application(target, false));
                  remember(actor, "CREATE_DRAFT", key, fingerprint, target, "CREATED", result);
                  return result;
                });
          } catch (DuplicateKeyException collision) {
            // The losing transaction (including its new draft) has already rolled back. Read winner
            // on a fresh READ_COMMITTED transaction; do not continue inside the failed transaction.
            return tx.execute(
                status -> {
                  access.requireActive(actor);
                  Map<String, Object> winner = replay(actor, "CREATE_DRAFT", key, fingerprint);
                  if (winner == null) throw conflict();
                  String target = winner.get("id").toString();
                  Map<String, Object> app = application(target, false);
                  if (app == null || !actor.userId().equals(app.get("applicant_id").toString()))
                    throw missing();
                  return winner;
                });
          }
        });
  }

  public Map<String, Object> save(
      AuthPrincipal actor, String target, String requestKey, JsonNode body) {
    String applicationId = id(target);
    whitelist(body, Set.of("expectedVersion", "title", "remark", "payload"));
    String title = limitedText(body, "title", 200);
    String remark = limitedText(body, "remark", 2000);
    String expected = id(text(body, "expectedVersion", true));
    String key = key(requestKey);
    byte[] fingerprint =
        fingerprint("PUT", "/api/v1/applications/" + applicationId + "/draft", "SAVE_DRAFT", body);
    return safe(
        () ->
            tx.execute(
                status -> {
                  access.requireActive(actor);
                  Map<String, Object> app = application(applicationId, true);
                  access.requireActive(
                      actor); // current committed account/session after obtaining the app lock
                  ownDraft(actor, app);
                  Map<String, Object> replay = replay(actor, "SAVE_DRAFT", key, fingerprint);
                  if (replay != null) return replay;
                  version(app, expected);
                  JsonNode payload = body.get("payload");
                  FixedDraftValidator.validate(app.get("type").toString(), payload);
                  JsonNode previous = draft(applicationId);
                  Map<String, String> oldKeys =
                      FixedDraftValidator.typedKeys(app.get("type").toString(), previous);
                  Map<String, String> newKeys =
                      FixedDraftValidator.typedKeys(app.get("type").toString(), payload);
                  for (var entry : newKeys.entrySet()) {
                    if (oldKeys.containsKey(entry.getKey())
                        && !oldKeys.get(entry.getKey()).equals(entry.getValue())) throw invalid();
                  }
                  Set<String> removed = new java.util.HashSet<>(oldKeys.keySet());
                  removed.removeAll(newKeys.keySet());
                  unbind(applicationId, removed, false);
                  jdbc.update(
                      "UPDATE application SET title=?,remark=?,row_version=row_version+1,updated_at=? WHERE id=?",
                      title,
                      remark,
                      now(),
                      applicationId);
                  jdbc.update(
                      "UPDATE application_draft SET payload=CAST(? AS JSON),updated_at=? WHERE application_id=?",
                      json(payload),
                      now(),
                      applicationId);
                  Map<String, Object> result = detail(application(applicationId, false));
                  remember(actor, "SAVE_DRAFT", key, fingerprint, applicationId, "SAVED", result);
                  return result;
                }));
  }

  public void delete(AuthPrincipal actor, String target, String requestKey, JsonNode body) {
    String applicationId = id(target);
    whitelist(body, Set.of("expectedVersion"));
    String expected = id(text(body, "expectedVersion", true));
    String key = key(requestKey);
    byte[] fingerprint =
        fingerprint("DELETE", "/api/v1/applications/" + applicationId, "DELETE_DRAFT", body);
    safe(
        () ->
            tx.execute(
                status -> {
                  access.requireActive(actor);
                  Map<String, Object> app = application(applicationId, true);
                  access.requireActive(actor);
                  if (app != null) ownDraft(actor, app);
                  Map<String, Object> replay = replay(actor, "DELETE_DRAFT", key, fingerprint);
                  if (replay != null)
                    return null; // same original actor/key/fingerprint, even after target removal
                  if (app == null) throw missing();
                  version(app, expected);
                  unbind(applicationId, Set.of(), true);
                  jdbc.update(
                      "DELETE FROM application_draft WHERE application_id=?", applicationId);
                  jdbc.update("DELETE FROM application WHERE id=?", applicationId);
                  remember(
                      actor, "DELETE_DRAFT", key, fingerprint, applicationId, "DELETED", Map.of());
                  return null;
                }));
  }

  public Map<String, Object> get(AuthPrincipal actor, String target) {
    String applicationId = id(target);
    return safe(
        () ->
            tx.execute(
                status -> {
                  access.requireActive(actor);
                  Map<String, Object> app = application(applicationId, false);
                  if (app == null || !access.canRead(actor, app)) throw missing();
                  return detail(app);
                }));
  }

  public Map<String, Object> list(AuthPrincipal actor, Map<String, String> filters) {
    Set<String> accepted =
        Set.of(
            "view",
            "type",
            "status",
            "submittedFrom",
            "submittedTo",
            "keyword",
            "departmentId",
            "page",
            "pageSize");
    if (!accepted.containsAll(filters.keySet())) throw invalid();
    String view = filters.getOrDefault("view", "mine");
    if (!Set.of("mine", "authorized").contains(view)) throw invalid();
    int page = page(filters.getOrDefault("page", "1"), Integer.MAX_VALUE);
    int pageSize = page(filters.getOrDefault("pageSize", "20"), 100);
    long offset = ((long) page - 1) * pageSize;
    return safe(
        () ->
            tx.execute(
                status -> {
                  access.requireActive(actor);
                  var scope = access.readScope(actor, "a");
                  StringBuilder where =
                      new StringBuilder(" WHERE (").append(scope.sql()).append(")");
                  List<Object> args = new ArrayList<>(scope.args());
                  if (view.equals("mine")) {
                    where.append(" AND a.applicant_id=?");
                    args.add(actor.userId());
                  }
                  addEnum(
                      filters,
                      "type",
                      Set.of("PURCHASE", "TRAVEL", "REIMBURSEMENT", "CUSTOM"),
                      where,
                      args);
                  addEnum(
                      filters,
                      "status",
                      Set.of("DRAFT", "IN_REVIEW", "APPROVED", "REJECTED", "WITHDRAWN"),
                      where,
                      args);
                  if (filters.containsKey("departmentId")) {
                    where.append(" AND a.dept_id=?");
                    args.add(id(filters.get("departmentId")));
                  }
                  if (filters.containsKey("submittedFrom") && filters.containsKey("submittedTo")) {
                    try {
                      if (OffsetDateTime.parse(filters.get("submittedFrom"))
                          .toInstant()
                          .isAfter(OffsetDateTime.parse(filters.get("submittedTo")).toInstant()))
                        throw invalid();
                    } catch (java.time.DateTimeException ex) {
                      throw invalid();
                    }
                  }
                  for (String name : List.of("submittedFrom", "submittedTo")) {
                    if (filters.containsKey(name)) {
                      LocalDateTime boundary;
                      try {
                        boundary =
                            OffsetDateTime.parse(filters.get(name))
                                .withOffsetSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime();
                      } catch (java.time.DateTimeException ex) {
                        throw invalid();
                      }
                      where.append(
                          name.equals("submittedFrom")
                              ? " AND a.submitted_at>=?"
                              : " AND a.submitted_at<?");
                      args.add(boundary);
                    }
                  }
                  if (filters.containsKey("keyword")) {
                    String keyword = filters.get("keyword");
                    if (keyword.codePointCount(0, keyword.length()) > 100) throw invalid();
                    String literal =
                        "%"
                            + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_")
                            + "%";
                    where.append(
                        " AND (a.application_no LIKE ? ESCAPE '!' OR a.title LIKE ? ESCAPE '!')");
                    args.add(literal);
                    args.add(literal);
                  }
                  Long count =
                      jdbc.queryForObject(
                          "SELECT COUNT(*) FROM application a" + where, Long.class, args.toArray());
                  args.add(pageSize);
                  args.add(offset);
                  List<Map<String, Object>> items =
                      jdbc
                          .queryForList(
                              "SELECT a.* FROM application a"
                                  + where
                                  + " ORDER BY COALESCE(a.submitted_at,a.updated_at) DESC,a.id DESC LIMIT ? OFFSET ?",
                              args.toArray())
                          .stream()
                          .map(this::summary)
                          .toList();
                  return Map.of(
                      "items",
                      items,
                      "total",
                      Long.toString(count == null ? 0 : count),
                      "page",
                      page,
                      "pageSize",
                      pageSize);
                }));
  }

  private void unbind(String applicationId, Set<String> removed, boolean all) {
    List<Map<String, Object>> bindings =
        jdbc.queryForList(
            "SELECT id,file_id,draft_target_key FROM file_binding WHERE application_id=? ORDER BY file_id,id",
            applicationId);
    List<Map<String, Object>> affected =
        bindings.stream()
            .filter(
                binding ->
                    all
                        || (binding.get("draft_target_key") != null
                            && removed.contains(binding.get("draft_target_key").toString())))
            .toList();
    // Preserve shared file objects. Queue is only a candidate; TASK06 worker must check references
    // after acquiring its file lock, never delete bytes or mark objects here.
    affected.stream()
        .map(binding -> binding.get("file_id").toString())
        .distinct()
        .forEach(
            file -> jdbc.queryForList("SELECT id FROM file_object WHERE id=? FOR UPDATE", file));
    for (Map<String, Object> binding : affected)
      jdbc.update(
          "DELETE FROM file_binding WHERE id=? AND application_id=?",
          binding.get("id"),
          applicationId);
    affected.stream()
        .map(binding -> binding.get("file_id").toString())
        .distinct()
        .forEach(
            file ->
                jdbc.update(
                    "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)",
                    file,
                    now(),
                    now(),
                    now()));
  }

  private Map<String, Object> application(String target, boolean lock) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM application WHERE id=?" + (lock ? " FOR UPDATE" : ""), target);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private JsonNode draft(String target) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT payload FROM application_draft WHERE application_id=?", String.class, target);
    if (rows.isEmpty()) throw unavailable();
    return parse(rows.get(0));
  }

  private Map<String, Object> summary(Map<String, Object> app) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name : List.of("id", "type", "title", "remark", "status"))
      result.put(name, app.get(name) == null ? null : app.get(name).toString());
    result.put("rowVersion", app.get("row_version").toString());
    result.put("applicationNo", app.get("application_no"));
    result.put("applicantId", app.get("applicant_id").toString());
    result.put("departmentId", app.get("dept_id").toString());
    result.put(
        "amount",
        app.get("amount") == null
            ? null
            : new java.math.BigDecimal(app.get("amount").toString()).setScale(2).toPlainString());
    result.put("submittedAt", utc(app.get("submitted_at")));
    result.put("updatedAt", utc(app.get("updated_at")));
    result.put(
        "sourceApplicationId",
        app.get("source_application_id") == null
            ? null
            : app.get("source_application_id").toString());
    result.put(
        "allowedActions",
        app.get("status").equals("DRAFT") ? List.of("SAVE_DRAFT", "DELETE_DRAFT") : List.of());
    return result;
  }

  private Map<String, Object> detail(Map<String, Object> app) {
    Map<String, Object> result = summary(app);
    String target = app.get("id").toString();
    if (app.get("status").equals("DRAFT")) result.put("payload", draft(target));
    else result.put("bodyAvailable", false);
    List<Map<String, Object>> bindings =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) AS id,CAST(file_id AS CHAR) AS fileId,purpose,draft_target_key AS draftTargetKey,field_key AS fieldKey,receipt_number AS receiptNumber FROM file_binding WHERE application_id=? ORDER BY id",
            target);
    result.put("bindings", bindings);
    return result;
  }

  private Map<String, Object> replay(
      AuthPrincipal actor, String action, String key, byte[] fingerprint) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT payload_hash,target_id,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?",
            actor.userId(),
            action,
            key);
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.get(0);
    if (!MessageDigest.isEqual(fingerprint, (byte[]) row.get("payload_hash"))) throw conflict();
    if (action.equals("CREATE_DRAFT")) {
      Map<String, Object> target = application(row.get("target_id").toString(), false);
      if (target == null || !actor.userId().equals(target.get("applicant_id").toString()))
        throw missing();
    }
    JsonNode result = parse(row.get("result_summary").toString());
    return mapper.convertValue(
        result, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  private void remember(
      AuthPrincipal actor,
      String action,
      String key,
      byte[] fingerprint,
      String target,
      String code,
      Map<String, Object> result) {
    jdbc.update(
        "INSERT INTO request_record(actor_id,action,request_key,payload_hash,target_id,result_code,result_summary,created_at) VALUES (?,?,?,?,?,?,CAST(? AS JSON),?)",
        actor.userId(),
        action,
        key,
        fingerprint,
        target,
        code,
        json(mapper.valueToTree(result)),
        now());
  }

  public byte[] fingerprint(String method, String path, String action, JsonNode body) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(
              (method + "\n" + path + "\n" + action + "\n" + json(canonical(body)))
                  .getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private JsonNode canonical(JsonNode body) {
    if (body.isObject()) {
      ObjectNode result = mapper.createObjectNode();
      Map<String, JsonNode> sorted = new TreeMap<>();
      body.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
      sorted.forEach(result::set);
      return result;
    }
    if (body.isArray()) {
      var array = mapper.createArrayNode();
      body.forEach(item -> array.add(canonical(item)));
      return array;
    }
    return body;
  }

  private static void ownDraft(AuthPrincipal actor, Map<String, Object> app) {
    if (app == null || !actor.userId().equals(app.get("applicant_id").toString())) throw missing();
    if (!"DRAFT".equals(app.get("status"))) throw new AuthFailure(403, "DRAFT_WRITE_FORBIDDEN", 0);
  }

  private static void version(Map<String, Object> app, String expected) {
    if (!app.get("row_version").toString().equals(expected)) throw conflict();
    if (new BigInteger(expected).equals(MAX)) throw conflict();
  }

  static String id(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]{0,19}")
        || new BigInteger(value).compareTo(MAX) > 0) throw invalid();
    return value;
  }

  static String key(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value))
        throw invalid();
      return value.toLowerCase(java.util.Locale.ROOT);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
  }

  private static void whitelist(JsonNode body, Set<String> fields) {
    if (body == null || !body.isObject()) throw invalid();
    body.fieldNames()
        .forEachRemaining(
            field -> {
              if (!fields.contains(field)) throw invalid();
            });
  }

  private static String text(JsonNode body, String field, boolean required) {
    JsonNode value = body.get(field);
    if (value == null) {
      if (required) throw invalid();
      return "";
    }
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static String limitedText(JsonNode body, String field, int maximum) {
    String value = text(body, field, true);
    if (!FixedDraftValidator.unicode(value) || value.codePointCount(0, value.length()) > maximum)
      throw invalid();
    return value;
  }

  private static int page(String value, int maximum) {
    try {
      if (!value.matches("[1-9][0-9]{0,9}")) throw invalid();
      int number = Integer.parseInt(value);
      if (number > maximum) throw invalid();
      return number;
    } catch (NumberFormatException ex) {
      throw invalid();
    }
  }

  private static void addEnum(
      Map<String, String> filters,
      String name,
      Set<String> legal,
      StringBuilder where,
      List<Object> args) {
    if (filters.containsKey(name)) {
      String value = filters.get(name);
      if (!legal.contains(value)) throw invalid();
      where.append(" AND a.").append(name).append("=?");
      args.add(value);
    }
  }

  private String json(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException ex) {
      throw invalid();
    }
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException ex) {
      throw unavailable();
    }
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static Object utc(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC).toString();
    if (value instanceof java.sql.Timestamp timestamp)
      return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    return value.toString();
  }

  private <T> T safe(Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (DuplicateKeyException ex) {
      throw conflict();
    } catch (DataAccessException | TransactionException ex) {
      throw new AuthFailure(503, "DATABASE_UNAVAILABLE", 0);
    }
  }

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_APPLICATION_INPUT", 0);
  }

  private static AuthFailure missing() {
    return new AuthFailure(404, "APPLICATION_NOT_FOUND", 0);
  }

  private static AuthFailure conflict() {
    return new AuthFailure(409, "APPLICATION_CONFLICT", 0);
  }

  private static AuthFailure unavailable() {
    return new AuthFailure(503, "APPLICATION_FEATURE_UNAVAILABLE", 0);
  }
}
