-- TASK-02 minimal production seed.
--
-- This seed is intentionally data-only and idempotent. It never creates demo
-- accounts and never overwrites an existing department, role or configuration
-- row. Demo accounts live behind the separate `demo` Spring profile.
--
-- The three department names and five role codes are the fixed baseline from
-- the specification. Re-running this file must leave observed row counts and
-- existing values unchanged.

INSERT INTO `department` (`name`, `enabled`, `manager_user_id`, `row_version`, `created_at`, `updated_at`)
SELECT '研发部', 1, NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `department` WHERE `name` = '研发部');

INSERT INTO `department` (`name`, `enabled`, `manager_user_id`, `row_version`, `created_at`, `updated_at`)
SELECT '市场部', 1, NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `department` WHERE `name` = '市场部');

INSERT INTO `department` (`name`, `enabled`, `manager_user_id`, `row_version`, `created_at`, `updated_at`)
SELECT '财务部', 1, NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `department` WHERE `name` = '财务部');

INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
SELECT 'EMPLOYEE', '员工', '普通员工，可创建并提交本人申请', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'EMPLOYEE');

INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
SELECT 'DEPT_HEAD', '部门负责人', '审批本部门申请，可兼任其他角色', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'DEPT_HEAD');

INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
SELECT 'FINANCE_APPROVER', '财务审批人', '执行财务审批，不隐含系统管理权限', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'FINANCE_APPROVER');

INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
SELECT 'ENTERPRISE_HEAD', '企业负责人', '执行企业负责人审批，不隐含系统管理权限', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'ENTERPRISE_HEAD');

INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
SELECT 'SYS_ADMIN', '系统管理员', '管理组织、配置与账号，不隐含审批权限', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'SYS_ADMIN');

INSERT INTO `system_config` (`id`, `active_policy_id`, `row_version`, `updated_at`)
SELECT 1, NULL, 1, UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `id` = 1);

-- Repair a missing inbox row without changing an existing sequence or revision.
INSERT INTO `inbox_state` (`user_id`, `last_delivery_seq`, `revision`, `updated_at`)
SELECT `u`.`id`, 0, 0, UTC_TIMESTAMP(6)
FROM `user` AS `u`
WHERE NOT EXISTS (SELECT 1 FROM `inbox_state` AS `s` WHERE `s`.`user_id` = `u`.`id`)
ORDER BY `u`.`id`;
