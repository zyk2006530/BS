-- Runtime least privilege for the business account. The account is created by
-- deploy/mysql/init/01-accounts.sh before Flyway runs. The migration account has
-- GRANT OPTION only for this schema and is not used by the long-running backend.
--
-- audit_record is append-only: no UPDATE or DELETE is granted. approval_record is
-- intentionally absent in TASK-02 (flow/instance semantics belong to TASK-09), so
-- its INSERT/SELECT-only grant must be added together with that table.

GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`department` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`user` TO 'approval_app'@'%';
GRANT SELECT ON `${flyway:database}`.`role` TO 'approval_app'@'%';
GRANT SELECT, INSERT, DELETE ON `${flyway:database}`.`user_role` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`data_grant` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`auth_session` TO 'approval_app'@'%';
GRANT SELECT, UPDATE ON `${flyway:database}`.`system_config` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`application` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`application_draft` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`file_object` TO 'approval_app'@'%';
GRANT SELECT, INSERT, DELETE ON `${flyway:database}`.`file_binding` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`notification` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`inbox_state` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`rate_limit_bucket` TO 'approval_app'@'%';
GRANT SELECT, INSERT ON `${flyway:database}`.`request_record` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:database}`.`mail_delivery` TO 'approval_app'@'%';
GRANT SELECT, INSERT ON `${flyway:database}`.`audit_record` TO 'approval_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `${flyway:database}`.`file_cleanup` TO 'approval_app'@'%';
