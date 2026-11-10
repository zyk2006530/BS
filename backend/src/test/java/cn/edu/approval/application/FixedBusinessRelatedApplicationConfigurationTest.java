package cn.edu.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

class FixedBusinessRelatedApplicationConfigurationTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final FixedBusinessRelatedApplicationSource source =
      new FixedBusinessRelatedApplicationConfiguration()
          .fixedBusinessRelatedApplicationSource(jdbc, mapper);
  private static final String APP_SQL =
      "SELECT id,applicant_id,status,type,application_no,title FROM application WHERE id=? AND applicant_id=? AND status='APPROVED' AND type IN ('PURCHASE','TRAVEL')";
  private static final String SNAPSHOT_SQL =
      "SELECT form_snapshot FROM flow_instance WHERE application_id=?";

  @Test
  void unavailableUnapprovedOrForeignApplicationDoesNotReadBody() {
    when(jdbc.queryForList(APP_SQL, "3", "1")).thenReturn(List.of());
    assertThat(source.find("3", "1")).isEmpty();
    verify(jdbc, never()).queryForList(anyString(), eq(String.class), eq("3"));
  }

  @Test
  void missingStudentSchemaFailsClosedWithSafeFieldError() {
    approved();
    when(jdbc.queryForList(SNAPSHOT_SQL, String.class, "3"))
        .thenThrow(
            new BadSqlGrammarException("sensitive", "secret_table", new SQLException("password")));
    assertThatThrownBy(() -> source.find("3", "1"))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(503);
              assertThat(failure.code()).isEqualTo("RELATED_APPLICATION_SNAPSHOT_UNAVAILABLE");
              assertThat(failure.fieldErrors())
                  .extracting(FixedBusinessFailure.FieldError::path)
                  .containsExactly("payload.relatedApplicationId");
              assertThat(failure.getMessage())
                  .doesNotContain("secret_table", "password", "flow_instance");
            });
  }

  @Test
  void exactlyOneStoredImmutableSnapshotIsRequired() {
    approved();
    when(jdbc.queryForList(SNAPSHOT_SQL, String.class, "3")).thenReturn(List.of());
    assertThatThrownBy(() -> source.find("3", "1")).isInstanceOf(FixedBusinessFailure.class);
    when(jdbc.queryForList(SNAPSHOT_SQL, String.class, "3")).thenReturn(List.of("{}", "{}"));
    assertThatThrownBy(() -> source.find("3", "1")).isInstanceOf(FixedBusinessFailure.class);
  }

  @Test
  void readsSnapshotWithoutAnyDraftOrWriteAccess() {
    approved();
    when(jdbc.queryForList(SNAPSHOT_SQL, String.class, "3"))
        .thenReturn(
            List.of(
                "{\"schemaVersion\":1,\"type\":\"PURCHASE\",\"payload\":{\"project\":\"p\"},\"amount\":\"1.00\"}"));
    var result = source.find("3", "1").orElseThrow();
    assertThat(result.applicationNo()).isEqualTo("CG-1");
    assertThat(result.formSnapshot().path("amount").asText()).isEqualTo("1.00");
    verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
  }

  @Test
  void malformedStoredJsonIsUnavailableNotClientInputFailure() {
    approved();
    when(jdbc.queryForList(SNAPSHOT_SQL, String.class, "3")).thenReturn(List.of("not-json"));
    assertThatThrownBy(() -> source.find("3", "1"))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class, f -> assertThat(f.status()).isEqualTo(503));
  }

  private void approved() {
    when(jdbc.queryForList(APP_SQL, "3", "1"))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    "3",
                    "applicant_id",
                    "1",
                    "status",
                    "APPROVED",
                    "type",
                    "PURCHASE",
                    "application_no",
                    "CG-1",
                    "title",
                    "purchase")));
  }
}
