"""Static migration-contract guards. Real MySQL evidence is generated separately."""
from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import patch
import unittest

from scripts.verify_task02_mysql import MysqlHarness

ROOT = Path(__file__).resolve().parents[1]


class EngineeringBaselineTests(unittest.TestCase):
    def test_legacy_verifier_pins_v3(self):
        h = MysqlHarness(Path("java.exe"), build=False, keep=False)
        with patch.dict(os.environ, {"SPRING_FLYWAY_TARGET": "99"}):
            env = h.app_env("approval", "test", "test-only", demo=False)
        self.assertEqual("3", env["SPRING_FLYWAY_TARGET"])

    def test_latest_verifier_does_not_inherit_a_stale_target(self):
        h = MysqlHarness(Path("java.exe"), build=False, keep=False, migration_target=None)
        with patch.dict(os.environ, {"SPRING_FLYWAY_TARGET": "3"}):
            env = h.app_env("approval", "test", "test-only", demo=False)
        self.assertNotIn("SPRING_FLYWAY_TARGET", env)

    def test_incremental_migration_preserves_relationships(self):
        sql = (ROOT / "backend/src/main/resources/db/migration/V4__engineering_file_lifecycle.sql").read_text(encoding="utf-8")
        self.assertIn("WHERE `status` IN ('TEMP', 'BOUND', 'FROZEN')", sql)
        self.assertIn("SET `status` = 'READY'", sql)
        self.assertNotIn("DELETE FROM", sql.upper())
        self.assertNotIn("DROP TABLE", sql.upper())
        self.assertNotIn("UPDATE `file_binding`", sql)
        self.assertNotIn("UPDATE `application`", sql)

    def test_in_flight_metadata_and_ready_validation_are_separate(self):
        sql = (ROOT / "backend/src/main/resources/db/migration/V4__engineering_file_lifecycle.sql").read_text(encoding="utf-8")
        self.assertIn("MODIFY COLUMN `byte_size` BIGINT UNSIGNED NULL", sql)
        self.assertIn("MODIFY COLUMN `sha256` BINARY(32) NULL", sql)
        self.assertIn("`status` <> 'READY' OR (`byte_size` IS NOT NULL AND `byte_size` > 0 AND `sha256` IS NOT NULL)", sql)
        self.assertIn("`status` = 'UPLOADING' AND `upload_lease_until` IS NOT NULL", sql)
        self.assertIn("'UPLOADING', 'READY', 'DELETING', 'DELETED'", sql)

    def test_real_verifier_covers_mysql_unknown_and_submitted_references(self):
        script = (ROOT / "scripts/verify_engineering_mysql.py").read_text(encoding="utf-8")
        self.assertIn('"UPDATE file_object SET byte_size=NULL WHERE id=1", 3819', script)
        self.assertIn('"UPDATE file_object SET byte_size=NULL,sha256=NULL WHERE id=1", 3819', script)
        self.assertIn('"UPDATE file_object SET sha256=NULL WHERE id=1", 3819', script)
        self.assertIn("'IN_REVIEW',10", script)
        self.assertIn('"draft/submitted applications changed"', script)
        self.assertIn('"V4 changed runtime grants"', script)

    def test_real_verifier_checks_checksum_and_runtime_denials(self):
        script = (ROOT / "scripts/verify_engineering_mysql.py").read_text(encoding="utf-8")
        self.assertIn('jar.read("BOOT-INF/classes/db/migration/" + migration.name) == migration.read_bytes()', script)
        self.assertIn('"checksum mismatch" in (result.stdout + result.stderr).lower()', script)
        self.assertIn('"migration_sha256"', script)
        for statement in ("DELETE FROM audit_record", "SELECT * FROM flyway_schema_history",
                          "ALTER TABLE file_object ADD COLUMN forbidden INT", "DROP TABLE file_object"):
            self.assertIn(f'"{statement}", 1142', script)

    def test_real_verifier_has_distinct_evidence_and_scope(self):
        script = (ROOT / "scripts/verify_engineering_mysql.py").read_text(encoding="utf-8")
        self.assertIn("engineering-v4-mysql.json", script)
        self.assertNotIn('default=BACKEND / "evidence/task-02-mysql.json"', script)
        self.assertIn("T01-T22 remain NOT_RUN", script)


if __name__ == "__main__":
    unittest.main()
