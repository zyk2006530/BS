-- Engineering design baseline, 2026-11-10. Append-only: do not edit V1-V3.
-- Run with business writes/uploads/cleanup stopped. MySQL DDL is not a single
-- rollbackable transaction; a failure requires inspection before Flyway repair.
-- Legacy TEMP/BOUND/FROZEN objects already have size/hash metadata. Their binding
-- and submitted-application relationships remain in file_binding/application;
-- READY describes object bytes, not whether a particular application is frozen.
-- This migration cannot certify disk bytes: reconcile the private volume before
-- enabling the new upload/preview/binding services. Never delete history here.
ALTER TABLE `file_object`
  DROP CHECK `ck_file_object_status`,
  MODIFY COLUMN `byte_size` BIGINT UNSIGNED NULL,
  MODIFY COLUMN `sha256` BINARY(32) NULL,
  MODIFY COLUMN `status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'UPLOADING',
  ADD COLUMN `upload_lease_until` DATETIME(6) NULL AFTER `status`;

UPDATE `file_object`
SET `status` = 'READY'
WHERE `status` IN ('TEMP', 'BOUND', 'FROZEN');

ALTER TABLE `file_object`
  ADD CONSTRAINT `ck_file_object_status`
    CHECK (`status` IN ('UPLOADING', 'READY', 'DELETING', 'DELETED')),
  ADD CONSTRAINT `ck_file_object_ready_metadata`
    CHECK (`status` <> 'READY' OR (`byte_size` IS NOT NULL AND `byte_size` > 0 AND `sha256` IS NOT NULL)),
  ADD CONSTRAINT `ck_file_object_upload_lease`
    CHECK ((`status` = 'UPLOADING' AND `upload_lease_until` IS NOT NULL)
        OR (`status` <> 'UPLOADING' AND `upload_lease_until` IS NULL)),
  ADD KEY `idx_file_object_upload_lease` (`status`, `upload_lease_until`, `id`),
  ADD KEY `idx_file_object_cleanup_age` (`status`, `updated_at`, `id`);

-- The database stores the deadline, not a volatile NOW() CHECK. TASK-06 must
-- create a 15-minute lease, cap a transfer at 2 minutes, and only finish an
-- unexpired UPLOADING row under its object lock. Clear the lease on transition.
-- CAS transitions, reference checks, private-volume rename/delete/reconciliation
-- and API permission checks are service responsibilities, not certified here.
