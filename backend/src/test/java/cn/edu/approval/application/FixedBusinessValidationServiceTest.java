package cn.edu.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class FixedBusinessValidationServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final BusinessAccess access = mock(BusinessAccess.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final TransactionStatus txStatus = mock(TransactionStatus.class);
  private final AuthPrincipal actor = new AuthPrincipal("1", "2", "ACTIVE", false, "NATIVE");
  private FixedBusinessValidationService service;
  private static final String PAYLOAD =
      """
      {"origin":"a","destination":"b","startAt":"2026-11-10T00:00:00Z","endAt":"2026-11-10T00:00:00Z","purpose":"trip","transport":"TRAIN","budget":"1.00"}
      """;

  @BeforeEach
  void setUp() {
    when(manager.getTransaction(any())).thenReturn(txStatus);
    doNothing().when(manager).commit(txStatus);
    when(access.requireActive(actor)).thenReturn(Map.of("id", "1"));
    when(jdbc.queryForList("SELECT * FROM application WHERE id=?", "3"))
        .thenReturn(List.of(application("1", "DRAFT", "7")));
    when(jdbc.queryForList(
            "SELECT payload,draft_schema_version FROM application_draft WHERE application_id=?",
            "3"))
        .thenReturn(List.of(Map.of("payload", PAYLOAD, "draft_schema_version", 1)));
    when(jdbc.queryForList("SELECT row_version,status FROM application WHERE id=?", "3"))
        .thenReturn(List.of(Map.of("row_version", 7, "status", "DRAFT")));
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT b.*"), eq("3")))
        .thenReturn(List.of());
    service =
        new FixedBusinessValidationService(
            jdbc,
            access,
            mapper,
            new FixedBusinessValidator((id, owner) -> Optional.empty()),
            manager);
  }

  private Map<String, Object> application(String owner, String status, String version) {
    return Map.of(
        "id",
        "3",
        "applicant_id",
        owner,
        "status",
        status,
        "row_version",
        version,
        "type",
        "TRAVEL",
        "title",
        "trip",
        "remark",
        "");
  }

  @Test
  void readsStoredPayloadOnlyWithoutVersionMutationOrWriteAndUsesReadOnlyTransaction()
      throws Exception {
    var result = service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}"));
    assertThat(result)
        .containsEntry("id", "3")
        .containsEntry("rowVersion", "7")
        .containsEntry("amount", "1.00")
        .containsEntry("naturalDays", 1L);
    verify(manager)
        .getTransaction(
            org.mockito.ArgumentMatchers.argThat(
                def ->
                    def.isReadOnly()
                        && def.getIsolationLevel()
                            == TransactionDefinition.ISOLATION_READ_COMMITTED));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void persistedAttachmentHealthAndUtcVerificationTimeAreMapped() throws Exception {
    var row = new java.util.HashMap<String, Object>();
    row.put("id", "9");
    row.put("file_id", "10");
    row.put("purpose", "GENERAL");
    row.put("owner_id", "1");
    row.put("status", "READY");
    row.put("integrity_status", "OK");
    row.put("verified_at", java.time.LocalDateTime.parse("2026-11-10T00:00:00"));
    row.put("created_at", java.time.LocalDateTime.parse("2026-11-10T00:00:00"));
    row.put("original_name", "a.png");
    row.put("media_type", "image/png");
    row.put("byte_size", 10L);
    row.put("file_digest", "a".repeat(64));
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT b.*"), eq("3")))
        .thenReturn(List.of(row));
    var result = service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}"));
    var snapshot = (com.fasterxml.jackson.databind.JsonNode) result.get("formSnapshot");
    assertThat(snapshot.path("bindings").get(0).path("verifiedAt").asText())
        .isEqualTo("2026-11-10T00:00:00Z");
    row.put("integrity_status", "DAMAGED");
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class,
            failure ->
                assertThat(failure.fieldErrors())
                    .extracting(FixedBusinessFailure.FieldError::path)
                    .contains("bindings[0].fileId"));
  }

  @Test
  void unknownRequestBodyFieldRejectedWithoutDatabaseRead() throws Exception {
    assertThatThrownBy(
            () ->
                service.validate(
                    actor, "3", mapper.readTree("{\"expectedVersion\":\"7\",\"payload\":{}}")))
        .isInstanceOf(FixedBusinessFailure.class);
    verify(access, never()).requireActive(any());
  }

  @Test
  void foreignDraftAlwaysNotFound() throws Exception {
    when(jdbc.queryForList("SELECT * FROM application WHERE id=?", "3"))
        .thenReturn(List.of(application("9", "DRAFT", "7")));
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(AuthFailure.class, f -> assertThat(f.status()).isEqualTo(404));
  }

  @Test
  void staleVersionAndNonDraftConflict() throws Exception {
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"6\"}")))
        .isInstanceOfSatisfying(AuthFailure.class, f -> assertThat(f.status()).isEqualTo(409));
    when(jdbc.queryForList("SELECT * FROM application WHERE id=?", "3"))
        .thenReturn(List.of(application("1", "APPROVED", "7")));
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(AuthFailure.class, f -> assertThat(f.status()).isEqualTo(409));
  }

  @Test
  void concurrentSaveAfterReadingPayloadReturnsConflict() throws Exception {
    when(jdbc.queryForList("SELECT row_version,status FROM application WHERE id=?", "3"))
        .thenReturn(List.of(Map.of("row_version", 8, "status", "DRAFT")));
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(AuthFailure.class, f -> assertThat(f.status()).isEqualTo(409));
  }

  @Test
  void unsupportedStoredSchemaFailsClosed() throws Exception {
    when(jdbc.queryForList(
            "SELECT payload,draft_schema_version FROM application_draft WHERE application_id=?",
            "3"))
        .thenReturn(List.of(Map.of("payload", PAYLOAD, "draft_schema_version", 2)));
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(AuthFailure.class, f -> assertThat(f.status()).isEqualTo(503));
  }

  @Test
  void databaseFailureNeverLeaksSql() throws Exception {
    when(jdbc.queryForList("SELECT * FROM application WHERE id=?", "3"))
        .thenThrow(
            new org.springframework.jdbc.BadSqlGrammarException(
                "query", "secret_table", new java.sql.SQLException("password")));
    assertThatThrownBy(
            () -> service.validate(actor, "3", mapper.readTree("{\"expectedVersion\":\"7\"}")))
        .isInstanceOfSatisfying(
            AuthFailure.class,
            f -> {
              assertThat(f.status()).isEqualTo(503);
              assertThat(f.getMessage()).doesNotContain("secret_table", "password");
            });
  }
}
