"""Tests for specification tooling, not enterprise approval business behavior."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("verify_specs", ROOT / "scripts/verify_specs.py")
assert SPEC and SPEC.loader
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class SpecificationCheckerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Derive the approved IDs from the real manifest, but use independent synthetic files.
        self.data = json.loads((ROOT / checker.MANIFEST).read_text(encoding="utf-8"))
        self.data = copy.deepcopy(self.data)
        pdf_bytes = b"synthetic PDF fixture for specification tooling tests only\n"
        self.put(self.data["source"]["repositoryPdf"], pdf_bytes)
        self.data["source"]["sha256"] = hashlib.sha256(pdf_bytes).hexdigest()
        for doc in self.data["documents"]:
            self.put(doc, "# Fixture\n[入口](<README.md>)\n" if doc == "README.md" else "# Fixture\n")
        for task in self.data["tasks"]:
            for path in task["evidence"]:
                self.put(path, "# Synthetic specification evidence\n")
        self.put((checker.SPEC_DIR / "spec.md").as_posix(),
                 "\n".join(f"## {r['id']} Fixture\n" for r in self.data["requirements"]))
        self.put((checker.SPEC_DIR / "tasks.md").as_posix(),
                 "\n".join(f"## {t['id']} Fixture\n状态：`{t['status']}`\n" for t in self.data["tasks"]))
        self.put((checker.SPEC_DIR / "acceptance.md").as_posix(),
                 "\n".join(f"## {t['id']} Fixture\n状态：`{t['status']}`\n" for t in self.data["tests"]))

    def put(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(text, bytes):
            path.write_bytes(text)
        else:
            path.write_text(text, encoding="utf-8")
        return path

    def errors(self):
        return checker.validate(self.root, self.data)

    def assert_error(self, phrase):
        self.assertTrue(any(phrase in e for e in self.errors()), self.errors())

    def test_valid_baseline(self):
        self.assertEqual([], self.errors())

    def test_pdf_fingerprint_mismatch(self):
        self.put(self.data["source"]["repositoryPdf"], b"changed")
        self.assert_error("SHA-256 differs")

    def test_missing_pdf(self):
        self.data["source"]["repositoryPdf"] = "missing.pdf"
        self.assert_error("missing file")

    def test_external_source_is_compared(self):
        external = self.put("external.pdf", b"wrong PDF")
        self.assertTrue(any("provided PDF: SHA-256 differs" in e
                            for e in checker.validate(self.root, self.data, external)))

    def test_duplicate_test_ids(self):
        self.data["tests"].append(copy.deepcopy(self.data["tests"][0]))
        self.assert_error("duplicate ids")

    def test_missing_test(self):
        self.data["tests"].pop()
        self.assert_error("id coverage mismatch")

    def test_unknown_requirement_reference(self):
        self.data["tasks"][1]["requirements"] = ["REQ-99"]
        self.assert_error("unknown references")

    def test_dependency_cycle(self):
        self.data["tasks"][0]["dependsOn"] = ["TASK-15"]
        self.assert_error("dependency cycle")

    def test_active_task_requires_finished_dependencies(self):
        tasks = {task["id"]: task for task in self.data["tasks"]}
        active = tasks["TASK-04"]
        active["status"] = "in_progress"
        # Construct an unfinished prerequisite instead of relying on repository progress.
        tasks[active["dependsOn"][0]]["status"] = "planned"
        self.put((checker.SPEC_DIR / "tasks.md").as_posix(),
                 "\n".join(f"## {task['id']} Fixture\n状态：`{task['status']}`\n"
                           for task in self.data["tasks"]))
        self.assert_error("unfinished dependency")

    def test_independent_coding_boundary(self):
        self.data["tasks"][9]["owner"] = "assisted"
        self.assert_error("owner must be student_only")

    def test_pass_requires_evidence(self):
        self.data["tests"][0]["status"] = "PASS"
        self.assert_error("requires evidence")

    def test_not_run_cannot_claim_evidence(self):
        self.data["tests"][0]["evidence"] = ["README.md"]
        self.assert_error("NOT_RUN cannot claim")

    def test_missing_evidence_file(self):
        self.data["tasks"][0]["evidence"] = ["missing.md"]
        self.assert_error("missing file")

    def test_broken_markdown_link(self):
        self.put("README.md", "# Fixture\n[broken](<no such file.md>)\n")
        self.assert_error("missing file")

    def test_unicode_and_url_encoded_link(self):
        self.put("有 空格.md", "fixture")
        self.put("README.md", "[中文](<有%20空格.md>)\n")
        self.assertEqual([], self.errors())

    def test_link_escape_rejected(self):
        self.put("README.md", "[escape](<../outside.md>)\n")
        self.assert_error("path escapes repository")

    def test_evidence_escape_rejected(self):
        self.data["tasks"][0]["evidence"] = ["../outside.md"]
        self.assert_error("path escapes repository")

    def test_duplicate_heading(self):
        self.put((checker.SPEC_DIR / "spec.md").as_posix(), "## REQ-01 Duplicate\n" * 12)
        self.assert_error("heading coverage mismatch")

    def test_status_drift(self):
        self.data["tasks"][1]["status"] = "planned" if self.data["tasks"][1]["status"] != "planned" else "in_progress"
        self.assert_error("status differs from manifest")

    def test_incomplete_document_inventory(self):
        self.data["documents"].remove("README.md")
        self.assert_error("managed document coverage mismatch")

    def test_malformed_sections_do_not_crash(self):
        for name in ("requirements", "tests", "tasks", "documents"):
            with self.subTest(name=name):
                bad = copy.deepcopy(self.data)
                bad[name] = 42
                self.assertTrue(checker.validate(self.root, bad))
        self.assertTrue(checker.validate(self.root, []))

    def test_non_string_statuses_do_not_crash(self):
        for section in ("tasks", "tests"):
            for status in (None, [], {}, 42):
                with self.subTest(section=section, status=status):
                    bad = copy.deepcopy(self.data)
                    bad[section][0]["status"] = status
                    self.assertTrue(checker.validate(self.root, bad))

    def test_validation_evidence_links_are_checked(self):
        self.put((checker.SPEC_DIR / "validation.md").as_posix(),
                 "[broken](<missing-evidence.md>)\n")
        self.assert_error("missing file")

    def test_cli_reports_invalid_json(self):
        self.put(checker.MANIFEST.as_posix(), "{not-json")
        stream = io.StringIO()
        with redirect_stderr(stream):
            result = checker.main(["--root", str(self.root)])
        self.assertEqual(1, result)
        self.assertIn("Cannot load baseline", stream.getvalue())

    def test_cli_can_run_outside_repository(self):
        self.put(checker.MANIFEST.as_posix(), json.dumps(self.data))
        stream = io.StringIO()
        with redirect_stdout(stream):
            result = checker.main(["--root", str(self.root)])
        self.assertEqual(0, result)
        self.assertIn("no approval business tests were executed", stream.getvalue())

    def test_code_fences_do_not_create_link_errors(self):
        self.put("README.md", "# Fixture\n```text\n[example](<not-a-file.md>)\n```\n")
        self.assertEqual([], self.errors())


if __name__ == "__main__":
    unittest.main()
