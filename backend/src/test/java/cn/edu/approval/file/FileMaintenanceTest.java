package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class FileMaintenanceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final PrivateFileStore store = mock(PrivateFileStore.class);
  private final FileService service = mock(FileService.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private FileMaintenance maintenance;
  private final LocalDateTime now = LocalDateTime.of(2026, 11, 10, 0, 0);

  @BeforeEach
  void setup() {
    when(manager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    maintenance =
        new FileMaintenance(
            jdbc,
            store,
            service,
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC),
            manager);
    when(jdbc.queryForList(
            eq("SELECT * FROM file_object WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(object("READY")));
    when(jdbc.queryForList(
            eq("SELECT * FROM file_cleanup WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(task("PENDING")));
    when(jdbc.queryForList(
            eq(
                "SELECT id FROM file_cleanup WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE"),
            any(Object[].class)))
        .thenReturn(List.of(Map.of("id", "41")));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
  }

  @Test
  void referencedSharedFileIsNeverMarkedOrPhysicallyDeleted() throws Exception {
    when(jdbc.queryForObject(
            eq("SELECT COUNT(*) FROM file_binding WHERE file_id=?"),
            eq(Long.class),
            any(Object[].class)))
        .thenReturn(1L);
    maintenance.process(task("PENDING"));
    verify(store, never()).delete(anyString());
    verify(jdbc, never())
        .update(
            eq(
                "UPDATE file_object SET status='DELETING',upload_lease_until=NULL,updated_at=? WHERE id=?"),
            any(Object[].class));
  }

  @Test
  void deletingStateAndClaimCommitBeforeDiskThenDeletedCommitsAfter() throws Exception {
    maintenance.process(task("PENDING"));
    var order = org.mockito.Mockito.inOrder(jdbc, manager, store);
    order
        .verify(jdbc)
        .queryForList(eq("SELECT * FROM file_object WHERE id=? FOR UPDATE"), any(Object[].class));
    order
        .verify(jdbc)
        .queryForList(eq("SELECT * FROM file_cleanup WHERE id=? FOR UPDATE"), any(Object[].class));
    order
        .verify(jdbc)
        .update(
            eq(
                "UPDATE file_object SET status='DELETING',upload_lease_until=NULL,updated_at=? WHERE id=?"),
            any(Object[].class));
    order.verify(manager).commit(any());
    order.verify(store).delete("a".repeat(32));
    order
        .verify(jdbc)
        .update(
            eq(
                "UPDATE file_object SET status='DELETED',upload_lease_until=NULL,updated_at=? WHERE id=? AND status='DELETING'"),
            any(Object[].class));
    order.verify(manager).commit(any());
  }

  @Test
  void physicalDeleteFailurePersistsRetryAndSafeErrorWithoutResurrectingObject() throws Exception {
    doThrow(new IOException("C:\\secret\\volume")).when(store).delete(anyString());
    maintenance.process(task("PENDING"));
    verify(jdbc)
        .update(
            eq(
                "UPDATE file_cleanup SET status='PENDING',lease_until=NULL,lease_token=NULL,last_error='DELETE_FAILED',next_retry_at=?,updated_at=? WHERE id=? AND lease_token=?"),
            eq(now.plusMinutes(1)),
            eq(now),
            eq("41"),
            anyString());
    verify(jdbc, never())
        .update(
            eq(
                "UPDATE file_object SET status='DELETED',upload_lease_until=NULL,updated_at=? WHERE id=? AND status='DELETING'"),
            any(Object[].class));
  }

  @Test
  void expiredClaimResumesDeletingObjectAfterCrashIncludingAlreadyAbsentBytes() throws Exception {
    var object = object("DELETING");
    when(jdbc.queryForList(
            eq("SELECT * FROM file_object WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(object));
    var task = task("PROCESSING");
    task.put("lease_until", now.minusSeconds(1));
    task.put("lease_token", "old");
    when(jdbc.queryForList(
            eq("SELECT * FROM file_cleanup WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(task));
    maintenance.process(task);
    verify(store).delete("a".repeat(32));
    verify(jdbc)
        .update(
            eq(
                "UPDATE file_object SET status='DELETED',upload_lease_until=NULL,updated_at=? WHERE id=? AND status='DELETING'"),
            any(Object[].class));
  }

  @Test
  void unexpiredCleanupClaimCannotBeStolen() throws Exception {
    var task = task("PROCESSING");
    task.put("lease_until", now.plusMinutes(1));
    when(jdbc.queryForList(
            eq("SELECT * FROM file_cleanup WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(task));
    maintenance.process(task);
    verify(store, never()).delete(anyString());
  }

  @Test
  void uploadingWithLiveLeaseIsDeferredWithoutDeletingEitherPath() throws Exception {
    var object = object("UPLOADING");
    object.put("upload_lease_until", now.plusMinutes(15));
    when(jdbc.queryForList(
            eq("SELECT * FROM file_object WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(object));
    maintenance.process(task("PENDING"));
    verify(store, never()).delete(anyString());
    verify(jdbc)
        .update(
            eq(
                "UPDATE file_cleanup SET status='PENDING',lease_until=NULL,lease_token=NULL,next_retry_at=?,updated_at=? WHERE id=?"),
            eq(now.plusMinutes(15)),
            eq(now),
            eq("41"));
  }

  @Test
  void expiredUploadingCanNeverRaceBackToReady() throws Exception {
    var object = object("UPLOADING");
    object.put("upload_lease_until", now);
    when(jdbc.queryForList(
            eq("SELECT * FROM file_object WHERE id=? FOR UPDATE"), any(Object[].class)))
        .thenReturn(List.of(object));
    maintenance.process(task("PENDING"));
    verify(store).delete("a".repeat(32));
  }

  @Test
  void pausePreventsAnyNewClaimsAndRetryBackoffIsBounded() {
    maintenance.setPaused(true);
    maintenance.cleanup();
    maintenance.reconcile();
    org.mockito.Mockito.verifyNoInteractions(jdbc);
    assertThat(FileMaintenance.retry(1)).isEqualTo(1);
    assertThat(FileMaintenance.retry(2)).isEqualTo(5);
    assertThat(FileMaintenance.retry(100000)).isEqualTo(30);
  }

  private static Map<String, Object> task(String status) {
    var row = new HashMap<String, Object>();
    row.put("id", "41");
    row.put("file_id", "21");
    row.put("attempts", 0);
    row.put("status", status);
    return row;
  }

  private static Map<String, Object> object(String status) {
    var row = new HashMap<String, Object>();
    row.put("id", "21");
    row.put("storage_key", "a".repeat(32));
    row.put("status", status);
    return row;
  }
}
