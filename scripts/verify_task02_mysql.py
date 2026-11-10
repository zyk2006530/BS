#!/usr/bin/env python3
"""Reproducible TASK-02 verification against a disposable real MySQL 8.4.

The harness starts the pinned MySQL image with the reviewed account-init script,
builds the backend jar, runs the one-shot Flyway/demo profiles, and checks the
TASK-02 completion conditions. It deliberately does not mark T11/T15/T18/T20/T22
as PASS: those acceptance cases also require the application APIs, workflow,
deployment and audit features that do not exist yet.

Usage:
    python scripts/verify_task02_mysql.py
    python scripts/verify_task02_mysql.py --skip-build --keep
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
JAR = BACKEND / "target" / "approval-backend-0.1.0-SNAPSHOT.jar"
MIGRATION_DIR = BACKEND / "src/main/resources/db/migration"
INIT_DIR = ROOT / "deploy/mysql/init"
IMAGE = "mysql:8.4.7@sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903"


class Failure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


def find_java() -> Path:
    java_home = os.environ.get("JAVA_HOME")
    candidates = []
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java.exe")
    candidates.extend(sorted((ROOT / ".local").glob("*/bin/java.exe")))
    if shutil.which("java"):
        candidates.append(Path(shutil.which("java") or ""))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise Failure("Java 17 executable not found; set JAVA_HOME or populate .local")


def java_home_for(java: Path) -> Path:
    return java.parent.parent


def run(command: list[str], *, env: dict[str, str] | None = None, timeout: int = 600) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(part) for part in command],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def docker(args: list[str], *, check: bool = False, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    result = run(["docker", *args], timeout=timeout)
    if check and result.returncode != 0:
        raise Failure(f"docker {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class MysqlHarness:
    def __init__(self, java: Path, *, build: bool, keep: bool):
        self.java = java
        self.build = build
        self.keep = keep
        self.container = f"approval-task02-it-{secrets.token_hex(4)}"
        self.port = free_port()
        suffix = secrets.token_hex(8)
        self.root_password = f"T02Root{suffix}Aa1"
        self.app_password = f"T02App{suffix}Bb2"
        self.migration_password = f"T02Mig{suffix}Cc3"
        self.demo_password = f"T02Demo{suffix}Dd4"
        self.checks: list[dict[str, str]] = []
        self.started = False

    def mysql(
        self,
        sql: str,
        *,
        database: str | None = "approval",
        user: str = "root",
        password: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        password = password or self.root_password
        args = [
            "exec",
            "-e",
            f"MYSQL_PWD={password}",
            self.container,
            "mysql",
            "--host=127.0.0.1",
            "--port=3306",
            "--user",
            user,
            "--batch",
            "--raw",
            "--skip-column-names",
            "--default-character-set=utf8mb4",
        ]
        if database:
            args.extend(["--database", database])
        args.extend(["--execute", sql])
        return docker(args, timeout=120)

    def scalar(self, sql: str, *, database: str | None = "approval", user: str = "root", password: str | None = None) -> str:
        result = self.mysql(sql, database=database, user=user, password=password)
        require(result.returncode == 0, f"query failed: {result.stderr.strip()}\nSQL: {sql}")
        lines = [line for line in result.stdout.splitlines() if line != ""]
        require(bool(lines), f"query returned no rows: {sql}")
        return lines[-1]

    def query(self, sql: str, *, database: str | None = "approval", user: str = "root", password: str | None = None) -> list[list[str]]:
        result = self.mysql(sql, database=database, user=user, password=password)
        require(result.returncode == 0, f"query failed: {result.stderr.strip()}\nSQL: {sql}")
        return [line.split("\t") for line in result.stdout.splitlines() if line != ""]

    def expect_error(self, sql: str, code: int, *, database: str = "approval", user: str = "root", password: str | None = None) -> str:
        result = self.mysql(sql, database=database, user=user, password=password)
        if result.returncode == 0:
            raise Failure(f"expected MySQL error {code}, statement succeeded: {sql}")
        match = re.search(r"ERROR (\d+)", result.stderr)
        actual = int(match.group(1)) if match else -1
        require(actual == code, f"expected MySQL error {code}, got {actual}: {result.stderr.strip()}")
        return result.stderr.strip()

    def start(self) -> None:
        if not INIT_DIR.joinpath("01-accounts.sh").is_file():
            raise Failure(f"missing account-init script under {INIT_DIR}")
        result = docker(
            [
                "run",
                "-d",
                "--name",
                self.container,
                "-p",
                f"127.0.0.1:{self.port}:3306",
                "-e",
                f"MYSQL_ROOT_PASSWORD={self.root_password}",
                "-e",
                "MYSQL_DATABASE=approval",
                "-e",
                f"MYSQL_APP_PASSWORD={self.app_password}",
                "-e",
                f"MYSQL_MIGRATION_PASSWORD={self.migration_password}",
                "-v",
                f"{INIT_DIR}:/docker-entrypoint-initdb.d:ro",
                IMAGE,
            ],
            check=True,
        )
        require(result.stdout.strip() != "", "docker run returned no container id")
        self.started = True
        deadline = time.time() + 180
        while time.time() < deadline:
            probe = self.mysql("SELECT 1")
            if probe.returncode == 0:
                return
            time.sleep(2)
        logs = docker(["logs", self.container], timeout=60)
        raise Failure(f"MySQL did not become ready:\n{logs.stdout[-4000:]}\n{logs.stderr[-2000:]}")

    def stop(self) -> None:
        if self.started and not self.keep:
            docker(["rm", "-f", self.container], timeout=120)

    def app_env(self, database: str, user: str, password: str, *, demo: bool) -> dict[str, str]:
        env = os.environ.copy()
        env.update(
            {
                "JAVA_HOME": str(java_home_for(self.java)),
                "DB_URL": f"jdbc:mysql://127.0.0.1:{self.port}/{database}?connectionTimeZone=UTC",
                "DB_USERNAME": user,
                "DB_PASSWORD": password,
                "DB_MIGRATION_USER": user,
                "DB_MIGRATION_PASSWORD": password,
            }
        )
        if demo:
            env["APP_DEMO_PASSWORD"] = self.demo_password
        return env

    def run_profile(self, profiles: str, database: str, *, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
        return run(
            [
                self.java,
                "-jar",
                JAR,
                f"--spring.profiles.active={profiles}",
                "--spring.main.web-application-type=none",
            ],
            env=env,
            timeout=240,
        )

    def check(self, name: str, function) -> None:
        started = time.time()
        try:
            detail = function()
            self.checks.append(
                {"name": name, "status": "PASS", "detail": detail, "seconds": f"{time.time() - started:.3f}"}
            )
            print(f"[PASS] {name}: {detail}")
        except Exception as error:  # noqa: BLE001 - the harness records the failure as evidence
            self.checks.append(
                {
                    "name": name,
                    "status": "FAIL",
                    "detail": str(error),
                    "seconds": f"{time.time() - started:.3f}",
                }
            )
            print(f"[FAIL] {name}: {error}")
            raise

    def artifact(self) -> dict:
        try:
            user_rows = self.query("SELECT username, password_hash, status FROM `user` ORDER BY id")
            state_hash = hashlib.sha256(
                "\n".join("\t".join(row) for row in user_rows).encode("utf-8")
            ).hexdigest()
        except Exception:  # noqa: BLE001 - evidence must still be written when setup failed
            state_hash = None
        return {
            "task": "TASK-02",
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "mysql_image": IMAGE,
            "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest() if JAR.is_file() else None,
            "database": f"127.0.0.1:{self.port}/approval",
            "demo_user_state_sha256": state_hash,
            "checks": self.checks,
        }


def build_jar(java: Path) -> None:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home_for(java))
    wrapper = BACKEND / "mvnw.cmd" if os.name == "nt" else BACKEND / "mvnw"
    prefix = ["cmd.exe", "/c"] if os.name == "nt" else []
    result = run(
        [
            *prefix,
            str(wrapper),
            "-f",
            "backend/pom.xml",
            "-q",
            "spotless:apply",
            "package",
            "-DskipTests",
        ],
        env=env,
        timeout=900,
    )
    require(result.returncode == 0, f"backend build failed:\n{result.stdout[-4000:]}\n{result.stderr[-4000:]}")
    require(JAR.is_file(), f"backend jar not produced at {JAR}")


def verify_task02(harness: MysqlHarness) -> None:
    if harness.build:
        build_jar(harness.java)
    require(JAR.is_file(), f"missing backend jar at {JAR}")
    harness.start()

    def init_accounts() -> str:
        app_grants = harness.scalar("SHOW GRANTS FOR 'approval_app'@'%'")
        migrator_grants = harness.query("SHOW GRANTS FOR 'approval_migrator'@'%'")
        require("USAGE ON *.*" in app_grants, f"application account has more than USAGE at init: {app_grants}")
        require(any("GRANT OPTION" in row[0] for row in migrator_grants), "migration account lacks GRANT OPTION")
        return "application account starts with USAGE only; migration account has schema DDL + GRANT OPTION"

    harness.check("account separation at MySQL init", init_accounts)

    def fresh_migration() -> str:
        env = harness.app_env("approval", "approval_migrator", harness.migration_password, demo=False)
        result = harness.run_profile("db,migrate", "approval", env=env)
        require(result.returncode == 0, f"migration failed:\n{result.stdout[-6000:]}\n{result.stderr[-4000:]}")
        history = harness.query("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")
        require([row[0] for row in history] == ["1", "2", "3"], f"unexpected migration history: {history}")
        require(all(row[2] == "1" for row in history), f"failed migration history: {history}")
        table_count = harness.scalar(
            "SELECT COUNT(*) FROM information_schema.tables "
            "WHERE table_schema='approval' AND table_name <> 'flyway_schema_history'"
        )
        require(table_count == "18", f"expected 18 TASK-02 tables, got {table_count}")
        departments = harness.query("SELECT name FROM department ORDER BY id")
        require([row[0] for row in departments] == ["研发部", "市场部", "财务部"], f"unexpected departments: {departments}")
        roles = harness.query("SELECT code FROM role ORDER BY id")
        require(
            [row[0] for row in roles]
            == ["EMPLOYEE", "DEPT_HEAD", "FINANCE_APPROVER", "ENTERPRISE_HEAD", "SYS_ADMIN"],
            f"unexpected roles: {roles}",
        )
        require(harness.scalar("SELECT COUNT(*) FROM user") == "0", "production migration created users")
        require(harness.scalar("SELECT COUNT(*) FROM inbox_state") == "0", "production migration created inbox rows")
        config = harness.query("SELECT id, row_version FROM system_config")
        require(config == [["1", "1"]], f"unexpected system_config: {config}")
        return "V1/V2/V3 applied; 18 tables; 3 departments; 5 roles; zero demo users/inbox rows; config id=1 version=1"

    harness.check("empty-database migration", fresh_migration)

    def idempotent_migration() -> str:
        before = harness.query(
            "SELECT (SELECT COUNT(*) FROM department), (SELECT COUNT(*) FROM role), "
            "(SELECT COUNT(*) FROM user), (SELECT COUNT(*) FROM inbox_state), "
            "(SELECT row_version FROM system_config WHERE id=1)"
        )
        env = harness.app_env("approval", "approval_migrator", harness.migration_password, demo=False)
        result = harness.run_profile("db,migrate", "approval", env=env)
        require(result.returncode == 0, f"second migration failed:\n{result.stdout[-6000:]}\n{result.stderr[-4000:]}")
        require("up to date" in (result.stdout + result.stderr).lower(), "second migration did not report up-to-date")
        after = harness.query(
            "SELECT (SELECT COUNT(*) FROM department), (SELECT COUNT(*) FROM role), "
            "(SELECT COUNT(*) FROM user), (SELECT COUNT(*) FROM inbox_state), "
            "(SELECT row_version FROM system_config WHERE id=1)"
        )
        require(before == after, f"re-running migration changed data: {before} -> {after}")
        return f"second migrate reported up-to-date; counts/config unchanged: {before[0]}"

    harness.check("repeated migration does not overwrite data", idempotent_migration)

    def demo_seed() -> str:
        env = harness.app_env("approval", "approval_migrator", harness.migration_password, demo=True)
        result = harness.run_profile("db,demo,migrate", "approval", env=env)
        require(result.returncode == 0, f"demo seed failed:\n{result.stdout[-6000:]}\n{result.stderr[-4000:]}")
        require("DEMO_SEED: users=11 newly_inserted=11 inbox_rows=11" in result.stdout, "unexpected demo seed output")
        require(harness.scalar("SELECT COUNT(*) FROM user") == "11", "demo user count")
        require(harness.scalar("SELECT COUNT(*) FROM user_role") == "11", "demo user_role count")
        require(harness.scalar("SELECT COUNT(*) FROM inbox_state") == "11", "demo inbox_state count")
        managers = harness.query("SELECT name, manager_user_id FROM department ORDER BY id")
        require(managers == [["研发部", "1"], ["市场部", "3"], ["财务部", "5"]], f"unexpected managers: {managers}")
        pending = harness.query(
            "SELECT u.department_id, COUNT(ur.role_id) FROM user u LEFT JOIN user_role ur ON ur.user_id=u.id "
            "WHERE u.username='pending_user' GROUP BY u.id"
        )
        require(pending == [["NULL", "0"]], f"pending user must have no department/role: {pending}")
        admin_roles = harness.query(
            "SELECT r.code FROM user_role ur JOIN role r ON r.id=ur.role_id JOIN user u ON u.id=ur.user_id "
            "WHERE u.username='admin' ORDER BY r.code"
        )
        require(admin_roles == [["SYS_ADMIN"]], f"admin has unexpected roles: {admin_roles}")
        fin_roles = harness.query(
            "SELECT r.code FROM user_role ur JOIN role r ON r.id=ur.role_id JOIN user u ON u.id=ur.user_id "
            "WHERE u.username='fin_head' ORDER BY r.code"
        )
        require(fin_roles == [["DEPT_HEAD"], ["FINANCE_APPROVER"]], f"fin_head roles: {fin_roles}")
        hashes = harness.query("SELECT password_hash FROM user ORDER BY id")
        pattern = re.compile(r"^\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$")
        require(all(pattern.match(row[0]) for row in hashes), "demo password hashes are not Argon2id with the required parameters")
        require(len({row[0] for row in hashes}) == len(hashes), "demo password hashes must use distinct salts")
        return "11 users; 11 roles; 11 inbox rows; managers 1/3/5; pending has no department/role; Argon2id parameters exact and salts distinct"

    harness.check("demo seed and inbox initialization", demo_seed)

    def demo_idempotent() -> str:
        before = harness.artifact()["demo_user_state_sha256"]
        env = harness.app_env("approval", "approval_migrator", harness.migration_password, demo=True)
        result = harness.run_profile("db,demo,migrate", "approval", env=env)
        require(result.returncode == 0, f"demo reseed failed:\n{result.stdout[-6000:]}\n{result.stderr[-4000:]}")
        require("newly_inserted=0" in result.stdout, "demo reseed inserted users")
        after = harness.artifact()["demo_user_state_sha256"]
        require(before == after, f"demo reseed changed user state: {before} -> {after}")
        require(harness.scalar("SELECT COUNT(*) FROM user") == "11", "demo reseed changed user count")
        return f"demo reseed inserted 0; user-state hash unchanged ({before[:16]}…)"

    harness.check("demo reseed does not overwrite accounts", demo_idempotent)

    def v2_idempotent() -> str:
        sql = (MIGRATION_DIR / "V2__task02_base_seed.sql").read_text(encoding="utf-8")
        before = harness.query(
            "SELECT (SELECT COUNT(*) FROM department), (SELECT COUNT(*) FROM role), "
            "(SELECT COUNT(*) FROM system_config), (SELECT COUNT(*) FROM inbox_state)"
        )
        for _ in range(2):
            result = harness.mysql(sql, database="approval")
            require(result.returncode == 0, f"V2 replay failed: {result.stderr.strip()}")
        after = harness.query(
            "SELECT (SELECT COUNT(*) FROM department), (SELECT COUNT(*) FROM role), "
            "(SELECT COUNT(*) FROM system_config), (SELECT COUNT(*) FROM inbox_state)"
        )
        require(before == after, f"V2 replay changed counts: {before} -> {after}")
        return "V2 SQL replayed twice; department/role/config/inbox counts unchanged (3/5/1/11)"

    harness.check("base seed replay is idempotent", v2_idempotent)

    def audit_privileges() -> str:
        insert = harness.mysql(
            "INSERT INTO audit_record (target_type,target_id,action,created_at) "
            "VALUES ('USER',1,'TASK02_APPEND_TEST',UTC_TIMESTAMP(6));",
            user="approval_app",
            password=harness.app_password,
        )
        require(insert.returncode == 0, f"app cannot append audit: {insert.stderr.strip()}")
        update = harness.mysql(
            "UPDATE audit_record SET action='TAMPER' WHERE action='TASK02_APPEND_TEST';",
            user="approval_app",
            password=harness.app_password,
        )
        require(update.returncode != 0 and "ERROR 1142" in update.stderr, "app can update audit_record")
        delete = harness.mysql(
            "DELETE FROM audit_record WHERE action='TASK02_APPEND_TEST';",
            user="approval_app",
            password=harness.app_password,
        )
        require(delete.returncode != 0 and "ERROR 1142" in delete.stderr, "app can delete audit_record")
        ddl = harness.mysql("CREATE TABLE task02_should_fail (id INT);", user="approval_app", password=harness.app_password)
        require(ddl.returncode != 0 and "ERROR 1142" in ddl.stderr, "app can execute DDL")
        audit_grants = harness.query("SHOW GRANTS FOR 'approval_app'@'%'")
        require(
            any("SELECT, INSERT ON `approval`.`audit_record`" in row[0] for row in audit_grants),
            "audit grant is not SELECT+INSERT",
        )
        return "app INSERT audit succeeds; UPDATE/DELETE/DDL fail with 1142; audit grant is SELECT+INSERT only"

    harness.check("business account cannot mutate audit records", audit_privileges)

    def approval_record_absent() -> str:
        rows = harness.query("SHOW TABLES LIKE 'approval_record'")
        require(rows == [], f"approval_record must not exist in TASK-02: {rows}")
        return "approval_record intentionally absent (TASK-09 owns flow/instance semantics; its grant must be added there)"

    harness.check("flow/instance tables are not smuggled into TASK-02", approval_record_absent)

    def constraints() -> str:
        harness.expect_error(
            "INSERT INTO department (name,enabled,row_version,created_at,updated_at) "
            "VALUES ('研发部',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            1062,
        )
        harness.expect_error(
            "INSERT INTO user (username,email,password_hash,name,requested_department_id,status,created_at,updated_at) "
            "VALUES ('rd_staff','other@example.com','x','Dup',1,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            1062,
        )
        harness.expect_error(
            "INSERT INTO user (username,email,password_hash,name,requested_department_id,status,created_at,updated_at) "
            "VALUES ('other_user','rd_staff@demo.example.test','x','Dup',1,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            1062,
        )
        harness.expect_error(
            "INSERT INTO user (username,email,password_hash,name,requested_department_id,status,created_at,updated_at) "
            "VALUES ('UPPER_USER','upper@example.com','x','Dup',1,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO application (type,applicant_id,dept_id,created_at,updated_at) "
            "VALUES ('PURCHASE',999999,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            1452,
        )
        harness.expect_error(
            "INSERT INTO user (username,email,password_hash,name,requested_department_id,status,created_at,updated_at) "
            "VALUES ('active_no_dept','active@example.com','x','Dup',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO user (username,email,password_hash,name,requested_department_id,status,created_at,updated_at) "
            "VALUES ('rejected_no_reason','rejected@example.com','x','Dup',1,'REJECTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO application (type,applicant_id,dept_id,status,created_at,updated_at) "
            "VALUES ('PURCHASE',1,1,'IN_REVIEW',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO application (type,applicant_id,dept_id,status,created_at,updated_at) "
            "VALUES ('CUSTOM',1,1,'DRAFT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO application (type,template_version_id,applicant_id,dept_id,created_at,updated_at) "
            "VALUES ('PURCHASE',99,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO system_config (id,active_policy_id,row_version,updated_at) "
            "VALUES (2,NULL,1,UTC_TIMESTAMP(6));",
            3819,
        )
        harness.expect_error(
            "INSERT INTO department (name,enabled,row_version,created_at,updated_at) "
            "VALUES ('VersionZero',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            3819,
        )

        app_id = harness.scalar(
            "INSERT INTO application (type,applicant_id,dept_id,created_at,updated_at) "
            "VALUES ('PURCHASE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();"
        )
        file_id = harness.scalar(
            "INSERT INTO file_object (owner_id,storage_key,original_name,media_type,byte_size,sha256,created_at,updated_at) "
            "VALUES (1,'task02-constraint-a','a.pdf','application/pdf',10,UNHEX(SHA2('a',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); "
            "SELECT LAST_INSERT_ID();"
        )
        file2_id = harness.scalar(
            "INSERT INTO file_object (owner_id,storage_key,original_name,media_type,byte_size,sha256,created_at,updated_at) "
            "VALUES (1,'task02-constraint-b','b.pdf','application/pdf',10,UNHEX(SHA2('b',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); "
            "SELECT LAST_INSERT_ID();"
        )
        file3_id = harness.scalar(
            "INSERT INTO file_object (owner_id,storage_key,original_name,media_type,byte_size,sha256,created_at,updated_at) "
            "VALUES (1,'task02-constraint-c','c.pdf','application/pdf',10,UNHEX(SHA2('c',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); "
            "SELECT LAST_INSERT_ID();"
        )
        general = (
            f"INSERT INTO file_binding (application_id,file_id,purpose,created_at) "
            f"VALUES ({app_id},{file_id},'GENERAL',UTC_TIMESTAMP(6));"
        )
        require(harness.mysql(general, database="approval").returncode == 0, "general binding insert failed")
        harness.expect_error(general, 1062)
        receipt_one = (
            f"INSERT INTO file_binding (application_id,file_id,purpose,expense_item_id,receipt_number,receipt_sha256,created_at) "
            f"VALUES ({app_id},{file2_id},'EXPENSE_RECEIPT',1,'R1',UNHEX(SHA2('receipt',256)),UTC_TIMESTAMP(6));"
        )
        require(harness.mysql(receipt_one, database="approval").returncode == 0, "receipt binding insert failed")
        harness.expect_error(
            f"INSERT INTO file_binding (application_id,file_id,purpose,expense_item_id,receipt_number,receipt_sha256,created_at) "
            f"VALUES ({app_id},{file3_id},'EXPENSE_RECEIPT',2,'R2',UNHEX(SHA2('receipt',256)),UTC_TIMESTAMP(6));",
            1062,
        )
        harness.expect_error(
            f"INSERT INTO file_binding (application_id,file_id,purpose,created_at) "
            f"VALUES ({app_id},{file3_id},'GENERAL',UTC_TIMESTAMP(6)); "
            f"INSERT INTO file_binding (application_id,file_id,purpose,created_at) "
            f"VALUES ({app_id},{file3_id},'GENERAL',UTC_TIMESTAMP(6));",
            1062,
        )

        harness.mysql(
            "INSERT INTO application (application_no,type,title,applicant_id,dept_id,"
            "applicant_name_snapshot,applicant_department_name_snapshot,status,amount,submitted_at,created_at,updated_at) "
            "VALUES ('CG-20261110-000000000000000001','PURCHASE','T',1,1,'N','D','IN_REVIEW',123.456,"
            "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            database="approval",
        )
        amount = harness.scalar(
            "SELECT amount FROM application WHERE application_no='CG-20261110-000000000000000001'"
        )
        require(amount == "123.46", f"DECIMAL(14,2) did not round to 123.46: {amount}")

        big_id = "9007199254740993"
        harness.mysql(
            f"INSERT INTO application (id,application_no,type,title,applicant_id,dept_id,"
            f"applicant_name_snapshot,applicant_department_name_snapshot,status,amount,submitted_at,created_at,updated_at) "
            f"VALUES ({big_id},'CG-20261110-BIGID000000000001','PURCHASE','T',1,1,'N','D','IN_REVIEW',1.00,"
            f"UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
            database="approval",
        )
        serialized = harness.scalar(f"SELECT CAST(id AS CHAR) FROM application WHERE id={big_id}")
        require(serialized == big_id, f"big ID string round-trip failed: {serialized}")

        return (
            "unique (department name, username, email), FK, ACTIVE department, rejection reason, "
            "submitted fields, CUSTOM template/fixed-type template, single config row, version>=1, "
            "draft field nulls, file binding uniqueness/receipt uniqueness, DECIMAL rounding 123.456→123.46, "
            "big ID 9007199254740993 round-trips as decimal string"
        )

    harness.check("FK/unique/check constraints on real MySQL", constraints)

    def production_separation() -> str:
        harness.mysql(
            "CREATE DATABASE approval_prod CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
            database=None,
        )
        harness.mysql(
            "GRANT ALL PRIVILEGES ON `approval_prod`.* TO 'approval_migrator'@'%' WITH GRANT OPTION;",
            database=None,
        )
        env = harness.app_env("approval_prod", "approval_migrator", harness.migration_password, demo=False)
        result = harness.run_profile("db,migrate", "approval_prod", env=env)
        require(result.returncode == 0, f"production database migration failed:\n{result.stdout[-6000:]}\n{result.stderr[-4000:]}")
        require(harness.scalar("SELECT COUNT(*) FROM user", database="approval_prod") == "0", "production DB has demo users")
        require(harness.scalar("SELECT COUNT(*) FROM inbox_state", database="approval_prod") == "0", "production DB has demo inbox rows")
        require(harness.scalar("SELECT COUNT(*) FROM department", database="approval_prod") == "3", "production departments")
        require(harness.scalar("SELECT COUNT(*) FROM role", database="approval_prod") == "5", "production roles")
        grants = harness.query("SHOW GRANTS FOR 'approval_app'@'%'")
        require(
            any("`approval_prod`.`audit_record`" in row[0] for row in grants),
            "V3 grant placeholder did not target the second database",
        )
        second = harness.run_profile("db,migrate", "approval_prod", env=env)
        require(second.returncode == 0 and "up to date" in (second.stdout + second.stderr).lower(), "production migrate was not idempotent")
        v2 = (MIGRATION_DIR / "V2__task02_base_seed.sql").read_text(encoding="utf-8")
        for _ in range(2):
            require(harness.mysql(v2, database="approval_prod").returncode == 0, "production V2 replay failed")
        require(harness.scalar("SELECT COUNT(*) FROM department", database="approval_prod") == "3", "production V2 duplicated departments")
        return "separate approval_prod DB migrated with 0 users/inbox, 3 departments, 5 roles; V3 grants target the second schema; no demo data"

    harness.check("production and demo databases are separate", production_separation)

    def restart_persistence() -> str:
        before = harness.artifact()["demo_user_state_sha256"]
        restart = docker(["restart", harness.container], timeout=120)
        require(restart.returncode == 0, f"container restart failed: {restart.stderr.strip()}")
        deadline = time.time() + 180
        while time.time() < deadline:
            if harness.mysql("SELECT 1").returncode == 0:
                break
            time.sleep(2)
        else:
            raise Failure("MySQL did not recover after restart")
        after = harness.artifact()["demo_user_state_sha256"]
        require(before == after, f"user state changed across restart: {before} -> {after}")
        require(harness.scalar("SELECT COUNT(*) FROM department") == "3", "departments changed across restart")
        require(harness.scalar("SELECT COUNT(*) FROM role") == "5", "roles changed across restart")
        require(harness.scalar("SELECT COUNT(*) FROM user") == "11", "users changed across restart")
        return "container restart preserved 3 departments, 5 roles and 11 users; demo state hash unchanged"

    harness.check("data survives MySQL restart", restart_persistence)


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify TASK-02 against a disposable MySQL 8.4 container")
    parser.add_argument("--skip-build", action="store_true", help="use the existing backend jar")
    parser.add_argument("--keep", action="store_true", help="keep the disposable container for inspection")
    parser.add_argument(
        "--json-out",
        type=Path,
        default=BACKEND / "evidence" / "task-02-mysql.json",
        help="evidence output path",
    )
    args = parser.parse_args()

    if docker(["version"], timeout=30).returncode != 0:
        print("Docker is required for the TASK-02 MySQL verification", file=sys.stderr)
        return 2

    java = find_java()
    harness = MysqlHarness(java, build=not args.skip_build, keep=args.keep)
    exit_code = 0
    try:
        verify_task02(harness)
        print("\nTASK-02 MySQL verification: PASS")
    except Exception as error:  # noqa: BLE001 - main converts every failure into evidence
        print(f"\nTASK-02 MySQL verification: FAIL\n{error}", file=sys.stderr)
        exit_code = 1
    finally:
        artifact = harness.artifact()
        artifact["verdict"] = "PASS" if exit_code == 0 else "FAIL"
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"evidence: {args.json_out}")
        harness.stop()
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
