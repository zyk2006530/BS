#!/usr/bin/env python3
"""Check the specification baseline; never execute or certify approval business tests."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

SPEC_DIR = Path("specs/001-enterprise-approval")
MANIFEST = SPEC_DIR / "manifest.json"
REQ_IDS = {f"REQ-{i:02d}" for i in range(1, 13)}
TEST_IDS = {f"T{i:02d}" for i in range(1, 23)}
TASK_IDS = {f"TASK-{i:02d}" for i in range(16)}
REQUIRED_DOCS = {
    "README.md", ".specify/memory/constitution.md",
    *(str(SPEC_DIR / name).replace("\\", "/") for name in (
        "README.md", "spec.md", "plan.md", "tasks.md", "acceptance.md", "gap-analysis.md",
        "validation.md", "engineering-validation.md"
    )),
}


def local_file(root: Path, value: object, label: str, errors: list[str]) -> Path | None:
    """Resolve a portable repository-relative file, rejecting escape and missing paths."""
    if not isinstance(value, str) or not value or "\\" in value:
        errors.append(f"{label}: expected a non-empty POSIX relative file path")
        return None
    path = Path(value)
    if path.is_absolute() or re.match(r"^[A-Za-z]:", value):
        errors.append(f"{label}: absolute path is not portable: {value}")
        return None
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        errors.append(f"{label}: path escapes repository: {value}")
        return None
    if not target.is_file():
        errors.append(f"{label}: missing file: {value}")
        return None
    return target


def records(data: dict, name: str, expected: set[str], errors: list[str]) -> dict[str, dict]:
    values = data.get(name)
    if not isinstance(values, list) or any(not isinstance(v, dict) for v in values):
        errors.append(f"{name}: expected a list of objects")
        return {}
    ids = [v.get("id") for v in values]
    if any(not isinstance(v, str) for v in ids):
        errors.append(f"{name}: every id must be a string")
        return {}
    duplicates = sorted(v for v, n in Counter(ids).items() if n > 1)
    if duplicates:
        errors.append(f"{name}: duplicate ids: {duplicates}")
    if set(ids) != expected:
        errors.append(f"{name}: id coverage mismatch; missing={sorted(expected - set(ids))}, "
                      f"extra={sorted(set(ids) - expected)}")
    return {v["id"]: v for v in values}


def refs(item: dict, key: str, allowed: set[str], errors: list[str], *, empty: bool = False) -> list[str]:
    values = item.get(key)
    label = f"{item.get('id', '?')}.{key}"
    if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
        errors.append(f"{label}: expected a list of strings")
        return []
    if (not empty and not values) or len(set(values)) != len(values):
        errors.append(f"{label}: empty or duplicate references")
    if set(values) - allowed:
        errors.append(f"{label}: unknown references: {sorted(set(values) - allowed)}")
    return values


def evidence(root: Path, item: dict, required: bool, errors: list[str]) -> None:
    values = item.get("evidence")
    if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
        errors.append(f"{item.get('id')}.evidence: expected a list of paths")
        return
    if required and not values:
        errors.append(f"{item.get('id')}: completed/executed/blocked status requires evidence")
    for value in values:
        local_file(root, value, f"{item.get('id')}.evidence", errors)


def headings(text: str, pattern: str) -> list[str]:
    return re.findall(r"^## (" + pattern + r")(?:\s|$)", text, flags=re.MULTILINE)


def check_links(root: Path, relative: str, text: str, errors: list[str]) -> None:
    # Our managed documents use inline Markdown links; ignore fenced code examples.
    text = re.sub(r"(?ms)^(`{3,}|~{3,})[^\n]*\n.*?^\1\s*$", "", text)
    for raw in re.findall(r"\[[^\]\n]*\]\(\s*(<[^>]+>|[^)\s]+)\s*\)", text):
        destination = raw[1:-1] if raw.startswith("<") else raw
        parsed = urlsplit(destination)
        if parsed.scheme in {"https", "http", "mailto"} or not parsed.path:
            continue
        if parsed.scheme or parsed.netloc:
            errors.append(f"{relative}: unsupported link: {destination}")
            continue
        target = (Path(relative).parent / unquote(parsed.path)).as_posix()
        local_file(root, target, f"{relative} link", errors)


def validate(root: Path, data: object, source_pdf: Path | None = None) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["manifest: expected an object"]
    if data.get("formatVersion") != 1 or data.get("stage") != "SPEC_BASELINE":
        errors.append("manifest: unsupported formatVersion or stage")
    source = data.get("source")
    if not isinstance(source, dict):
        errors.append("source: expected an object")
        source = {}
    expected_hash = source.get("sha256", "")
    if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
        errors.append("source.sha256: expected 64 lowercase hexadecimal characters")
    repo_pdf = local_file(root, source.get("repositoryPdf"), "source.repositoryPdf", errors)
    for label, pdf in (("repository PDF", repo_pdf), ("provided PDF", source_pdf)):
        if pdf is not None:
            if not pdf.is_file():
                errors.append(f"{label}: missing file: {pdf}")
            elif hashlib.sha256(pdf.read_bytes()).hexdigest() != expected_hash:
                errors.append(f"{label}: SHA-256 differs from approved design baseline")

    reqs = records(data, "requirements", REQ_IDS, errors)
    tests = records(data, "tests", TEST_IDS, errors)
    tasks = records(data, "tasks", TASK_IDS, errors)
    covered_tests: set[str] = set()
    for item in reqs.values():
        covered_tests.update(refs(item, "tests", TEST_IDS, errors))
    if covered_tests != TEST_IDS:
        errors.append("requirements: not all acceptance tests are mapped")
    implemented_reqs: set[str] = set()
    implemented_tests: set[str] = set()
    graph: dict[str, list[str]] = {}
    for key, item in tasks.items():
        req_refs = refs(item, "requirements", REQ_IDS, errors)
        test_refs = refs(item, "tests", TEST_IDS, errors)
        if key != "TASK-00":
            implemented_reqs.update(req_refs)
            implemented_tests.update(test_refs)
        graph[key] = refs(item, "dependsOn", set(tasks), errors, empty=True)
        status = item.get("status")
        if not isinstance(status, str) or status not in {"planned", "in_progress", "done", "blocked"}:
            errors.append(f"{key}: unknown task status")
            status = ""
        expected_owner = "student_only" if key in {"TASK-09", "TASK-10"} else "assisted"
        if item.get("owner") != expected_owner:
            errors.append(f"{key}: owner must be {expected_owner}; preserve independent coding boundary")
        evidence(root, item, status in {"done", "blocked"}, errors)
        if status in {"done", "in_progress"}:
            for dep in graph[key]:
                if tasks.get(dep, {}).get("status") != "done":
                    errors.append(f"{key}: unfinished dependency {dep}")
    if implemented_reqs != REQ_IDS or implemented_tests != TEST_IDS:
        errors.append("tasks: implementation coverage is incomplete (TASK-00 does not count)")
    visited: set[str] = set()
    active: set[str] = set()

    def visit(key: str) -> None:
        if key in active:
            errors.append(f"tasks: dependency cycle at {key}")
            return
        if key in visited:
            return
        active.add(key)
        for dep in graph.get(key, []):
            visit(dep)
        active.remove(key)
        visited.add(key)

    for key in graph:
        visit(key)
    for key, item in tests.items():
        status = item.get("status")
        if not isinstance(status, str) or status not in {"NOT_RUN", "PASS", "FAIL", "BLOCKED"}:
            errors.append(f"{key}: unknown acceptance status")
        evidence(root, item, item.get("status") != "NOT_RUN", errors)
        if item.get("status") == "NOT_RUN" and item.get("evidence"):
            errors.append(f"{key}: NOT_RUN cannot claim execution evidence")

    documents = data.get("documents")
    if not isinstance(documents, list) or any(not isinstance(v, str) for v in documents):
        errors.append("documents: expected a list of paths")
        documents = []
    if len(set(documents)) != len(documents) or set(documents) != REQUIRED_DOCS:
        errors.append("documents: managed document coverage mismatch")
    texts: dict[str, str] = {}
    for relative in documents:
        doc = local_file(root, relative, "documents", errors)
        if doc:
            try:
                texts[relative] = doc.read_text(encoding="utf-8")
                check_links(root, relative, texts[relative], errors)
            except (OSError, UnicodeError, ValueError) as exc:
                errors.append(f"{relative}: cannot read document: {exc}")
    for filename, pattern, expected in (
        ("spec.md", r"REQ-\d{2}", REQ_IDS),
        ("tasks.md", r"TASK-\d{2}", TASK_IDS),
        ("acceptance.md", r"T\d{2}", TEST_IDS),
    ):
        relative = (SPEC_DIR / filename).as_posix()
        text = texts.get(relative, "")
        found = headings(text, pattern)
        if set(found) != expected or len(found) != len(expected):
            errors.append(f"{relative}: heading coverage mismatch or duplicate headings")
        entries = tasks if filename == "tasks.md" else tests if filename == "acceptance.md" else {}
        for key, item in entries.items():
            match = re.search(r"(?ms)^## " + re.escape(key) + r"(?:\s|$).*?(?=^## |\Z)", text)
            status = item.get("status")
            if match and isinstance(status, str) and not re.search(r"`" + re.escape(status) + r"`", match.group()):
                errors.append(f"{relative}: {key} status differs from manifest")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--source-pdf", type=Path, help="Optionally compare the externally supplied PDF")
    args = parser.parse_args(argv)
    try:
        data = json.loads((args.root / MANIFEST).read_text(encoding="utf-8"))
        errors = validate(args.root, data, args.source_pdf)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors = [f"Cannot load baseline: {exc}"]
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("PASS: design fingerprint, 12 requirements, 16 tasks, 22 acceptance definitions, links and evidence.")
    print("Scope: specification consistency only; no approval business tests were executed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
