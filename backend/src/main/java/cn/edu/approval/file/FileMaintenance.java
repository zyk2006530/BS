package cn.edu.approval.file;

import cn.edu.approval.identity.AuthFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent at-least-once cleanup. Claims commit before any physical deletion. */
@Component
@Profile("db & !migrate")
public class FileMaintenance {
  private static final Logger LOG = LoggerFactory.getLogger(FileMaintenance.class);
  private final JdbcTemplate jdbc;
  private final PrivateFileStore store;
  private final FileService service;
  private final Clock clock;
  private final TransactionTemplate tx;
  private volatile boolean paused;

  public FileMaintenance(
      JdbcTemplate jdbc,
      PrivateFileStore store,
      FileService service,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.store = store;
    this.service = service;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Maintenance orchestration must stop new HTTP writes and wait for in-flight calls as well. */
  public void setPaused(boolean paused) {
    this.paused = paused;
  }

  @Scheduled(fixedDelayString = "${app.files.cleanup-delay-ms:60000}")
  public void cleanup() {
    if (paused) return;
    try {
      // No queue uniqueness assumption: existing draft deletion can enqueue the same shared object.
      List<Map<String, Object>> stale =
          jdbc.queryForList(
              "SELECT f.id FROM file_object f WHERE ((f.status='UPLOADING' AND f.upload_lease_until<=?) OR (f.status='READY' AND f.updated_at<=? AND NOT EXISTS(SELECT 1 FROM file_binding b WHERE b.file_id=f.id)) OR f.status='DELETING') AND NOT EXISTS(SELECT 1 FROM file_cleanup c WHERE c.file_id=f.id AND c.status IN ('PENDING','PROCESSING')) ORDER BY f.id LIMIT 100",
              now(),
              now().minusHours(24));
      for (Map<String, Object> row : stale)
        jdbc.update(
            "INSERT INTO file_cleanup(file_id,status,attempts,next_retry_at,created_at,updated_at) VALUES (?,'PENDING',0,?,?,?)",
            row.get("id"),
            now(),
            now(),
            now());
      List<Map<String, Object>> work =
          jdbc.queryForList(
              "SELECT * FROM file_cleanup WHERE (status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=?)) OR (status='PROCESSING' AND lease_until<=?) ORDER BY id LIMIT 100",
              now(),
              now());
      for (Map<String, Object> candidate : work) {
        if (paused) return;
        process(candidate);
      }
      jdbc.update("DELETE FROM rate_limit_bucket WHERE expires_at<?", now());
    } catch (RuntimeException ex) {
      LOG.warn("File maintenance database operation unavailable");
    }
  }

  void process(Map<String, Object> candidate) {
    String token = UUID.randomUUID().toString();
    Claim claim = tx.execute(status -> claim(candidate, token));
    if (claim == null) return;
    try {
      store.delete(claim.key());
      tx.execute(
          status -> {
            if (claim.fileId() != null)
              jdbc.queryForList("SELECT id FROM file_object WHERE id=? FOR UPDATE", claim.fileId());
            if (owns(claim)) {
              if (claim.fileId() != null)
                jdbc.update(
                    "UPDATE file_object SET status='DELETED',upload_lease_until=NULL,updated_at=? WHERE id=? AND status='DELETING'",
                    now(),
                    claim.fileId());
              jdbc.update(
                  "UPDATE file_cleanup SET status='DONE',lease_until=NULL,lease_token=NULL,last_error=NULL,updated_at=? WHERE id=? AND lease_token=?",
                  now(),
                  claim.taskId(),
                  claim.token());
            }
            return null;
          });
    } catch (IOException | RuntimeException ex) {
      // Exact safe code only; OS messages may disclose the private root.
      jdbc.update(
          "UPDATE file_cleanup SET status='PENDING',lease_until=NULL,lease_token=NULL,last_error='DELETE_FAILED',next_retry_at=?,updated_at=? WHERE id=? AND lease_token=?",
          now().plusMinutes(retry(claim.attempt())),
          now(),
          claim.taskId(),
          claim.token());
      LOG.warn("File cleanup failed for task {}", claim.taskId());
    }
  }

  private Claim claim(Map<String, Object> candidate, String token) {
    String task = candidate.get("id").toString();
    String file = candidate.get("file_id") == null ? null : candidate.get("file_id").toString();
    Map<String, Object> object = null;
    if (file != null) {
      List<Map<String, Object>> rows =
          jdbc.queryForList("SELECT * FROM file_object WHERE id=? FOR UPDATE", file);
      if (!rows.isEmpty()) object = rows.get(0);
    }
    List<Map<String, Object>> tasks =
        jdbc.queryForList("SELECT * FROM file_cleanup WHERE id=? FOR UPDATE", task);
    if (tasks.isEmpty()) return null;
    Map<String, Object> row = tasks.get(0);
    if ("DONE".equals(row.get("status"))) return null;
    if ("PROCESSING".equals(row.get("status"))
        && row.get("lease_until") != null
        && FileService.time(row.get("lease_until")).isAfter(now())) return null;
    if ("PENDING".equals(row.get("status"))
        && row.get("next_retry_at") != null
        && FileService.time(row.get("next_retry_at")).isAfter(now())) return null;
    String key;
    if (object != null) {
      Long refs =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM file_binding WHERE file_id=?", Long.class, file);
      if (refs != null && refs > 0) {
        done(task);
        return null;
      }
      if ("UPLOADING".equals(object.get("status"))
          && FileService.time(object.get("upload_lease_until")).isAfter(now())) {
        jdbc.update(
            "UPDATE file_cleanup SET status='PENDING',lease_until=NULL,lease_token=NULL,next_retry_at=?,updated_at=? WHERE id=?",
            object.get("upload_lease_until"),
            now(),
            task);
        return null;
      }
      if ("DELETED".equals(object.get("status"))) {
        done(task);
        return null;
      }
      key = object.get("storage_key").toString();
      jdbc.update(
          "UPDATE file_object SET status='DELETING',upload_lease_until=NULL,updated_at=? WHERE id=?",
          now(),
          file);
    } else {
      if (row.get("storage_key") == null) {
        done(task);
        return null;
      }
      key = row.get("storage_key").toString();
      Long exists =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM file_object WHERE storage_key=?", Long.class, key);
      if (exists != null && exists > 0) {
        done(task);
        return null;
      }
      // Recheck age just before deletion; a recent path is never an orphan candidate.
      if (!old(key)) {
        done(task);
        return null;
      }
    }
    long attempt = Long.parseLong(row.get("attempts").toString()) + 1;
    jdbc.update(
        "UPDATE file_cleanup SET status='PROCESSING',attempts=LEAST(attempts+1,2147483647),lease_until=?,lease_token=?,updated_at=? WHERE id=?",
        now().plusMinutes(5),
        token,
        now(),
        task);
    return new Claim(task, file, key, token, attempt);
  }

  private boolean owns(Claim claim) {
    return !jdbc.queryForList(
            "SELECT id FROM file_cleanup WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE",
            claim.taskId(),
            claim.token())
        .isEmpty();
  }

  private void done(String task) {
    jdbc.update(
        "UPDATE file_cleanup SET status='DONE',lease_until=NULL,lease_token=NULL,updated_at=? WHERE id=?",
        now(),
        task);
  }

  static int retry(long attempt) {
    return attempt <= 1 ? 1 : attempt == 2 ? 5 : 30;
  }

  @Scheduled(fixedDelayString = "${app.files.reconcile-delay-ms:86400000}")
  public void reconcile() {
    if (paused) return;
    try {
      // Keyset batches bound heap while checking all historical READY objects, including bound
      // ones.
      String after = "0";
      while (!paused) {
        List<String> files =
            jdbc.queryForList(
                "SELECT CAST(id AS CHAR) FROM file_object WHERE status='READY' AND id>? ORDER BY id LIMIT 100",
                String.class,
                after);
        if (files.isEmpty()) break;
        for (String file : files) {
          try {
            service.checkIntegrity(file);
          } catch (AuthFailure ex) {
            LOG.warn("File integrity incident for object {}", file);
          }
          after = file;
        }
      }
      try (var paths = Files.list(store.root())) {
        var iterator = paths.iterator();
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (paused) return;
          String name = path.getFileName().toString();
          String key = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
          if (!key.matches("[a-f0-9]{32}") || !old(key)) continue;
          Long exists =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM file_object WHERE storage_key=?", Long.class, key);
          if (exists != null && exists == 0)
            jdbc.update(
                "INSERT INTO file_cleanup(storage_key,status,attempts,next_retry_at,created_at,updated_at) SELECT ?,'PENDING',0,?,?,? WHERE NOT EXISTS(SELECT 1 FROM file_cleanup WHERE storage_key=? AND status IN ('PENDING','PROCESSING'))",
                key,
                now(),
                now(),
                now(),
                key);
        }
      }
    } catch (IOException | RuntimeException ex) {
      LOG.warn("File reconciliation unavailable");
    }
  }

  private boolean old(String key) {
    try {
      boolean found = false;
      for (boolean temporary : List.of(false, true)) {
        Path path = store.path(key, temporary);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
          found = true;
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
              || !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                  .toInstant()
                  .isBefore(clock.instant().minusSeconds(86400))) return false;
        }
      }
      return found;
    } catch (IOException | RuntimeException ex) {
      return false;
    }
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private record Claim(String taskId, String fileId, String key, String token, long attempt) {}
}
