#!/usr/bin/env python3
"""Verify engineering V4 on disposable MySQL, not T11 or approval business APIs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile

from verify_task02_mysql import (
    BACKEND, JAR, MIGRATION_DIR, ROOT, MysqlHarness, docker, find_java,
    java_home_for, require, run,
)

V4 = MIGRATION_DIR / "V4__engineering_file_lifecycle.sql"


def build(java: Path) -> None:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home_for(java))
    wrapper = BACKEND / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    prefix = ["cmd.exe", "/c"] if os.name == "nt" else []
    result = run([*prefix, str(wrapper), "-f", "backend/pom.xml", "-q", "package", "-DskipTests"], env=env, timeout=900)
    require(result.returncode == 0, f"build failed:\n{result.stdout[-5000:]}\n{result.stderr[-3000:]}")


def verify(h: MysqlHarness) -> None:
    with zipfile.ZipFile(JAR) as jar:
        for migration in sorted(MIGRATION_DIR.glob("V*.sql")):
            require(jar.read("BOOT-INF/classes/db/migration/" + migration.name) == migration.read_bytes(),
                    f"stale jar migration: {migration.name}; rebuild without --skip-build")
    h.start()

    def migrate(db: str, target: str | None = None) -> None:
        h.migration_target = target
        env = h.app_env(db, "approval_migrator", h.migration_password, demo=False)
        result = h.run_profile("db,migrate", db, env=env)
        require(result.returncode == 0, f"{db} migrate failed:\n{result.stdout[-6000:]}\n{result.stderr[-3000:]}")

    def fresh() -> str:
        migrate("approval")
        history = h.query("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")
        require(history == [[str(i), "1"] for i in range(1, 5)], f"history: {history}")
        require(h.scalar("SELECT COUNT(*) FROM user") == "0", "fresh migration created users")
        require(h.scalar("SELECT COUNT(*) FROM file_object") == "0", "fresh migration created file fixtures")
        require(h.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='approval'") == "19", "table count")
        return "fresh V1-V4 succeeds; 18 business tables, no demo users/files"
    h.check("fresh V1-V4", fresh)

    db = "approval_upgrade"
    def upgrade() -> str:
        result = h.mysql(f"CREATE DATABASE {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
                         f"GRANT ALL PRIVILEGES ON `{db}`.* TO 'approval_migrator'@'%' WITH GRANT OPTION;", database=None)
        require(result.returncode == 0, result.stderr)
        migrate(db, "3")
        before_history = h.query("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank", database=db)
        require([r[0] for r in before_history] == ["1", "2", "3"], "V3 fixture history")
        fixture = """
