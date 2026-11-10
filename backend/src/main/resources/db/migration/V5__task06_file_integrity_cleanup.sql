-- TASK06: durable cleanup claims and fail-closed object integrity, no history deletion.
ALTER TABLE file_object
  ADD COLUMN integrity_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN verified_at DATETIME(6) NULL,
  ADD CONSTRAINT ck_file_integrity CHECK (integrity_status IN ('UNKNOWN','OK','DAMAGED'));
ALTER TABLE file_cleanup
  ADD COLUMN lease_until DATETIME(6) NULL,
  ADD COLUMN lease_token CHAR(36) NULL,
  ADD COLUMN last_error VARCHAR(80) NULL;
