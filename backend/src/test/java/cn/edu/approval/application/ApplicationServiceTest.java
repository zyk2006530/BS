package cn.edu.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ApplicationServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final BusinessAccess access = mock(BusinessAccess.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final AuthPrincipal actor = new AuthPrincipal("7", "9", "ACTIVE", false, "MINI_PROGRAM");
  private ApplicationService service;
  private static final String KEY = "8fc54884-16d8-4b28-bd61-a446a3dcfa62";

  @BeforeEach
  void setup() {
    when(manager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    when(access.requireActive(actor)).thenReturn(Map.of("id", "7", "department_id", "2"));
    service =
        new ApplicationService(
            jdbc,
            manager,
            access,
            mapper,
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void fingerprintsCanonicalizeNestedKeysButRetainMethodTargetAndBody() throws Exception {
    var first = mapper.readTree("{\"b\":{\"y\":1,\"x\":2},\"a\":[]}");
    var second = mapper.readTree("{\"a\":[],\"b\":{\"x\":2,\"y\":1}}");
    assertThat(service.fingerprint("PUT", "/1/draft", "SAVE_DRAFT", first))
        .isEqualTo(service.fingerprint("PUT", "/1/draft", "SAVE_DRAFT", second))
        .isNotEqualTo(service.fingerprint("PUT", "/2/draft", "SAVE_DRAFT", second))
        .isNotEqualTo(service.fingerprint("DELETE", "/1/draft", "SAVE_DRAFT", second));
  }

  @Test
  void unsignedIdentifiersAndUuidAreStrictAndLossless() {
    assertThat(ApplicationService.id("9007199254740993")).isEqualTo("9007199254740993");
    assertThat(ApplicationService.id("18446744073709551615")).isEqualTo("18446744073709551615");
    for (String bad : List.of("0", "01", "-1", "1.0", "18446744073709551616", "1e3")) {
      assertThatThrownBy(() -> ApplicationService.id(bad)).isInstanceOf(AuthFailure.class);
    }
    assertThat(ApplicationService.key(KEY.toUpperCase(java.util.Locale.ROOT))).isEqualTo(KEY);
    assertThatThrownBy(() -> ApplicationService.key("1-1-1-1-1")).isInstanceOf(AuthFailure.class);
  }

  @Test
  void unknownCreationFieldsAndCustomExecutionAreUnavailable() throws Exception {
    assertThatThrownBy(
            () ->
                service.create(
                    actor, KEY, mapper.readTree("{\"type\":\"TRAVEL\",\"applicantId\":\"8\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(400);
    assertThatThrownBy(
            () ->
                service.create(
                    actor,
                    KEY,
                    mapper.readTree("{\"type\":\"CUSTOM\",\"templateVersionId\":\"1\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(503);
    verify(jdbc)
        .queryForList(
            eq(
                "SELECT payload_hash,target_id,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?"),
            any(Object[].class));
  }

  @Test
  void optionalCreationIdentifiersAreValidatedBeforeUnavailableFeatures() {
    for (String json :
        List.of(
            "{\"type\":\"CUSTOM\",\"templateVersionId\":1}",
            "{\"type\":\"CUSTOM\",\"templateVersionId\":null}",
            "{\"type\":\"CUSTOM\",\"sourceApplicationId\":true}",
            "{\"type\":\"CUSTOM\",\"sourceApplicationId\":\"18446744073709551616\"}",
            "{\"type\":\"TRAVEL\",\"sourceApplicationId\":\"0\"}")) {
      assertThatThrownBy(() -> service.create(actor, KEY, mapper.readTree(json)))
          .isInstanceOf(AuthFailure.class)
          .extracting("status")
          .isEqualTo(400);
    }
    org.mockito.Mockito.verifyNoInteractions(jdbc, access);
  }

  @Test
  void anotherPersonsDraftIsNotDiscoverable() {
    when(jdbc.queryForList(eq("SELECT * FROM application WHERE id=?"), any(Object[].class)))
        .thenReturn(List.of(app("8", "DRAFT", "1")));
    when(access.canRead(eq(actor), any())).thenReturn(false);
    assertThatThrownBy(() -> service.get(actor, "9007199254740993"))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
  }

  @Test
  void saveLocksThenRechecksIdentityThenReplaysBeforeVersion() throws Exception {
    var body =
        mapper.readTree(
            "{\"expectedVersion\":\"1\",\"title\":\"t\",\"remark\":\"\",\"payload\":{}}");
    when(jdbc.queryForList(
            eq("SELECT * FROM application WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(app("7", "DRAFT", "99")));
    byte[] fp =
        service.fingerprint(
            "PUT", "/api/v1/applications/9007199254740993/draft", "SAVE_DRAFT", body);
    when(jdbc.queryForList(
            eq(
                "SELECT payload_hash,target_id,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?"),
            any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "payload_hash",
                    fp,
                    "target_id",
                    "9007199254740993",
                    "result_summary",
                    "{\"id\":\"9007199254740993\",\"rowVersion\":\"2\"}")));
    assertThat(service.save(actor, "9007199254740993", KEY, body).get("rowVersion")).isEqualTo("2");
    var order = inOrder(access, jdbc);
    order.verify(access).requireActive(actor);
    order
        .verify(jdbc)
        .queryForList(eq("SELECT * FROM application WHERE id=? FOR UPDATE"), any(Object[].class));
    order.verify(access).requireActive(actor);
    order
        .verify(jdbc)
        .queryForList(
            eq(
                "SELECT payload_hash,target_id,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?"),
            any(Object[].class));
  }

  @Test
  void removingExpenseRowUnbindsOnlyItsReceiptsAndQueuesSharedObjectCandidate() throws Exception {
    var before = app("7", "DRAFT", "1");
    before.put("type", "REIMBURSEMENT");
    var after = new HashMap<String, Object>(before);
    after.put("row_version", "2");
    after.put("dept_id", "2");
    when(jdbc.queryForList(
            eq("SELECT * FROM application WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(before));
    when(jdbc.queryForList(eq("SELECT * FROM application WHERE id=?"), any(Object[].class)))
        .thenReturn(List.of(after));
    when(jdbc.queryForList(
            eq("SELECT payload FROM application_draft WHERE application_id=?"),
            eq(String.class),
            any(Object[].class)))
        .thenReturn(
            List.of("{\"items\":[{\"clientKey\":\"expense_a\"},{\"clientKey\":\"expense_b\"}]}"))
        .thenReturn(List.of("{\"items\":[{\"clientKey\":\"expense_b\"}]}"));
    when(jdbc.queryForList(
            eq(
                "SELECT id,file_id,draft_target_key FROM file_binding WHERE application_id=? ORDER BY file_id,id"),
            any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of("id", "11", "file_id", "21", "draft_target_key", "expense_a"),
                Map.of("id", "12", "file_id", "22", "draft_target_key", "expense_b")));
    var body =
        mapper.readTree(
            "{\"expectedVersion\":\"1\",\"title\":\"t\",\"remark\":\"\",\"payload\":{\"items\":[{\"clientKey\":\"expense_b\"}]}}");
    assertThat(service.save(actor, "9007199254740993", KEY, body).get("rowVersion")).isEqualTo("2");
    verify(jdbc).queryForList(eq("SELECT id FROM file_object WHERE id=? FOR UPDATE"), eq("21"));
    verify(jdbc)
        .update(
            eq("DELETE FROM file_binding WHERE id=? AND application_id=?"),
            eq("11"),
            eq("9007199254740993"));
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
        .update(eq("DELETE FROM file_binding WHERE id=? AND application_id=?"), eq("12"), any());
    verify(jdbc)
        .update(
            eq(
                "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)"),
            eq("21"),
            any(),
            any(),
            any());
  }

  @Test
  void deleteResponseCanReplayAfterTargetDisappearsButDifferentFingerprintCannot()
      throws Exception {
    var body = mapper.readTree("{\"expectedVersion\":\"1\"}");
    when(jdbc.queryForList(
            eq("SELECT * FROM application WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of());
    byte[] fp =
        service.fingerprint(
            "DELETE", "/api/v1/applications/9007199254740993", "DELETE_DRAFT", body);
    when(jdbc.queryForList(
            eq(
                "SELECT payload_hash,target_id,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?"),
            any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "payload_hash", fp, "target_id", "9007199254740993", "result_summary", "{}")));
    service.delete(actor, "9007199254740993", KEY, body);
    assertThatThrownBy(
            () ->
                service.delete(
                    actor, "9007199254740993", KEY, mapper.readTree("{\"expectedVersion\":\"2\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(409);
  }

  @Test
  void listCountAlreadyContainsAuthorizationAndEscapesLiteralWildcards() {
    when(access.readScope(actor, "a"))
        .thenReturn(new BusinessAccess.SqlScope("a.applicant_id=?", List.of("7")));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    service.list(actor, Map.of("keyword", "x%_!", "view", "authorized"));
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForObject(sql.capture(), eq(Long.class), args.capture());
    assertThat(sql.getValue()).contains("WHERE (a.applicant_id=?)", "LIKE ? ESCAPE '!'");
    assertThat(args.getValue()).containsExactly("7", "%x!%!_!!%", "%x!%!_!!%");
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(manager).getTransaction(definition.capture());
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Test
  void unpairedSurrogatesInTitleAndRemarkAreRejectedBeforeDatabaseAccess() {
    for (String field : List.of("title", "remark")) {
      var body = mapper.createObjectNode();
      body.put("expectedVersion", "1");
      body.put("title", "title");
      body.put("remark", "remark");
      body.set("payload", mapper.createObjectNode());
      body.put(field, String.valueOf((char) 0xD800));
      assertThatThrownBy(() -> service.save(actor, "1", KEY, body))
          .isInstanceOf(AuthFailure.class)
          .extracting("status")
          .isEqualTo(400);
    }
    org.mockito.Mockito.verifyNoInteractions(jdbc, access);
  }

  @Test
  void submittedToUsesExclusiveUtcBoundaryAndRejectsLegacyName() {
    when(access.readScope(actor, "a"))
        .thenReturn(new BusinessAccess.SqlScope("a.applicant_id=?", List.of("7")));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    service.list(actor, Map.of("submittedTo", "2026-11-11T00:00:00Z"));
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
    assertThat(sql.getValue()).contains("a.submitted_at<?");
    assertThatThrownBy(() -> service.list(actor, Map.of("submittedUntil", "2026-11-11T00:00:00Z")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(400);
  }

  private static Map<String, Object> app(String owner, String status, String version) {
    var result = new HashMap<String, Object>();
    result.put("id", "9007199254740993");
    result.put("applicant_id", owner);
    result.put("status", status);
    result.put("row_version", version);
    result.put("type", "TRAVEL");
    return result;
  }
}
