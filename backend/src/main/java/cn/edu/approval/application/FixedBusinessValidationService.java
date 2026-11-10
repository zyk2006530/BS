package cn.edu.approval.application;

import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only advisory validation. Submit must independently revalidate in its own transaction. */
@Service
@Profile("db & !migrate")
public class FixedBusinessValidationService {
  private final JdbcTemplate jdbc;
  private final BusinessAccess access;
  private final ObjectMapper mapper;
  private final FixedBusinessValidator validator;
  private final TransactionTemplate read;

  public FixedBusinessValidationService(
      JdbcTemplate jdbc,
      BusinessAccess access,
      ObjectMapper mapper,
      FixedBusinessValidator validator,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.mapper = mapper;
    this.validator = validator;
    read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Map<String, Object> validate(AuthPrincipal actor, String target, JsonNode body) {
    String applicationId = ApplicationService.id(target);
    if (body == null
        || !body.isObject()
        || body.size() != 1
        || !body.has("expectedVersion")
        || !body.get("expectedVersion").isTextual())
      throw FixedBusinessFailure.field("expectedVersion", "Only expectedVersion is accepted");
    String expected = ApplicationService.id(body.get("expectedVersion").asText());
    try {
      return read.execute(
          status -> {
            access.requireActive(actor);
            var applications =
                jdbc.queryForList("SELECT * FROM application WHERE id=?", applicationId);
            if (applications.isEmpty()
                || !actor.userId().equals(applications.get(0).get("applicant_id").toString()))
              throw new AuthFailure(404, "APPLICATION_NOT_FOUND", 0);
            var app = applications.get(0);
            if (!"DRAFT".equals(app.get("status")))
              throw new AuthFailure(409, "APPLICATION_CONFLICT", 0);
            if (!expected.equals(app.get("row_version").toString()))
              throw new AuthFailure(409, "APPLICATION_CONFLICT", 0);
            String type = app.get("type").toString();
            if (!Set.of("PURCHASE", "TRAVEL", "REIMBURSEMENT").contains(type))
              throw new AuthFailure(503, "DRAFT_TYPE_UNAVAILABLE", 0);
            var drafts =
                jdbc.queryForList(
                    "SELECT payload,draft_schema_version FROM application_draft WHERE application_id=?",
                    applicationId);
            if (drafts.size() != 1
                || !"1".equals(drafts.get(0).get("draft_schema_version").toString()))
              throw new AuthFailure(503, "DRAFT_SCHEMA_UNAVAILABLE", 0);
            JsonNode payload;
            try {
              payload = mapper.readTree(drafts.get(0).get("payload").toString());
            } catch (JsonProcessingException ex) {
              throw new AuthFailure(503, "DRAFT_SCHEMA_UNAVAILABLE", 0);
            }
            List<FixedBusinessValidator.Binding> bindings =
                jdbc
                    .queryForList(
                        "SELECT b.*,f.owner_id,f.status,f.integrity_status,f.verified_at,f.original_name,f.media_type,f.byte_size,HEX(f.sha256) AS file_digest,HEX(b.receipt_sha256) AS receipt_digest FROM file_binding b JOIN file_object f ON f.id=b.file_id WHERE b.application_id=? ORDER BY b.id",
                        applicationId)
                    .stream()
                    .map(FixedBusinessValidationService::binding)
                    .toList();
            var result =
                validator.validate(
                    actor.userId(),
                    type,
                    string(app, "title"),
                    string(app, "remark"),
                    payload,
                    bindings);
            var current =
                jdbc.queryForList(
                    "SELECT row_version,status FROM application WHERE id=?", applicationId);
            if (current.isEmpty()
                || !expected.equals(current.get(0).get("row_version").toString())
                || !"DRAFT".equals(current.get(0).get("status")))
              throw new AuthFailure(409, "APPLICATION_CONFLICT", 0);
            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("id", applicationId);
            response.put("rowVersion", expected);
            response.put("amount", result.amount());
            response.put("naturalDays", result.naturalDays());
            response.put("formSnapshot", result.formSnapshot());
            return response;
          });
    } catch (DataAccessException | org.springframework.transaction.TransactionException ex) {
      throw new AuthFailure(503, "DATABASE_TEMPORARILY_UNAVAILABLE", 0);
    }
  }

  private static FixedBusinessValidator.Binding binding(Map<String, Object> row) {
    return new FixedBusinessValidator.Binding(
        string(row, "id"),
        string(row, "file_id"),
        string(row, "purpose"),
        string(row, "draft_target_key"),
        string(row, "receipt_number"),
        string(row, "original_name"),
        string(row, "media_type"),
        string(row, "byte_size"),
        string(row, "file_digest"),
        string(row, "status"),
        string(row, "owner_id"),
        string(row, "expense_item_id"),
        string(row, "quote_id"),
        string(row, "field_key"),
        string(row, "receipt_digest"),
        utc(row.get("created_at")),
        string(row, "integrity_status"),
        utc(row.get("verified_at")));
  }

  private static String string(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : row.get(key).toString();
  }

  private static String utc(Object value) {
    if (value instanceof Timestamp stamp)
      return stamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
    return value == null ? null : value.toString();
  }
}
