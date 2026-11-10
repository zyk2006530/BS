# TASK-02 迁移和最小初始化验证记录

- 日期：2026-11-10（Asia/Singapore）
- 结论：TASK-02 的数据库迁移、最小初始化、账号权限基线和真实 MySQL 验证已完成；T11、T15、T18、T20、T22 仍为 `NOT_RUN`，因为对应 API、流程、部署和审计功能尚未实现，不能用本次数据库证据替代验收。
- 机器状态：TASK-02 为 `done`，依赖调整为 TASK-00；TASK-01 仍为 `in_progress`。依赖调整理由见下文“依赖与边界”。

## 交付内容

| 文件 | 作用 |
| --- | --- |
| [V1__task02_baseline_schema.sql](<../../backend/src/main/resources/db/migration/V1__task02_baseline_schema.sql>) | 18 张 TASK-02 基础表：部门、用户、角色、授权、会话、配置、申请/草稿、文件/绑定、通知/收件箱、限流、请求记录、邮件、审计、清理。 |
| [V2__task02_base_seed.sql](<../../backend/src/main/resources/db/migration/V2__task02_base_seed.sql>) | 幂等的三部门、五角色、单行配置和缺失收件箱行修复；不创建演示账号。 |
| [V3__task02_runtime_grants.sql](<../../backend/src/main/resources/db/migration/V3__task02_runtime_grants.sql>) | 迁移账号对业务账号的逐表最小授权；审计只授予 `SELECT, INSERT`，使用 `${flyway:database}` 占位符适配数据库名。 |
| [DemoDataInitializer.java](<../../backend/src/main/java/cn/edu/approval/demo/DemoDataInitializer.java>) | 仅 `demo` profile 的演示账号初始化；密码只从 `APP_DEMO_PASSWORD` 读取，Argon2id 参数固定，重复运行不覆盖。 |
| [01-accounts.sh](<../../deploy/mysql/init/01-accounts.sh>) | MySQL 首次初始化时创建固定的 `approval_app` 与 `approval_migrator`；密码来自环境，迁移账号持有该 schema 的全部权限与 `GRANT OPTION`，仅用于迁移。 |
| [compose.yaml](<../../deploy/compose.yaml>) | 三常驻服务（frontend/backend/mysql）加一次性 `migrate` 作业：后端等待迁移成功、使用业务账号且关闭 Flyway，MySQL 只在内部网络。 |
| [verify_task02_mysql.py](<../../scripts/verify_task02_mysql.py>) | 可复现的真实 MySQL 8.4 验证脚本；启动一次性容器、构建后端、执行迁移/demo、检查约束和权限、输出 JSON 证据。 |

关键边界：`flow_template`、`template_version`、`flow_instance`、`instance_node`、`approval_record`、`reminder_record` 等流程/模板/实例语义表**没有**在本任务创建；它们属于 TASK-09/TASK-10 的独立编码范围。`application` 的 `template_version_id`、`system_config.active_policy_id`、`file_binding.expense_item_id/quote_id` 只保留列，不加跨任务外键。

## 真实 MySQL 验证

环境：Zulu OpenJDK `17.0.17+10`、Maven `3.9.11`、Docker Engine `29.9.3`、MySQL `8.4.7`，镜像摘要 `sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903`。

```powershell
# 仓库根目录
python scripts/verify_task02_mysql.py
```

2026-11-10 实际执行结果（11/11 通过，证据见 [task-02-mysql.json](<../../backend/evidence/task-02-mysql.json>)）：

| 检查 | 结果 |
| --- | --- |
| MySQL 初始化账号隔离 | 业务账号初始只有 `USAGE`；迁移账号有 schema 全部权限和 `GRANT OPTION`，仅用于迁移。 |
| 空库迁移 | V1/V2/V3 全部成功；18 张业务表 + `flyway_schema_history`；3 部门、5 角色、配置 `id=1,row_version=1`；无演示用户/收件箱行。 |
| 重复迁移 | 第二次 `migrate` 报告 `up to date`；部门/角色/用户/收件箱/配置计数与版本不变。 |
| 演示种子与收件箱 | `DEMO_SEED: users=11 newly_inserted=11 inbox_rows=11`；11 用户、11 角色绑定、11 收件箱行；负责人 1/3/5；`pending_user` 无部门无角色；`admin` 只有 `SYS_ADMIN`；密码哈希为 `$argon2id$v=19$m=19456,t=2,p=1$...`，每个用户使用独立盐。 |
| 演示重复执行 | 第二次 `newly_inserted=0`；用户状态哈希 `d9a512ff383cfd250309a4511acdbf31560e73460beb623ee2dde306cc7c5e38` 不变。 |
| V2 直接重放 | 连续执行两次后部门/角色/配置/收件箱计数仍为 3/5/1/11。 |
| 业务账号审计权限 | `INSERT` 审计成功；`UPDATE`、`DELETE`、`CREATE TABLE` 均返回 `ERROR 1142`；授权为 `SELECT, INSERT`。 |
| 流程表未偷带 | `SHOW TABLES LIKE 'approval_record'` 为空。 |
| 外键/唯一/检查约束 | 部门名、用户名、邮箱重复均 1062；不存在申请人外键 1452；ACTIVE 缺部门、REJECTED 缺原因、提交缺编号/标题/快照、CUSTOM 缺模板、固定类型带模板、配置第二行、版本 0 均为 3819；文件绑定重复和同一申请同一票据哈希重复均 1062；`DECIMAL(14,2)` 将 `123.456` 存为 `123.46`；`9007199254740993` 作为字符串精确往返。 |
| 生产/演示分离 | 独立 `approval_prod` 数据库迁移后 0 用户、0 收件箱、3 部门、5 角色；V3 占位符把授权正确指向第二个数据库；重复迁移和 V2 重放不增量。 |
| 重启持久 | 容器重启后 3 部门、5 角色、11 用户和演示状态哈希不变。 |