INSERT INTO user (id,username,email,password_hash,name,requested_department_id,created_at,updated_at)
VALUES (1,'fixture_user','fixture@example.test','not-a-login-hash','Fixture',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO application (id,type,applicant_id,dept_id,created_at,updated_at)
VALUES (1,'PURCHASE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO application (id,application_no,type,title,applicant_id,dept_id,applicant_name_snapshot,
applicant_department_name_snapshot,status,amount,submitted_at,created_at,updated_at)
VALUES (2,'LEGACY-SUBMITTED-1','PURCHASE','Legacy submitted',1,1,'Fixture','Legacy department',
'IN_REVIEW',10,'2026-11-09 00:00:00.000000','2026-11-09 00:00:00.000000','2026-11-09 00:00:00.000000');
"""
        for i, status in enumerate(("TEMP", "BOUND", "FROZEN"), 1):
            fixture += ("INSERT INTO file_object (id,owner_id,storage_key,original_name,media_type,byte_size,sha256,status,created_at,updated_at) "
                        f"VALUES ({i},1,'legacy-{i}','legacy.pdf','application/pdf',10,UNHEX(SHA2('legacy-{i}',256)),'{status}',"
                        "'2026-11-09 00:00:00.000000','2026-11-09 00:00:00.000000');\n")
        # TEMP can also have a binding in the old schema; preserve all references.
        fixture += "INSERT INTO file_binding (application_id,file_id,purpose,created_at) VALUES (1,1,'GENERAL',UTC_TIMESTAMP(6)),(1,2,'GENERAL',UTC_TIMESTAMP(6)),(2,3,'GENERAL',UTC_TIMESTAMP(6));"
        require(h.mysql(fixture, database=db).returncode == 0, "cannot seed old metadata")
        select_metadata = "SELECT id,owner_id,storage_key,original_name,media_type,byte_size,HEX(sha256),created_at,updated_at FROM file_object ORDER BY id"
        before = h.query(select_metadata, database=db)
        bindings = h.query("SELECT * FROM file_binding ORDER BY id", database=db)
        applications = h.query("SELECT * FROM application ORDER BY id", database=db)
        grants = h.query("SHOW GRANTS FOR 'approval_app'@'%'", database=db)
        migrate(db)
        require(h.query(select_metadata, database=db) == before, "legacy metadata or timestamps changed")
        require(h.query("SELECT * FROM file_binding ORDER BY id", database=db) == bindings, "bindings changed")
        require(h.query("SELECT * FROM application ORDER BY id", database=db) == applications, "draft/submitted applications changed")
        require(h.query("SHOW GRANTS FOR 'approval_app'@'%'", database=db) == grants, "V4 changed runtime grants")
        require(h.query("SELECT status,upload_lease_until FROM file_object ORDER BY id", database=db) == [["READY", "NULL"]] * 3, "legacy state mapping")
        require(h.query("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3') ORDER BY installed_rank", database=db) == before_history, "V1-V3 history changed")
        return "V3 -> V4 preserves all metadata, timestamps, draft/submitted applications, 3 bindings, runtime grants and V1-V3 checksums; TEMP/BOUND/FROZEN map to READY"
    h.check("populated V3 upgrade", upgrade)

    def constraints() -> str:
        insert = ("INSERT INTO file_object (owner_id,storage_key,original_name,media_type,upload_lease_until,created_at,updated_at) "
                  "VALUES (1,'uploading','new.pdf','application/pdf',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));")
        require(h.mysql(insert, database=db, user="approval_app", password=h.app_password).returncode == 0, "business account cannot create UPLOADING metadata")
        require(h.query("SELECT status,byte_size,sha256 FROM file_object WHERE storage_key='uploading'", database=db) == [["UPLOADING", "NULL", "NULL"]], "unknown size/hash must be nullable during upload")
        for bad in ("TEMP", "BOUND", "FROZEN", "INVALID", "ready"):
            h.expect_error(f"UPDATE file_object SET status='{bad}' WHERE id=1", 3819, database=db)
        h.expect_error("UPDATE file_object SET upload_lease_until=NULL WHERE storage_key='uploading'", 3819, database=db)
        h.expect_error("UPDATE file_object SET status='READY',upload_lease_until=NULL WHERE storage_key='uploading'", 3819, database=db)
        h.expect_error("UPDATE file_object SET byte_size=0 WHERE id=1", 3819, database=db)
        # MySQL CHECK accepts UNKNOWN: test NULL size with a valid hash separately.
        h.expect_error("UPDATE file_object SET byte_size=NULL WHERE id=1", 3819, database=db)
        h.expect_error("UPDATE file_object SET byte_size=NULL,sha256=NULL WHERE id=1", 3819, database=db)
        h.expect_error("UPDATE file_object SET sha256=NULL WHERE id=1", 3819, database=db)
        h.expect_error("UPDATE file_object SET status=NULL WHERE id=1", 1048, database=db)
        h.expect_error("UPDATE file_object SET upload_lease_until=UTC_TIMESTAMP(6) WHERE id=1", 3819, database=db)
        sql = ("UPDATE file_object SET status='READY',byte_size=12,sha256=UNHEX(SHA2('new',256)),upload_lease_until=NULL "
               "WHERE storage_key='uploading' AND status='UPLOADING' AND upload_lease_until>UTC_TIMESTAMP(6); SELECT ROW_COUNT();")
        require(h.scalar(sql, database=db, user="approval_app", password=h.app_password) == "1", "READY transition failed")
        for state in ("DELETING", "DELETED"):
            require(h.mysql(f"UPDATE file_object SET status='{state}' WHERE storage_key='uploading'", database=db,
                            user="approval_app", password=h.app_password).returncode == 0, f"state {state} refused")
        indexes = h.query("SHOW INDEX FROM file_object", database=db)
        require({"idx_file_object_upload_lease", "idx_file_object_cleanup_age"} <= {r[2] for r in indexes}, "cleanup indexes absent")
        h.expect_error("UPDATE audit_record SET action='TAMPER'", 1142, database=db, user="approval_app", password=h.app_password)
        h.expect_error("DELETE FROM audit_record", 1142, database=db, user="approval_app", password=h.app_password)
        h.expect_error("SELECT * FROM flyway_schema_history", 1142, database=db, user="approval_app", password=h.app_password)
        h.expect_error("CREATE TABLE forbidden (id INT)", 1142, database=db, user="approval_app", password=h.app_password)
        h.expect_error("ALTER TABLE file_object ADD COLUMN forbidden INT", 1142, database=db, user="approval_app", password=h.app_password)
        h.expect_error("DROP TABLE file_object", 1142, database=db, user="approval_app", password=h.app_password)
        return "four states; READY NULL-size/hash and zero-size rejected; lease rules and indexes; app file DML works, DDL/history access/audit UPDATE+DELETE denied (SQL only)"
    h.check("new metadata constraints and runtime grants", constraints)

    def repeat() -> str:
        before = h.query("SELECT * FROM file_object ORDER BY id", database=db)
        history = h.query("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank", database=db)
        migrate(db)
        require(h.query("SELECT * FROM file_object ORDER BY id", database=db) == before, "repeat migration changed objects")
        require(h.query("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank", database=db) == history, "repeat changed history")
        return "second Flyway migrate leaves data and all checksums unchanged"
    h.check("repeated V4 migrate", repeat)

    def checksum_validation() -> str:
        original = h.scalar("SELECT checksum FROM flyway_schema_history WHERE version='4'", database=db)
        require(original.lstrip("-").isdigit(), "V4 checksum is absent")
        require(h.mysql("UPDATE flyway_schema_history SET checksum=0 WHERE version='4'", database=db).returncode == 0,
                "cannot prepare disposable checksum tamper")
        try:
            h.migration_target = None
            env = h.app_env(db, "approval_migrator", h.migration_password, demo=False)
            result = h.run_profile("db,migrate", db, env=env)
            require(result.returncode != 0, "Flyway accepted a tampered V4 checksum")
            require("checksum mismatch" in (result.stdout + result.stderr).lower(),
                    f"migration failed for a reason other than checksum validation: {result.stdout[-3000:]} {result.stderr[-2000:]}")
        finally:
            require(h.mysql(f"UPDATE flyway_schema_history SET checksum={original} WHERE version='4'", database=db).returncode == 0,
                    "cannot restore disposable checksum fixture")
        migrate(db)
        require(h.scalar("SELECT checksum FROM flyway_schema_history WHERE version='4'", database=db) == original,
                "restored V4 checksum changed")
        return "tampered V4 history checksum rejected by Flyway; original restored without repair and migrate succeeds"
    h.check("Flyway checksum tamper rejection", checksum_validation)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--json-out", type=Path, default=BACKEND / "evidence/engineering-v4-mysql.json")
    args = parser.parse_args()
    if docker(["version"], timeout=30).returncode != 0:
        print("Docker is required for engineering MySQL verification", file=sys.stderr)
        return 2
    h = MysqlHarness(find_java(), build=False, keep=args.keep, migration_target=None)
    exit_code = 0
    try:
        if not args.skip_build:
            build(h.java)
        verify(h)
    except Exception as error:
        print(f"Engineering migration verification FAIL: {error}", file=sys.stderr)
        exit_code = 1
    finally:
        artifact = h.artifact()
        artifact.update({"task": "ENGINEERING_BASELINE_V4", "verdict": "PASS" if exit_code == 0 else "FAIL",
                         "v4_sha256": hashlib.sha256(V4.read_bytes()).hexdigest(),
                         "migration_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                              for path in sorted(MIGRATION_DIR.glob("V*.sql"))},
                         "scope": "database migration only; T01-T22 remain NOT_RUN"})
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        h.stop()
        print(f"evidence: {args.json_out}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
