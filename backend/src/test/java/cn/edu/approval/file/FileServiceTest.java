package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.approval.application.ApplicationService;
import cn.edu.approval.common.BusinessAccess;
import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class FileServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final BusinessAccess access = mock(BusinessAccess.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final PrivateFileStore store = mock(PrivateFileStore.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
  private final AuthPrincipal actor = new AuthPrincipal("7", "9", "ACTIVE", false, "MINI_PROGRAM");
  private ApplicationService apps;
  private FileService service;
  private static final String KEY = "8fc54884-16d8-4b28-bd61-a446a3dcfa62";
  private static final String APP = "SELECT * FROM application WHERE id=?";
  private static final String FILE = "SELECT * FROM file_object WHERE id=?";
  private static final String REPLAY =
      "SELECT payload_hash,result_summary FROM request_record WHERE actor_id=? AND action=? AND request_key=?";

  @BeforeEach
  void setup() {
    when(manager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    apps = new ApplicationService(jdbc, manager, access, mapper, clock);
    service = new FileService(jdbc, access, apps, mapper, clock, store, manager, 20, 104857600);
    when(jdbc.queryForList(eq(APP), any(Object[].class)))
        .thenReturn(List.of(app("7", "DRAFT", "1")));
    when(jdbc.queryForList(eq(APP + " FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(app("7", "DRAFT", "1")));
    when(jdbc.queryForList(eq(FILE), any(Object[].class))).thenReturn(List.of(file("7", "READY")));
    when(jdbc.queryForList(eq(FILE + " FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(file("7", "READY")));
    when(store.verify(anyString(), eq(8L), any(byte[].class))).thenReturn(true);
    doAnswer(
            invocation -> {
              KeyHolder holder = invocation.getArgument(1);
              holder.getKeyList().add(Map.of("id", 42L));
              return 1;
            })
        .when(jdbc)
        .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
  }

  @Test
  void uploadBudgetCommitsBeforeRejectingThirtyFirstAttempt() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(31L);
    assertThatThrownBy(() -> service.beginUpload(actor))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(429);
    verify(manager).commit(any());
    verify(jdbc)
        .update(
            eq(
                "INSERT INTO rate_limit_bucket(bucket_key,window_start,counter,expires_at) VALUES (?,?,1,?) ON DUPLICATE KEY UPDATE counter=LEAST(counter+1,2147483647)"),
            any(Object[].class));
  }

  @Test
  void uploadCreatesFifteenMinuteLeaseAndPublishesBeforeReadyCommit() throws Exception {
    when(store.newKey()).thenReturn("a".repeat(32));
    when(store.write(anyString(), anyString(), any()))
        .thenReturn(new PrivateFileStore.Stored(8, new byte[32]));
    var row = file("7", "UPLOADING");
    row.put("upload_lease_until", LocalDateTime.of(2026, 11, 10, 0, 15));
    when(jdbc.queryForList(eq(FILE + " FOR UPDATE"), any(Object[].class))).thenReturn(List.of(row));
    assertThat(
            service
                .upload(actor, "x.png", "image/png", new ByteArrayInputStream(new byte[8]))
                .get("fileId"))
        .isEqualTo("42");
    var order = org.mockito.Mockito.inOrder(store, jdbc);
    order.verify(store).write(anyString(), anyString(), any());
    order.verify(store).publish(anyString());
    order.verify(jdbc).queryForList(eq(FILE + " FOR UPDATE"), any(Object[].class));
    order
        .verify(jdbc)
        .update(
            eq(
                "UPDATE file_object SET status='READY',byte_size=?,sha256=?,upload_lease_until=NULL,integrity_status='OK',verified_at=?,updated_at=? WHERE id=?"),
            any(Object[].class));
    var creator = ArgumentCaptor.forClass(PreparedStatementCreator.class);
    verify(jdbc).update(creator.capture(), any(KeyHolder.class));
    var connection = mock(java.sql.Connection.class);
    var statement = mock(java.sql.PreparedStatement.class);
    when(connection.prepareStatement(anyString(), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
        .thenReturn(statement);
    creator.getValue().createPreparedStatement(connection);
    verify(statement).setObject(5, LocalDateTime.of(2026, 11, 10, 0, 15));
  }

  @Test
  void expiredUploadCannotBecomeReadyAfterRenameAndIsQueued() throws Exception {
    when(store.newKey()).thenReturn("a".repeat(32));
    when(store.write(anyString(), anyString(), any()))
        .thenReturn(new PrivateFileStore.Stored(8, new byte[32]));
    var row = file("7", "UPLOADING");
    row.put("upload_lease_until", LocalDateTime.of(2026, 11, 10, 0, 0));
    when(jdbc.queryForList(eq(FILE + " FOR UPDATE"), any(Object[].class))).thenReturn(List.of(row));
    assertThatThrownBy(
            () ->
                service.upload(actor, "x.png", "image/png", new ByteArrayInputStream(new byte[8])))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(409);
    verify(jdbc)
        .update(
            eq(
                "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)"),
            any(Object[].class));
  }

  @Test
  void interruptedTransferQueuesRecoveryAndNeverPublishes() throws Exception {
    when(store.newKey()).thenReturn("a".repeat(32));
    when(store.write(anyString(), anyString(), any())).thenThrow(new IOException("interrupted"));
    assertThatThrownBy(
            () ->
                service.upload(actor, "x.png", "image/png", new ByteArrayInputStream(new byte[8])))
        .isInstanceOf(AuthFailure.class);
    verify(store, never()).publish(anyString());
    verify(jdbc)
        .update(
            eq(
                "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)"),
            any(Object[].class));
  }

  @Test
  void bindingReplaysBeforeStaleVersionAndDiskCheck() throws Exception {
    var body =
        mapper.readTree("{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}");
    when(jdbc.queryForList(eq(REPLAY), any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "payload_hash",
                    apps.fingerprint("POST", "/api/v1/applications/11/bindings", "BIND_FILE", body),
                    "result_summary",
                    "{\"bindingId\":\"42\",\"rowVersion\":\"2\"}")));
    assertThat(service.bind(actor, "11", KEY, body).get("rowVersion")).isEqualTo("2");
    verify(store, never()).verify(anyString(), any(Long.class), any());
  }

  @Test
  void bindingRechecksAccountAfterApplicationLock() throws Exception {
    service.bind(
        actor,
        "11",
        KEY,
        mapper.readTree("{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}"));
    var order = org.mockito.Mockito.inOrder(jdbc, access);
    order.verify(jdbc).queryForList(eq(APP + " FOR UPDATE"), any(Object[].class));
    order.verify(access).requireActive(actor);
    order.verify(jdbc).queryForList(eq(REPLAY), any(Object[].class));
    order.verify(jdbc).queryForList(eq(FILE + " FOR UPDATE"), any(Object[].class));
  }

  @Test
  void otherOwnersFileIsHiddenWithoutHashing() throws Exception {
    when(jdbc.queryForList(eq(FILE), any(Object[].class))).thenReturn(List.of(file("8", "READY")));
    assertThatThrownBy(
            () ->
                service.bind(
                    actor,
                    "11",
                    KEY,
                    mapper.readTree(
                        "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
    org.mockito.Mockito.verifyNoInteractions(store);
  }

  @Test
  void duplicateBytesAcrossAnyPurposeAreRejected() throws Exception {
    when(jdbc.queryForList(
            eq(
                "SELECT f.id,f.byte_size,f.sha256 FROM file_binding b JOIN file_object f ON f.id=b.file_id WHERE b.application_id=?"),
            any(Object[].class)))
        .thenReturn(List.of(Map.of("id", "22", "byte_size", 8, "sha256", new byte[32])));
    assertThatThrownBy(
            () ->
                service.bind(
                    actor,
                    "11",
                    KEY,
                    mapper.readTree(
                        "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("code")
        .isEqualTo("DUPLICATE_FILE");
  }

  @Test
  void totalApplicationCountIsNotPerExpenseRow() throws Exception {
    service = new FileService(jdbc, access, apps, mapper, clock, store, manager, 1, 104857600);
    when(jdbc.queryForList(
            eq(
                "SELECT f.id,f.byte_size,f.sha256 FROM file_binding b JOIN file_object f ON f.id=b.file_id WHERE b.application_id=?"),
            any(Object[].class)))
        .thenReturn(List.of(Map.of("id", "22", "byte_size", 8, "sha256", new byte[] {1})));
    assertThatThrownBy(
            () ->
                service.bind(
                    actor,
                    "11",
                    KEY,
                    mapper.readTree(
                        "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(413);
  }

  @Test
  void totalBytesAreIndependentOfCountLimitAndIncludeEveryPurpose() throws Exception {
    service = new FileService(jdbc, access, apps, mapper, clock, store, manager, 20, 8);
    when(jdbc.queryForList(
            eq(
                "SELECT f.id,f.byte_size,f.sha256 FROM file_binding b JOIN file_object f ON f.id=b.file_id WHERE b.application_id=?"),
            any(Object[].class)))
        .thenReturn(List.of(Map.of("id", "22", "byte_size", 8, "sha256", new byte[] {1})));
    assertThatThrownBy(
            () ->
                service.bind(
                    actor,
                    "11",
                    KEY,
                    mapper.readTree(
                        "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(413);
    verify(jdbc, never())
        .update(
            eq("UPDATE application SET row_version=row_version+1,updated_at=? WHERE id=?"),
            any(Object[].class));
  }

  @Test
  void identicalKeyWithChangedBodyConflictsBeforeDiskAndVersionCheck() throws Exception {
    var original =
        mapper.readTree("{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\"}");
    when(jdbc.queryForList(eq(REPLAY), any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "payload_hash",
                    apps.fingerprint(
                        "POST", "/api/v1/applications/11/bindings", "BIND_FILE", original),
                    "result_summary",
                    "{\"bindingId\":\"42\",\"rowVersion\":\"2\"}")));
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
    changed.put("fileId", "22");
    assertThatThrownBy(() -> service.bind(actor, "11", KEY, changed))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(409);
    org.mockito.Mockito.verifyNoInteractions(store);
  }

  @Test
  void quotationEvidenceRejectsOtherApplicationType() throws Exception {
    assertThatThrownBy(
            () ->
                service.bind(
                    actor,
                    "11",
                    KEY,
                    mapper.readTree(
                        "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"QUOTE_EVIDENCE\",\"draftTargetKey\":\"quote_a\"}")))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(400);
  }

  @Test
  void genericBindingCannotSmuggleReceiptOrTarget() throws Exception {
    for (String extra :
        List.of(",\"receiptNumber\":\"123\"", ",\"draftTargetKey\":\"x\"", ",\"fieldKey\":\"x\""))
      assertThatThrownBy(
              () ->
                  service.bind(
                      actor,
                      "11",
                      KEY,
                      mapper.readTree(
                          "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"GENERAL\""
                              + extra
                              + "}")))
          .isInstanceOf(AuthFailure.class)
          .extracting("status")
          .isEqualTo(400);
  }

  @Test
  void receiptRequiresExistingCorrectTypeRowAndNotNoReceipt() throws Exception {
    var app = app("7", "DRAFT", "1");
    app.put("type", "REIMBURSEMENT");
    when(jdbc.queryForList(eq(APP), any(Object[].class))).thenReturn(List.of(app));
    when(jdbc.queryForList(eq(APP + " FOR UPDATE"), any(Object[].class))).thenReturn(List.of(app));
    when(jdbc.queryForList(
            eq("SELECT payload FROM application_draft WHERE application_id=?"),
            eq(String.class),
            any(Object[].class)))
        .thenReturn(List.of("{\"items\":[{\"clientKey\":\"expense_a\",\"noReceipt\":true}]}"));
    for (String row : List.of("expense_a", "missing"))
      assertThatThrownBy(
              () ->
                  service.bind(
                      actor,
                      "11",
                      KEY,
                      mapper.readTree(
                          "{\"expectedVersion\":\"1\",\"fileId\":\"21\",\"purpose\":\"EXPENSE_RECEIPT\",\"draftTargetKey\":\""
                              + row
                              + "\"}")))
          .isInstanceOf(AuthFailure.class)
          .extracting("status")
          .isEqualTo(400);
  }

  @Test
  void boundFilesRequireContextAndCurrentReadPermissionEvenForOwner() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThatThrownBy(() -> service.download(actor, "21", null))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
    when(access.canRead(eq(actor), any())).thenReturn(false);
    assertThatThrownBy(() -> service.download(actor, "21", "11"))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
    when(access.canRead(eq(actor), any())).thenReturn(true);
    assertThat(service.download(actor, "21", "11").get("id")).isEqualTo("21");
  }

  @Test
  void unboundFileIsOwnerOnlyAndUploadingIsNeverDownloadable() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.queryForList(eq(FILE), any(Object[].class))).thenReturn(List.of(file("8", "READY")));
    assertThatThrownBy(() -> service.download(actor, "21", null))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
    when(jdbc.queryForList(eq(FILE), any(Object[].class)))
        .thenReturn(List.of(file("7", "UPLOADING")));
    assertThatThrownBy(() -> service.download(actor, "21", null))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(404);
  }

  @Test
  void integrityFailurePersistsDamageWithoutDeletingHistoricalBinding() {
    when(store.verify(anyString(), eq(8L), any(byte[].class))).thenReturn(false);
    assertThatThrownBy(() -> service.checkIntegrity("21"))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(503);
    verify(jdbc)
        .update(
            eq(
                "UPDATE file_object SET integrity_status=?,verified_at=? WHERE id=? AND status='READY'"),
            eq("DAMAGED"),
            any(),
            eq("21"));
    verify(jdbc, never())
        .update(
            eq("DELETE FROM file_binding WHERE id=? AND application_id=?"), any(Object[].class));
  }

  @Test
  void unbindReplayDoesNotNeedRemovedBindingOrOldVersion() throws Exception {
    var body = mapper.readTree("{\"expectedVersion\":\"1\"}");
    when(jdbc.queryForList(eq(REPLAY), any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "payload_hash",
                    apps.fingerprint(
                        "DELETE", "/api/v1/applications/11/bindings/42", "UNBIND_FILE", body),
                    "result_summary",
                    "{\"rowVersion\":\"2\"}")));
    assertThat(service.unbind(actor, "11", "42", KEY, body).get("rowVersion")).isEqualTo("2");
  }

  @Test
  void malformedUnsignedIdsAndUnknownFieldsFailEarly() {
    for (String value : List.of("0", "01", "1e3", "18446744073709551616"))
      assertThatThrownBy(() -> FileService.id(value)).isInstanceOf(AuthFailure.class);
    assertThat(FileService.id("9007199254740993")).isEqualTo("9007199254740993");
  }

  private static Map<String, Object> app(String owner, String status, String version) {
    var result = new HashMap<String, Object>();
    result.put("id", "11");
    result.put("applicant_id", owner);
    result.put("status", status);
    result.put("row_version", version);
    result.put("type", "TRAVEL");
    return result;
  }

  private static Map<String, Object> file(String owner, String status) {
    var result = new HashMap<String, Object>();
    result.put("id", "21");
    result.put("owner_id", owner);
    result.put("status", status);
    result.put("storage_key", "a".repeat(32));
    result.put("byte_size", 8);
    result.put("sha256", new byte[32]);
    result.put("integrity_status", "OK");
    return result;
  }
}
