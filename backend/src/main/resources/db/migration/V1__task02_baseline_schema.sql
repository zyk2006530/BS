-- TASK-02 baseline schema: organization, identity, configuration, drafts,
-- files, inbox, audit and transaction-support tables.
--
-- Flow/template/instance-semantics tables (flow_template, template_department,
-- template_version, policy_version, flow_instance, instance_node,
-- approval_record, reminder_record) are intentionally absent. TASK-09 and
-- TASK-10 own those definitions and their execution semantics.
--
-- TASK-02 also does not create purchase, travel, reimbursement or custom_form
-- extension tables. Those belong to their business tasks. template_version_id
-- and active_policy_id are reserved columns without a foreign key until the
-- owning migration adds the referenced table.

CREATE TABLE `department` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(80) NOT NULL,
  `enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  `manager_user_id` BIGINT UNSIGNED NULL,
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_department_name` (`name`),
  KEY `idx_department_manager` (`manager_user_id`),
  CONSTRAINT `ck_department_enabled` CHECK (`enabled` IN (0, 1)),
  CONSTRAINT `ck_department_name` CHECK (CHAR_LENGTH(`name`) BETWEEN 1 AND 80),
  CONSTRAINT `ck_department_version` CHECK (`row_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `email` VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `name` VARCHAR(80) NOT NULL,
  `requested_department_id` BIGINT UNSIGNED NOT NULL,
  `department_id` BIGINT UNSIGNED NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `rejection_reason` VARCHAR(500) NULL,
  `must_change_password` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_username` (`username`),
  UNIQUE KEY `uq_user_email` (`email`),
  KEY `idx_user_department_status` (`department_id`, `status`, `id`),
  KEY `idx_user_requested_department` (`requested_department_id`),
  CONSTRAINT `fk_user_requested_department` FOREIGN KEY (`requested_department_id`)
    REFERENCES `department` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_department` FOREIGN KEY (`department_id`)
    REFERENCES `department` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_user_username_length` CHECK (CHAR_LENGTH(`username`) BETWEEN 4 AND 64),
  CONSTRAINT `ck_user_username_lowercase` CHECK (`username` = LOWER(`username`)),
  CONSTRAINT `ck_user_name` CHECK (CHAR_LENGTH(`name`) BETWEEN 1 AND 80),
  CONSTRAINT `ck_user_email_length` CHECK (CHAR_LENGTH(`email`) BETWEEN 3 AND 254),
  CONSTRAINT `ck_user_email_lowercase` CHECK (`email` = LOWER(`email`)),
  CONSTRAINT `ck_user_status` CHECK (`status` IN ('PENDING', 'ACTIVE', 'REJECTED', 'DISABLED')),
  CONSTRAINT `ck_user_active_department` CHECK (`status` <> 'ACTIVE' OR `department_id` IS NOT NULL),
  CONSTRAINT `ck_user_rejection` CHECK (
    (`status` = 'REJECTED' AND `rejection_reason` IS NOT NULL AND CHAR_LENGTH(`rejection_reason`) > 0)
    OR `status` <> 'REJECTED'
  ),
  CONSTRAINT `ck_user_must_change` CHECK (`must_change_password` IN (0, 1)),
  CONSTRAINT `ck_user_version` CHECK (`row_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `department`
  ADD CONSTRAINT `fk_department_manager` FOREIGN KEY (`manager_user_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE `role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `name` VARCHAR(80) NOT NULL,
  `description` VARCHAR(255) NOT NULL DEFAULT '',
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_role` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`user_id`, `role_id`),
  KEY `idx_user_role_role` (`role_id`),
  CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`)
    REFERENCES `role` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `data_grant` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `ability` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `scope` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `department_id` BIGINT UNSIGNED NULL,
  `valid_from` DATETIME(6) NOT NULL,
  `valid_until` DATETIME(6) NOT NULL,
  `revoked_at` DATETIME(6) NULL,
  `granter_id` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(2000) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_data_grant_user` (`user_id`, `ability`, `scope`, `valid_until`),
  KEY `idx_data_grant_department` (`department_id`, `ability`, `valid_from`, `valid_until`),
  CONSTRAINT `fk_data_grant_user` FOREIGN KEY (`user_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_data_grant_department` FOREIGN KEY (`department_id`)
    REFERENCES `department` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_data_grant_granter` FOREIGN KEY (`granter_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_data_grant_ability` CHECK (`ability` IN ('READ_BODY', 'STATISTICS')),
  CONSTRAINT `ck_data_grant_scope` CHECK (`scope` IN ('DEPARTMENT', 'ENTERPRISE')),
  CONSTRAINT `ck_data_grant_department` CHECK (
    (`scope` = 'DEPARTMENT' AND `department_id` IS NOT NULL)
    OR (`scope` = 'ENTERPRISE' AND `department_id` IS NULL)
  ),
  CONSTRAINT `ck_data_grant_reason` CHECK (CHAR_LENGTH(`reason`) > 0),
  CONSTRAINT `ck_data_grant_window` CHECK (`valid_until` > `valid_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `auth_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `token_hash` BINARY(32) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `last_seen_at` DATETIME(6) NOT NULL,
  `absolute_expires_at` DATETIME(6) NOT NULL,
  `revoked_at` DATETIME(6) NULL,
  `client_type` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_session_token_hash` (`token_hash`),
  KEY `idx_auth_session_user` (`user_id`, `revoked_at`, `absolute_expires_at`),
  KEY `idx_auth_session_expiry` (`absolute_expires_at`),
  CONSTRAINT `fk_auth_session_user` FOREIGN KEY (`user_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_auth_session_client` CHECK (`client_type` IN ('WEB', 'MINI_PROGRAM')),
  CONSTRAINT `ck_auth_session_window` CHECK (`absolute_expires_at` > `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `system_config` (
  `id` TINYINT UNSIGNED NOT NULL,
  `active_policy_id` BIGINT UNSIGNED NULL,
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_system_config_single_row` CHECK (`id` = 1),
  CONSTRAINT `ck_system_config_version` CHECK (`row_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `application` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_no` VARCHAR(40) NULL,
  `type` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `title` VARCHAR(200) NOT NULL DEFAULT '',
  `remark` VARCHAR(2000) NOT NULL DEFAULT '',
  `applicant_id` BIGINT UNSIGNED NOT NULL,
  `dept_id` BIGINT UNSIGNED NOT NULL,
  `applicant_name_snapshot` VARCHAR(80) NOT NULL DEFAULT '',
  `applicant_department_name_snapshot` VARCHAR(80) NOT NULL DEFAULT '',
  `status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `amount` DECIMAL(14,2) NULL,
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `template_version_id` BIGINT UNSIGNED NULL,
  `source_application_id` BIGINT UNSIGNED NULL,
  `submitted_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_application_no` (`application_no`),
  KEY `idx_application_applicant` (`applicant_id`, `submitted_at`, `id`),
  KEY `idx_application_department` (`dept_id`, `status`, `submitted_at`, `id`),
  KEY `idx_application_source` (`source_application_id`),
  CONSTRAINT `fk_application_applicant` FOREIGN KEY (`applicant_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_application_department` FOREIGN KEY (`dept_id`)
    REFERENCES `department` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_application_source` FOREIGN KEY (`source_application_id`)
    REFERENCES `application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_application_type` CHECK (`type` IN ('PURCHASE', 'TRAVEL', 'REIMBURSEMENT', 'CUSTOM')),
  CONSTRAINT `ck_application_status` CHECK (`status` IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
  CONSTRAINT `ck_application_version` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_application_draft_fields` CHECK (
    `status` <> 'DRAFT'
    OR (`application_no` IS NULL AND `amount` IS NULL AND `submitted_at` IS NULL)
  ),
  CONSTRAINT `ck_application_submitted_fields` CHECK (
    `status` = 'DRAFT'
    OR (
      `application_no` IS NOT NULL
      AND `submitted_at` IS NOT NULL
      AND CHAR_LENGTH(`title`) > 0
      AND CHAR_LENGTH(`applicant_name_snapshot`) > 0
      AND CHAR_LENGTH(`applicant_department_name_snapshot`) > 0
    )
  ),
  CONSTRAINT `ck_application_amount` CHECK (`status` = 'DRAFT' OR `type` = 'CUSTOM' OR `amount` IS NOT NULL),
  CONSTRAINT `ck_application_template` CHECK (
    (`type` = 'CUSTOM' AND `template_version_id` IS NOT NULL)
    OR (`type` <> 'CUSTOM' AND `template_version_id` IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `application_draft` (
  `application_id` BIGINT UNSIGNED NOT NULL,
  `payload` JSON NOT NULL,
  `draft_schema_version` INT UNSIGNED NOT NULL DEFAULT 1,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`application_id`),
  CONSTRAINT `ck_application_draft_schema_version` CHECK (`draft_schema_version` >= 1),
  CONSTRAINT `fk_application_draft_application` FOREIGN KEY (`application_id`)
    REFERENCES `application` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `file_object` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_id` BIGINT UNSIGNED NOT NULL,
  `storage_key` VARCHAR(255) NOT NULL,
  `original_name` VARCHAR(255) NOT NULL,
  `media_type` VARCHAR(100) NOT NULL,
  `byte_size` BIGINT UNSIGNED NOT NULL,
  `sha256` BINARY(32) NOT NULL,
  `status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'TEMP',
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_file_object_storage_key` (`storage_key`),
  KEY `idx_file_object_owner_status` (`owner_id`, `status`, `id`),
  CONSTRAINT `fk_file_object_owner` FOREIGN KEY (`owner_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_object_size` CHECK (`byte_size` > 0),
  CONSTRAINT `ck_file_object_status` CHECK (`status` IN ('TEMP', 'BOUND', 'FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `file_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `file_id` BIGINT UNSIGNED NOT NULL,
  `purpose` VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `draft_target_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  `field_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  `expense_item_id` BIGINT UNSIGNED NULL,
  `quote_id` BIGINT UNSIGNED NULL,
  `receipt_number` VARCHAR(80) NULL,
  `receipt_sha256` BINARY(32) NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_file_binding_application_file` (`application_id`, `file_id`),
  UNIQUE KEY `uq_file_binding_application_receipt` (`application_id`, `receipt_sha256`),
  KEY `idx_file_binding_receipt` (`receipt_sha256`, `application_id`),
  KEY `idx_file_binding_file` (`file_id`),
  CONSTRAINT `fk_file_binding_application` FOREIGN KEY (`application_id`)
    REFERENCES `application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_file_binding_file` FOREIGN KEY (`file_id`)
    REFERENCES `file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_binding_purpose` CHECK (`purpose` IN ('GENERAL', 'QUOTE_EVIDENCE', 'EXPENSE_RECEIPT', 'CUSTOM_FIELD')),
  CONSTRAINT `ck_file_binding_target` CHECK (
    (`purpose` = 'GENERAL'
      AND `draft_target_key` IS NULL AND `field_key` IS NULL
      AND `expense_item_id` IS NULL AND `quote_id` IS NULL
      AND `receipt_number` IS NULL AND `receipt_sha256` IS NULL)
    OR (`purpose` = 'QUOTE_EVIDENCE'
      AND ((`draft_target_key` IS NULL) <> (`quote_id` IS NULL))
      AND `field_key` IS NULL AND `expense_item_id` IS NULL
      AND `receipt_number` IS NULL AND `receipt_sha256` IS NULL)
    OR (`purpose` = 'EXPENSE_RECEIPT'
      AND ((`draft_target_key` IS NULL) <> (`expense_item_id` IS NULL))
      AND `field_key` IS NULL AND `quote_id` IS NULL
      AND `receipt_sha256` IS NOT NULL)
    OR (`purpose` = 'CUSTOM_FIELD'
      AND `field_key` IS NOT NULL AND `draft_target_key` IS NULL
      AND `expense_item_id` IS NULL AND `quote_id` IS NULL
      AND `receipt_number` IS NULL AND `receipt_sha256` IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipient_id` BIGINT UNSIGNED NOT NULL,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `delivery_seq` BIGINT UNSIGNED NOT NULL,
  `event_key` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `summary` VARCHAR(200) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `read_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_notification_event` (`recipient_id`, `event_key`),
  UNIQUE KEY `uq_notification_seq` (`recipient_id`, `delivery_seq`),
  KEY `idx_notification_read` (`recipient_id`, `read_at`, `delivery_seq`),
  CONSTRAINT `fk_notification_recipient` FOREIGN KEY (`recipient_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_notification_application` FOREIGN KEY (`application_id`)
    REFERENCES `application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `inbox_state` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `last_delivery_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_inbox_state_user` FOREIGN KEY (`user_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `rate_limit_bucket` (
  `bucket_key` BINARY(32) NOT NULL,
  `window_start` DATETIME(6) NOT NULL,
  `counter` INT UNSIGNED NOT NULL DEFAULT 0,
  `expires_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`bucket_key`, `window_start`),
  KEY `idx_rate_limit_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `request_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `request_key` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `payload_hash` BINARY(32) NOT NULL,
  `target_id` BIGINT UNSIGNED NULL,
  `result_code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `result_summary` JSON NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_request_record_key` (`actor_id`, `action`, `request_key`),
  KEY `idx_request_record_created` (`created_at`, `id`),
  CONSTRAINT `fk_request_record_actor` FOREIGN KEY (`actor_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `mail_delivery` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `notification_id` BIGINT UNSIGNED NOT NULL,
  `recipient_email_snapshot` VARCHAR(254) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `attempts` INT UNSIGNED NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME(6) NULL,
  `lease_until` DATETIME(6) NULL,
  `last_error` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_mail_delivery_notification` (`notification_id`),
  KEY `idx_mail_delivery_retry` (`status`, `next_retry_at`, `id`),
  CONSTRAINT `fk_mail_delivery_notification` FOREIGN KEY (`notification_id`)
    REFERENCES `notification` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `audit_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT UNSIGNED NULL,
  `target_type` VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `target_id` BIGINT UNSIGNED NULL,
  `action` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `before_values` JSON NULL,
  `after_values` JSON NULL,
  `reason` VARCHAR(2000) NULL,
  `request_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_actor` (`actor_id`, `created_at`, `id`),
  KEY `idx_audit_target` (`target_type`, `target_id`, `created_at`, `id`),
  CONSTRAINT `fk_audit_actor` FOREIGN KEY (`actor_id`)
    REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `file_cleanup` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_id` BIGINT UNSIGNED NULL,
  `storage_key` VARCHAR(255) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `attempts` INT UNSIGNED NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_file_cleanup_retry` (`status`, `next_retry_at`, `id`),
  CONSTRAINT `ck_file_cleanup_target` CHECK (`file_id` IS NOT NULL OR `storage_key` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