MySQL 8.4 下 Flyway `11.7.2` 会打印 `Flyway upgrade recommended: MySQL 8.4 is newer...`；迁移实际成功，但正式发布应评估升级 Flyway 或固定到已验证的 MySQL 8.x 版本。

## 完成条件对照

- 空库迁移：已验证。`V1` 首次执行时曾因 MySQL 3818（检查约束不能引用自增列）移除 `ck_application_source`；`source_application_id <> id` 由后续服务层校验，不能依赖该检查约束。
- 重复初始化不覆盖：已验证迁移、V2、演示种子三条路径。
- 真实 MySQL 约束：已验证唯一、外键、检查约束、金额小数位和大 ID 字符串。
- 审批/审计账号禁止 UPDATE/DELETE：当前可验证的 `audit_record` 已做到只 `SELECT, INSERT`；`approval_record` 按任务边界不存在，TASK-09 创建该表时必须沿用同一授权模板。因此本次**不**把 T22 标为通过。
- 三部门五角色：已验证研发部/市场部/财务部与 `EMPLOYEE`、`DEPT_HEAD`、`FINANCE_APPROVER`、`ENTERPRISE_HEAD`、`SYS_ADMIN`。
- 收件箱行初始化：演示数据库 11 行；生产迁移没有用户，故为 0 行；V2 会为每个已存在用户补缺失行。
- 生产/演示分离：演示数据只由 `demo` profile 写入，验证中使用独立数据库；生产迁移 0 用户。
- 密码环境注入：MySQL 三个密码来自环境；演示初始密码只来自 `APP_DEMO_PASSWORD`；仓库中没有默认密码。`01-accounts.sh` 拒绝缺失变量和含换行的密码。
- 业务账号最小权限：`approval_app` 不持有 DDL，不能改审计；长运行后端不持有迁移账号；迁移由一次性 `migrate` 服务执行，后端 `SPRING_FLYWAY_ENABLED=false`。

## 依赖与边界

`tasks.md` 原先把 TASK-02 依赖设为 TASK-01。TASK-01 的未完成项是微信云端/体验成员/真机、SMTP 真实收件、Docker/MySQL 容器实测等外部接入；这些不是数据库 schema 与迁移的前置条件。为避免“外部接入未完成”无限阻塞数据库任务，本次把 TASK-02 依赖调整为 TASK-00，并在任务文件中记录；TASK-01 仍是 `in_progress`，不把其外部条件虚报为完成。

本次没有执行 `docker compose up`。三常驻服务加一次性迁移作业的 Compose、迁移命令、账号脚本和授权只做了静态测试、`docker compose config --quiet` 语法/插值检查和**直接容器**的等价验证；本次尝试构建后端镜像时 Docker Hub 元数据请求超时，因此镜像构建也未完成。`migrate` 作业、Nginx 到后端的同源迁移门禁、空环境整体启动仍应归入 T15 的部署验收，不能由本记录替代。T11 的文件上传/绑定/清理 API、T18 的管理版本并发、T20 的金额边界业务提交、T22 的完整审计与生产账号检查同样尚未实现。

## 复现命令

```powershell
. ./scripts/use-java.ps1
python scripts/verify_task02_mysql.py
python -m unittest discover -s tests -p "test_*.py" -v
.\backend\mvnw.cmd -f backend/pom.xml -B verify
```

脚本默认构建当前后端、启动一次性 MySQL、结束后删除容器；`--keep` 保留容器供检查，`--skip-build` 使用已有 jar。脚本失败时仍写出证据 JSON，但 `verdict` 为 `FAIL`。
