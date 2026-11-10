package cn.edu.approval.file;

import cn.edu.approval.application.ApplicationService;
import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
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

@Service
@Profile("db & !migrate")
public class FileService {
  private static final BigInteger MAX = new BigInteger("18446744073709551615");
  private final JdbcTemplate jdbc;
  private final BusinessAccess access;
  private final ApplicationService applications;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final PrivateFileStore store;
  private final TransactionTemplate tx;
  private final int maxCount;
  private final long maxTotal;

  public FileService(
      JdbcTemplate jdbc,
      BusinessAccess access,
      ApplicationService applications,
      ObjectMapper mapper,
      Clock clock,
      PrivateFileStore store,
      PlatformTransactionManager manager,
      @Value("${app.files.max-count:20}") int maxCount,
      @Value("${app.files.max-total-bytes:104857600}") long maxTotal) {
    this.jdbc = jdbc;
    this.access = access;
    this.applications = applications;
    this.mapper = mapper;
    this.clock = clock;
    this.store = store;
    this.maxCount = maxCount;
    this.maxTotal = maxTotal;
    if (maxCount < 1 || maxCount > 20 || maxTotal < 1 || maxTotal > 104857600)
      throw new IllegalArgumentException("Invalid binding limit");
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Called before reading even multipart headers. Failed uploads consume the account budget. */
  public void beginUpload(AuthPrincipal actor) {
    safe(
        () -> {
          access.requireActive(actor);
          return null;
        });
    LocalDateTime window = now().truncatedTo(ChronoUnit.MINUTES);
    byte[] bucket =
        PrivateFileStore.digest()
            .digest(("upload:" + actor.userId()).getBytes(StandardCharsets.UTF_8));
    Long count =
        safe(
            () ->
                tx.execute(
                    status -> {
                      jdbc.update(
                          "INSERT INTO rate_limit_bucket(bucket_key,window_start,counter,expires_at) VALUES (?,?,1,?) ON DUPLICATE KEY UPDATE counter=LEAST(counter+1,2147483647)",
                          bucket,
                          window,
                          window.plusMinutes(2));
                      return jdbc.queryForObject(
                          "SELECT counter FROM rate_limit_bucket WHERE bucket_key=? AND window_start=?",
                          Long.class,
                          bucket,
                          window);
                    }));
    if (count != null && count > 30)
      throw new AuthFailure(429, "UPLOAD_RATE_LIMITED", 60 - now().getSecond());
  }

  public Map<String, Object> upload(
      AuthPrincipal actor, String name, String media, InputStream input) {
    String type = PrivateFileStore.media(name, media);
    String key = store.newKey();
    String fileId =
        safe(
            () ->
                tx.execute(
                    status -> {
                      access.requireActive(actor);
                      return insert(
                          "INSERT INTO file_object(owner_id,storage_key,original_name,media_type,status,upload_lease_until,created_at,updated_at) VALUES (?,?,?,?,'UPLOADING',?,?,?)",
                          actor.userId(),
                          key,
                          name,
                          type,
                          now().plusMinutes(15),
                          now(),
                          now());
                    }));
    try {
      PrivateFileStore.Stored bytes = store.write(key, type, input);
      uploadDeadline(input);
      store.publish(key);
      return safe(
          () ->
              tx.execute(
                  status -> {
                    Map<String, Object> row = object(fileId, true);
                    access.requireActive(actor);
                    uploadDeadline(input);
                    if (row == null
                        || !"UPLOADING".equals(row.get("status"))
                        || !time(row.get("upload_lease_until")).isAfter(now())) throw conflict();
                    jdbc.update(
                        "UPDATE file_object SET status='READY',byte_size=?,sha256=?,upload_lease_until=NULL,integrity_status='OK',verified_at=?,updated_at=? WHERE id=?",
                        bytes.size(),
                        bytes.sha256(),
                        now(),
                        now(),
                        fileId);
                    uploadDeadline(input);
                    return Map.of(
                        "fileId",
                        fileId,
                        "originalName",
                        name,
                        "mediaType",
                        type,
                        "byteSize",
                        Long.toString(bytes.size()),
                        "sha256",
                        bytes.hex());
                  }));
    } catch (IOException | RuntimeException ex) {
      // Durable enqueue, never delete a path in a database transaction. Recovery also scans leases.
      try {
        safe(
            () ->
                tx.execute(
                    status -> {
                      enqueue(fileId);
                      return null;
                    }));
      } catch (RuntimeException ignored) {
        /* lease expiry is the independent crash-recovery path */
      }
      if (ex instanceof AuthFailure failure) throw failure;
      throw PrivateFileStore.unavailable();
    }
  }

  private static void uploadDeadline(InputStream input) {
    if (input instanceof UploadBody body) body.assertWithinDeadline();
  }

  public Map<String, Object> bind(
      AuthPrincipal actor, String target, String requestKey, JsonNode body) {
    String appId = id(target);
    whitelist(
        body,
        Set.of(
            "expectedVersion", "fileId", "purpose", "draftTargetKey", "fieldKey", "receiptNumber"));
    String expected = id(text(body, "expectedVersion", true));
    String fileId = id(text(body, "fileId", true));
    String purpose = text(body, "purpose", true);
    String request = key(requestKey);
    byte[] fingerprint =
        applications.fingerprint(
            "POST", "/api/v1/applications/" + appId + "/bindings", "BIND_FILE", body);
    // Do authorization before disk work and again under the application lock.
    Map<String, Object> priorResult =
        safe(
            () ->
                tx.execute(
                    status -> {
                      access.requireActive(actor);
                      own(actor, application(appId, false));
                      Map<String, Object> replay = replay(actor, "BIND_FILE", request, fingerprint);
                      if (replay != null) return replay;
                      Map<String, Object> file = object(fileId, false);
                      if (file == null || !actor.userId().equals(file.get("owner_id").toString()))
                        throw missing();
                      if (!"READY".equals(file.get("status"))) throw PrivateFileStore.unavailable();
                      return null;
                    }));
    if (priorResult != null) return priorResult;
    checkIntegrity(fileId);
    return safe(
        () ->
            tx.execute(
                status -> {
                  Map<String, Object> app = application(appId, true);
                  access.requireActive(actor);
                  own(actor, app);
                  Map<String, Object> replay = replay(actor, "BIND_FILE", request, fingerprint);
                  if (replay != null) return replay;
                  version(app, expected);
                  Map<String, Object> file = object(fileId, true);
                  if (file == null || !actor.userId().equals(file.get("owner_id").toString()))
                    throw missing();
                  if (!"READY".equals(file.get("status"))
                      || "DAMAGED".equals(file.get("integrity_status")))
                    throw PrivateFileStore.unavailable();
                  validatePurpose(appId, app.get("type").toString(), purpose, body);
                  List<Map<String, Object>> bound =
                      jdbc.queryForList(
                          "SELECT f.id,f.byte_size,f.sha256 FROM file_binding b JOIN file_object f ON f.id=b.file_id WHERE b.application_id=?",
                          appId);
                  long total = Long.parseLong(file.get("byte_size").toString());
                  for (Map<String, Object> prior : bound) {
                    if (fileId.equals(prior.get("id").toString())
                        || java.security.MessageDigest.isEqual(
                            (byte[]) file.get("sha256"), (byte[]) prior.get("sha256")))
                      throw new AuthFailure(409, "DUPLICATE_FILE", 0);
                    total += Long.parseLong(prior.get("byte_size").toString());
                  }
                  if (bound.size() >= maxCount || total > maxTotal)
                    throw new AuthFailure(413, "APPLICATION_FILE_LIMIT", 0);
                  String binding =
                      insert(
                          "INSERT INTO file_binding(application_id,file_id,purpose,draft_target_key,field_key,receipt_number,receipt_sha256,created_at) VALUES (?,?,?,?,?,?,?,?)",
                          appId,
                          fileId,
                          purpose,
                          nullable(body, "draftTargetKey"),
                          nullable(body, "fieldKey"),
                          nullable(body, "receiptNumber"),
                          purpose.equals("EXPENSE_RECEIPT") ? file.get("sha256") : null,
                          now());
                  advance(appId);
                  Map<String, Object> result =
                      Map.of(
                          "bindingId",
                          binding,
                          "rowVersion",
                          new BigInteger(expected).add(BigInteger.ONE).toString());
                  remember(actor, "BIND_FILE", request, fingerprint, appId, result);
                  return result;
                }));
  }

  public Map<String, Object> unbind(
      AuthPrincipal actor, String target, String binding, String requestKey, JsonNode body) {
    String appId = id(target);
    String bindingId = id(binding);
    String request = key(requestKey);
    whitelist(body, Set.of("expectedVersion"));
    String expected = id(text(body, "expectedVersion", true));
    byte[] fingerprint =
        applications.fingerprint(
            "DELETE",
            "/api/v1/applications/" + appId + "/bindings/" + bindingId,
            "UNBIND_FILE",
            body);
    return safe(
        () ->
            tx.execute(
                status -> {
                  Map<String, Object> app = application(appId, true);
                  access.requireActive(actor);
                  own(actor, app);
                  Map<String, Object> replay = replay(actor, "UNBIND_FILE", request, fingerprint);
                  if (replay != null) return replay;
                  version(app, expected);
                  List<Map<String, Object>> rows =
                      jdbc.queryForList(
                          "SELECT file_id FROM file_binding WHERE id=? AND application_id=?",
                          bindingId,
                          appId);
                  if (rows.isEmpty()) throw missing();
                  String file = rows.get(0).get("file_id").toString();
                  object(file, true);
                  jdbc.update(
                      "DELETE FROM file_binding WHERE id=? AND application_id=?", bindingId, appId);
                  enqueue(file);
                  advance(appId);
                  Map<String, Object> result =
                      Map.of("rowVersion", new BigInteger(expected).add(BigInteger.ONE).toString());
                  remember(actor, "UNBIND_FILE", request, fingerprint, appId, result);
                  return result;
                }));
  }

  public Map<String, Object> download(AuthPrincipal actor, String target, String context) {
    String fileId = id(target);
    String appId = context == null ? null : id(context);
    Map<String, Object> file =
        safe(
            () ->
                tx.execute(
                    status -> {
                      access.requireActive(actor);
                      Map<String, Object> row = object(fileId, false);
                      if (row == null) throw missing();
                      Long count =
                          jdbc.queryForObject(
                              "SELECT COUNT(*) FROM file_binding WHERE file_id=?",
                              Long.class,
                              fileId);
                      if (count != null && count > 0) {
                        if (appId == null) throw missing();
                        Map<String, Object> app = application(appId, false);
                        Long binding =
                            jdbc.queryForObject(
                                "SELECT COUNT(*) FROM file_binding WHERE application_id=? AND file_id=?",
                                Long.class,
                                appId,
                                fileId);
                        if (app == null
                            || binding == null
                            || binding == 0
                            || !access.canRead(actor, app)) throw missing();
                      } else if (!actor.userId().equals(row.get("owner_id").toString()))
                        throw missing();
                      if (!"READY".equals(row.get("status"))) throw missing();
                      return row;
                    }));
    checkIntegrity(fileId);
    return file;
  }

  /** Hash outside business locks; persist a safe incident marker, preserve every binding. */
  public void checkIntegrity(String fileId) {
    Map<String, Object> file = safe(() -> object(fileId, false));
    if (file == null || !"READY".equals(file.get("status"))) return;
    boolean okay =
        store.verify(
            file.get("storage_key").toString(),
            Long.parseLong(file.get("byte_size").toString()),
            (byte[]) file.get("sha256"));
    safe(
        () -> {
          jdbc.update(
              "UPDATE file_object SET integrity_status=?,verified_at=? WHERE id=? AND status='READY'",
              okay ? "OK" : "DAMAGED",
              now(),
              fileId);
          return null;
        });
    if (!okay) throw PrivateFileStore.unavailable();
  }

  private void validatePurpose(String appId, String type, String purpose, JsonNode body) {
    String row = nullable(body, "draftTargetKey");
    String field = nullable(body, "fieldKey");
    String receipt = nullable(body, "receiptNumber");
    if (receipt != null
        && (receipt.codePointCount(0, receipt.length()) > 80
            || receipt.codePoints().anyMatch(c -> c < 32))) throw invalid();
    if (purpose.equals("GENERAL")) {
      if (row != null || field != null || receipt != null) throw invalid();
      return;
    }
    if (purpose.equals("CUSTOM_FIELD"))
      throw new AuthFailure(503, "CUSTOM_ATTACHMENT_ADAPTER_UNAVAILABLE", 0);
    if (field != null || row == null || row.length() > 64) throw invalid();
    String array;
    if (purpose.equals("QUOTE_EVIDENCE") && type.equals("PURCHASE") && receipt == null)
      array = "quotes";
    else if (purpose.equals("EXPENSE_RECEIPT") && type.equals("REIMBURSEMENT")) array = "items";
    else throw invalid();
    List<String> drafts =
        jdbc.queryForList(
            "SELECT payload FROM application_draft WHERE application_id=?", String.class, appId);
    if (drafts.isEmpty()) throw conflict();
    JsonNode payload = parse(drafts.get(0));
    for (JsonNode item : payload.path(array)) {
      if (row.equals(item.path("clientKey").asText())) {
        if (purpose.equals("EXPENSE_RECEIPT") && item.path("noReceipt").asBoolean(false))
          throw invalid();
        return;
      }
    }
    throw invalid();
  }

  private Map<String, Object> replay(
      AuthPrincipal actor, String action, String request, byte[] fingerprint) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT payload_hash,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?",
            actor.userId(),
            action,
            request);
    if (rows.isEmpty()) return null;
    if (!java.security.MessageDigest.isEqual(fingerprint, (byte[]) rows.get(0).get("payload_hash")))
      throw conflict();
    return mapper.convertValue(
        parse(rows.get(0).get("result_summary").toString()),
        new TypeReference<Map<String, Object>>() {});
  }

  private void remember(
      AuthPrincipal actor,
      String action,
      String request,
      byte[] fingerprint,
      String target,
      Map<String, Object> result) {
    jdbc.update(
        "INSERT INTO request_record(actor_id,action,request_key,payload_hash,target_id,result_code,result_summary,created_at) VALUES (?,?,?,?,?,'SUCCESS',CAST(? AS JSON),?)",
        actor.userId(),
        action,
        request,
        fingerprint,
        target,
        json(result),
        now());
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw invalid();
    }
  }

  private JsonNode parse(String value) {
    try {
      return mapper.readTree(value);
    } catch (JsonProcessingException ex) {
      throw PrivateFileStore.unavailable();
    }
  }

  private void enqueue(String file) {
    jdbc.update(
        "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)",
        file,
        now(),
        now(),
        now());
  }

  private String insert(String sql, Object... args) {
    var holder = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
          return statement;
        },
        holder);
    Number generated = holder.getKey();
    if (generated == null) throw PrivateFileStore.unavailable();
    return id(generated.toString());
  }

  private void advance(String app) {
    jdbc.update(
        "UPDATE application SET row_version=row_version+1,updated_at=? WHERE id=?", now(), app);
  }

  private Map<String, Object> application(String id, boolean lock) {
    return first(
        jdbc.queryForList(
            "SELECT * FROM application WHERE id=?" + (lock ? " FOR UPDATE" : ""), id));
  }

  private Map<String, Object> object(String id, boolean lock) {
    return first(
        jdbc.queryForList(
            "SELECT * FROM file_object WHERE id=?" + (lock ? " FOR UPDATE" : ""), id));
  }

  private static Map<String, Object> first(List<Map<String, Object>> rows) {
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static void own(AuthPrincipal actor, Map<String, Object> app) {
    if (app == null || !actor.userId().equals(app.get("applicant_id").toString())) throw missing();
    if (!"DRAFT".equals(app.get("status"))) throw new AuthFailure(403, "DRAFT_WRITE_FORBIDDEN", 0);
  }

  private static void version(Map<String, Object> app, String expected) {
    if (!expected.equals(app.get("row_version").toString()) || new BigInteger(expected).equals(MAX))
      throw conflict();
  }

  static String id(String value) {
    if (value == null
        || !value.matches("[1-9][0-9]{0,19}")
        || new BigInteger(value).compareTo(MAX) > 0) throw invalid();
    return value;
  }

  private static String key(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value))
        throw invalid();
      return value.toLowerCase(java.util.Locale.ROOT);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
  }

  private static String text(JsonNode body, String field, boolean required) {
    JsonNode node = body.get(field);
    if (node == null && !required) return null;
    if (node == null || !node.isTextual() || node.textValue().isEmpty()) throw invalid();
    return node.textValue();
  }

  private static String nullable(JsonNode body, String field) {
    return text(body, field, false);
  }

  private static void whitelist(JsonNode body, Set<String> fields) {
    if (body == null || !body.isObject()) throw invalid();
    body.fieldNames()
        .forEachRemaining(
            field -> {
              if (!fields.contains(field)) throw invalid();
            });
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  static LocalDateTime time(Object value) {
    return value instanceof java.sql.Timestamp stamp
        ? stamp.toLocalDateTime()
        : (LocalDateTime) value;
  }

  private <T> T safe(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DuplicateKeyException ex) {
      throw conflict();
    } catch (DataAccessException | TransactionException ex) {
      throw new AuthFailure(503, "DATABASE_UNAVAILABLE", 0);
    }
  }

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_FILE_INPUT", 0);
  }

  private static AuthFailure missing() {
    return new AuthFailure(404, "FILE_NOT_FOUND", 0);
  }

  private static AuthFailure conflict() {
    return new AuthFailure(409, "FILE_BINDING_CONFLICT", 0);
  }
}
