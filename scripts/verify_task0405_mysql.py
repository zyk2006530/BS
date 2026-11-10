#!/usr/bin/env python3
"""TASK-04/05 real-jar/MySQL black-box verification; never builds or edits production state.

Credentials remain in memory. SQL bootstraps isolated administrators/employees and fault fixtures;
management functionality itself is always exercised through HTTP. Full T acceptance is not promoted.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid
import zipfile

from verify_task02_mysql import BACKEND, MIGRATION_DIR, ROOT, IMAGE, MysqlHarness, docker, find_java, require
from verify_task03_mysql import Application, expect, parallel_calls


def key() -> str:
    return str(uuid.uuid4())


def verify(h: MysqlHarness, app: Application) -> None:
    app.prepare_jar()
    with zipfile.ZipFile(app.runtime_jar) as archive:
        for migration in sorted(MIGRATION_DIR.glob("V*.sql")):
            require(archive.read("BOOT-INF/classes/db/migration/" + migration.name) == migration.read_bytes(),
                    f"stale migration {migration.name}; main must build serially")
    h.start()
    state: dict = {"password": secrets.token_urlsafe(24)}

    def sql(statement: str) -> None:
        require(h.mysql(statement).returncode == 0, "isolated SQL fixture failed; raw statement/output withheld")

    def reset_limits() -> None:
        sql("DELETE FROM rate_limit_bucket")

    def utc_matches(value: str, table: str, column: str, target: str) -> None:
        require(isinstance(value, str) and value.endswith("Z"), "HTTP timestamp must be explicit UTC-Z")
        encoded = h.scalar(f"SELECT DATE_FORMAT({column},'%Y-%m-%dT%H:%i:%s.%f') FROM {table} WHERE id={target}")
        expected = datetime.fromisoformat(encoded).replace(tzinfo=timezone.utc)
        actual = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(abs((actual - expected).total_seconds()) < 0.002, "DATETIME serialized in non-UTC timezone")
        sql_now = h.scalar("SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f')")
        require(abs((actual - datetime.fromisoformat(sql_now).replace(tzinfo=timezone.utc)).total_seconds()) < 15, "HTTP timestamp not within15s of SQL UTC")

    def register(username: str) -> str:
        reset_limits()
        reply = expect(app.request("POST", "/native-auth/register", {
            "username": username, "name": "Isolated Fixture", "email": username + "@example.test",
            "password": state["password"], "requestedDepartmentId": "1"}), 201)
        return reply.data["userId"]

    def login(username: str, password: str | None = None) -> str:
        reset_limits()
        return expect(app.request("POST", "/native-auth/login", {
            "username": username, "password": password or state["password"]}), 200).data["token"]

    def req(method: str, path: str, body=None, *, actor="admin", request_key=None):
        extra = [("Idempotency-Key", request_key)] if request_key else None
        return app.request(method, path, body, token=state[actor + "_token"], extra=extra)

    def config(actor="admin") -> str:
        return expect(req("GET", "/admin/config", actor=actor), 200).data["configVersion"]

    def uv(user_id: str) -> str:
        return h.scalar(f"SELECT CAST(row_version AS CHAR) FROM user WHERE id={user_id}")

    def dv(dept_id: str) -> str:
        return h.scalar(f"SELECT CAST(row_version AS CHAR) FROM department WHERE id={dept_id}")

    def management(method: str, path: str, body: dict, version: str | None = None, actor="admin", status=200):
        data = dict(body, expectedConfigVersion=config(actor), reason="isolated verification")
        if version is not None:
            data["expectedVersion"] = version
        return expect(req(method, path, data, actor=actor), status).data

    def create(actor="employee", request_key=None, kind="PURCHASE"):
        return expect(req("POST", "/applications", {"type": kind}, actor=actor,
                          request_key=request_key or key()), 201).data

    def application_id(data: dict) -> str:
        result = data.get("id", data.get("applicationId"))
        require(isinstance(result, str) and result.isdigit(), "application ID must be decimal string")
        return result

    def setup() -> str:
        result = subprocess.run([str(h.java), "-jar", str(app.runtime_jar), "--spring.profiles.active=db,migrate",
                                 "--spring.main.web-application-type=none"], cwd=ROOT,
                                env=h.app_env("approval", "approval_migrator", h.migration_password, demo=False),
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
        require(result.returncode == 0, "isolated migration failed; raw logs withheld")
        require(h.scalar("SELECT COUNT(*) FROM user") == "0", "database must start empty")
        app.start()
        for name in ("admin", "admin2", "employee", "outsider"):
            state[name + "_id"] = register("fixture_" + name)
            sql(f"UPDATE user SET status='ACTIVE',department_id={'2' if name == 'outsider' else '1'} WHERE id={state[name + '_id']}")
            role = "SYS_ADMIN" if name.startswith("admin") else "EMPLOYEE"
            sql(f"INSERT INTO user_role(user_id,role_id,created_at) SELECT {state[name + '_id']},id,UTC_TIMESTAMP(6) FROM role WHERE code='{role}'")
            state[name + "_token"] = login("fixture_" + name)
        expect(req("GET", "/admin/config", actor="employee"), 403)
        expect(req("GET", "/admin/config"), 200)
        expect(app.request("GET", "/admin/config"), 401)
        return "fresh migration; only four initial users/roles bootstrapped in SQL; real app uses least-privilege runtime account"
    h.check("isolated startup and bootstrap", setup)

    def confirmations() -> str:
        pending = register("fixture_pending")
        rejected = register("fixture_rejected")
        before = int(h.scalar("SELECT COUNT(*) FROM audit_record"))
        management("POST", f"/admin/users/{pending}/confirm", {"departmentId": "1"}, uv(pending))
        require(h.scalar(f"SELECT CONCAT(status,':',department_id) FROM user WHERE id={pending}") == "ACTIVE:1", "confirm did not activate chosen department")
        require(h.scalar(f"SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE user_id={pending} AND r.code='EMPLOYEE'") == "1", "confirm must grant employee")
        management("POST", f"/admin/users/{rejected}/reject", {}, uv(rejected))
        require(h.scalar(f"SELECT status FROM user WHERE id={rejected}") == "REJECTED", "reject did not persist")
        require(int(h.scalar("SELECT COUNT(*) FROM audit_record")) == before + 2, "confirm/reject audit not atomic")
        state["confirmed_id"], state["rejected_id"] = pending, rejected
        state["confirmed_token"] = login("fixture_pending")
        stale = {"departmentId": "1", "reason": "isolated verification", "expectedConfigVersion": "1", "expectedVersion": uv(pending)}
        expect(req("POST", f"/admin/users/{pending}/confirm", stale), 409)
        expect(req("POST", f"/admin/users/{pending}/confirm", stale, actor="employee"), 403)
        users = expect(req("GET", "/admin/users?status=REJECTED&page=1&pageSize=1"), 200).data
        require(users["total"] == "1" and len(users["items"]) == 1, "management pagination/filter count mismatch")
        audits = expect(req("GET", "/admin/audit-records?page=1&pageSize=100"), 200).data
        require(int(audits["total"]) == before + 2, "audit list count mismatch")
        newest_audit = audits["items"][0]
        utc_matches(newest_audit["createdAt"], "audit_record", "created_at", newest_audit["auditId"])
        serialized = json.dumps(audits)
        require("password_hash" not in serialized and state["password"] not in serialized, "audit leaked credentials")
        return "confirm/reject through management API persist status/department/employee role, append readable credential-free audits, filter pagination and enforce admin/stale versions"
    try:
        h.check("audited confirmation and rejection", confirmations)
    except Exception:
        diagnostics = [
            ("user activation", "UPDATE user SET status='ACTIVE',department_id=1,rejection_reason=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=5"),
            ("employee role", "INSERT INTO user_role(user_id,role_id,created_at) SELECT 5,id,UTC_TIMESTAMP(6) FROM role WHERE code='EMPLOYEE' ON DUPLICATE KEY UPDATE user_id=user_id"),
            ("audit append", "INSERT INTO audit_record(actor_id,target_type,target_id,action,before_values,after_values,reason,request_id,created_at) VALUES(1,'USER',5,'USER_CONFIRMED',JSON_OBJECT('status','PENDING'),JSON_OBJECT('status','ACTIVE'),'isolated verification','00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(6))"),
            ("config increment", "UPDATE system_config SET row_version=row_version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=1"),
        ]
        import re
        for label, statement in diagnostics:
            result = h.mysql("START TRANSACTION; " + statement + "; ROLLBACK;", user="approval_app", password=h.app_password)
            match = re.search(r"ERROR (\d+) \(([^)]+)\)", result.stderr)
            print("confirm SQL diagnostic: " + label + (" PASS" if result.returncode == 0 else " FAIL code=" + (match.group(1) if match else "unknown") + " SQLSTATE=" + (match.group(2) if match else "unknown")))
        raise

    def departments_roles() -> str:
        result = management("POST", "/admin/departments", {"name": "Isolated Unit", "enabled": True, "managerUserId": None}, status=201)
        dept = result.get("targetId", result.get("id", result.get("departmentId")))
        require(isinstance(dept, str), "department ID must be string")
        state["department_id"] = dept
        management("PATCH", f"/admin/departments/{dept}", {"name": "Renamed Unit", "enabled": True, "managerUserId": None}, dv(dept))
        require(h.scalar(f"SELECT name FROM department WHERE id={dept}") == "Renamed Unit", "department rename missing")
        management("PUT", f"/admin/users/{state['confirmed_id']}/roles", {"roles": ["EMPLOYEE", "DEPT_HEAD"]}, uv(state["confirmed_id"]), status=409)
        management("PATCH", f"/admin/departments/{dept}", {"managerUserId": state["confirmed_id"]}, dv(dept), status=409)
        management("PATCH", f"/admin/users/{state['confirmed_id']}", {"departmentId": dept}, uv(state["confirmed_id"]))
        management("PATCH", f"/admin/departments/{dept}", {"managerUserId": state["confirmed_id"]}, dv(dept))
        management("PATCH", f"/admin/users/{state['confirmed_id']}", {"status": "DISABLED"}, uv(state["confirmed_id"]), status=409)
        management("PATCH", f"/admin/departments/{dept}", {"clearManager": True}, dv(dept))
        management("PUT", f"/admin/users/{state['admin2_id']}/roles", {"roles": ["EMPLOYEE"]}, uv(state["admin2_id"]))
        management("PUT", f"/admin/users/{state['admin_id']}/roles", {"roles": ["EMPLOYEE"]}, uv(state["admin_id"]), status=409)
        management("PUT", f"/admin/users/{state['admin2_id']}/roles", {"roles": ["SYS_ADMIN"]}, uv(state["admin2_id"]))
        return "department creation/rename/manager eligibility and responsibility protection; role diff audit and last-enabled-admin guard"
    h.check("departments roles and last administrator", departments_roles)

    def config_race() -> str:
        before = int(config())
        ver = dv(state["department_id"])
        body = {"name": "Concurrent Unit", "reason": "isolated verification", "expectedConfigVersion": str(before), "expectedVersion": ver}
        replies = parallel_calls(2, lambda index: req("PATCH", f"/admin/departments/{state['department_id']}", body, actor="admin" if index == 0 else "admin2"))
        require(Counter(r.status for r in replies) == Counter({200: 1, 409: 1}), "configuration race must yield exactly one success and one conflict")
        require(int(config()) == before + 1, "config race incremented more than once")
        return "two administrators using same object/config versions serialize to one200 and one409, exactly one configuration increment"
    h.check("configuration version race", config_race)

    def draft_protocol() -> str:
        sql("ALTER TABLE application AUTO_INCREMENT=9007199254740993")
        request_key = key()
        replies = parallel_calls(2, lambda _: req("POST", "/applications", {"type": "PURCHASE"}, actor="employee", request_key=request_key))
        require(all(r.status == 201 for r in replies), "same-key concurrent create must replay original creation status")
        ids = [application_id(reply.data) for reply in replies]
        require(ids[0] == ids[1] and int(ids[0]) >= 9007199254740993, "same-key create duplicated or rounded big ID")
        target = ids[0]
        state["draft_id"] = target
        expect(req("POST", "/applications", {"type": "TRAVEL"}, actor="employee", request_key=request_key), 409)
        expect(req("GET", f"/applications/{target}", actor="outsider"), 404)
        expect(req("GET", f"/applications/{target}"), 404)
        body = {"expectedVersion": "1", "title": "Literal_%_Keyword", "remark": "", "payload": {"items": [{"clientKey": str(uuid.uuid4()), "name": "Fixture"}]}}
        changed = expect(req("PUT", f"/applications/{target}/draft", body, actor="employee", request_key=key()), 200).data
        require(changed["rowVersion"] == "2", "save must increment version")
        utc_matches(changed["updatedAt"], "application", "updated_at", target)
        expect(req("PUT", f"/applications/{target}/draft", body, actor="employee", request_key=key()), 409)
        bad = dict(body, expectedVersion="2", payload={"untrusted": "field"})
        expect(req("PUT", f"/applications/{target}/draft", bad, actor="employee", request_key=key()), 400)
        bad = dict(body, expectedVersion="2", payload={"items": [{"clientKey": "duplicate"}, {"clientKey": "duplicate"}]})
        expect(req("PUT", f"/applications/{target}/draft", bad, actor="employee", request_key=key()), 400)
        other = application_id(create())
        repeated_key = key()
        good = dict(body, expectedVersion="2")
        expect(req("PUT", f"/applications/{target}/draft", good, actor="employee", request_key=repeated_key), 200)
        expect(req("PUT", f"/applications/{other}/draft", dict(body, expectedVersion="1"), actor="employee", request_key=repeated_key), 409)
        page = expect(req("GET", "/applications?view=mine&page=1&pageSize=100", actor="employee"), 200).data
        require(int(page["total"]) == 2, "owner filtered count mismatch")
        filtered = expect(req("GET", "/applications?view=mine&keyword=%25_%25&pageSize=1", actor="employee"), 200).data
        require(filtered["total"] == "0", "LIKE wildcard characters were not treated as literal text")
        filtered = expect(req("GET", "/applications?view=mine&keyword=Literal_%25_&pageSize=1", actor="employee"), 200).data
        require(filtered["total"] == "1" and len(filtered["items"]) == 1, "literal keyword filter count before pagination mismatch")
        expect(req("GET", "/applications?page=0", actor="employee"), 400)
        expect(req("GET", "/applications?submittedUntil=2030-01-01T00:00:00Z", actor="employee"), 400)
        filtered = expect(req("GET", "/applications?submittedFrom=2000-01-01T00:00:00Z&submittedTo=2030-01-01T00:00:00Z", actor="employee"), 200).data
        require(filtered["total"] == "0", "unsubmitted drafts leaked into submitted time range")
        invalid_money = dict(body, expectedVersion="3", payload={"items": [{"clientKey": "money-row", "unitPrice": "1.2"}]})
        expect(req("PUT", f"/applications/{target}/draft", invalid_money, actor="employee", request_key=key()), 400)
        expect(req("POST", "/applications", {"type": "PURCHASE"}, actor="employee"), 400)
        expect(req("POST", "/applications", {"type": "PURCHASE"}, actor="employee", request_key="not-a-uuid"), 400)
        expect(req("POST", "/applications", {"type": "CUSTOM"}, actor="employee", request_key=key()), 503)
        page = expect(req("GET", "/applications?view=mine", actor="outsider"), 200).data
        require(int(page["total"]) == 0, "unauthorized count leakage")
        state["other_draft_id"] = other
        return "same-key concurrent creation replays one big-string ID; fingerprints isolate body/target; versions, unknown payload, client keys and owner-only counts enforced"
    h.check("draft idempotency versions and visibility", draft_protocol)

    def fixed_payloads() -> str:
        travel = application_id(create(kind="TRAVEL"))
        body = {"expectedVersion": "1", "title": "Travel UTC fixture", "remark": "", "payload": {
            "origin": "Hangzhou", "destination": "Shanghai", "startAt": "2026-12-01T01:00:00Z",
            "endAt": "2026-12-02T10:30:00Z", "budget": "100.00"}}
        saved = expect(req("PUT", f"/applications/{travel}/draft", body, actor="employee", request_key=key()), 200).data
        require(saved["payload"]["startAt"] == body["payload"]["startAt"] and saved["rowVersion"] == "2", "valid UTC travel time was not preserved")
        for invalid in ("2026-12-01T01:00:00+00:00", "not-a-time", "2026-02-30T01:00:00Z"):
            broken = dict(body, expectedVersion="2", payload=dict(body["payload"], startAt=invalid))
            expect(req("PUT", f"/applications/{travel}/draft", broken, actor="employee", request_key=key()), 400)
        purchase = state["draft_id"]
        broken = {"expectedVersion": "3", "title": "Invalid date", "remark": "", "payload": {"expectedDate": "2026-02-30"}}
        expect(req("PUT", f"/applications/{purchase}/draft", broken, actor="employee", request_key=key()), 400)
        return "valid TRAVEL explicit UTC-Z payload survives save; offset/non-time/impossible-date timestamps and impossible purchase dates are rejected400"
    h.check("fixed draft dates and UTC travel payload", fixed_payloads)

    def grants() -> str:
        # Submitted SQL fixtures exercise the generic read permission seam, not workflow execution.
        owner = state["employee_id"]
        sql(f"INSERT INTO application(id,application_no,type,title,applicant_id,dept_id,applicant_name_snapshot,applicant_department_name_snapshot,status,amount,submitted_at,created_at,updated_at) VALUES (9007199254741999,'FIXTURE-READ','PURCHASE','Permission Fixture',{owner},1,'Fixture','Unit','APPROVED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
        path = "/applications/9007199254741999"
        expect(req("GET", path), 404)
        expect(req("GET", path, actor="outsider"), 404)
        management("PUT", f"/admin/users/{state['outsider_id']}/roles", {"roles": ["EMPLOYEE", "FINANCE_APPROVER"]}, uv(state["outsider_id"]))
        expect(req("GET", path, actor="outsider"), 404)
        management("PATCH", f"/admin/users/{state['confirmed_id']}", {"departmentId": "1"}, uv(state["confirmed_id"]))
        management("PATCH", "/admin/departments/1", {"managerUserId": state["confirmed_id"]}, dv("1"))
        submitted = expect(req("GET", path, actor="confirmed"), 200).data
        utc_matches(submitted["submittedAt"], "application", "submitted_at", "9007199254741999")
        expect(req("GET", f"/applications/{state['draft_id']}", actor="confirmed"), 404)
        management("PATCH", "/admin/departments/1", {"clearManager": True}, dv("1"))
        expect(req("GET", path, actor="confirmed"), 404)
        now = datetime.now(timezone.utc)
        def grant(ability, department="1", start=None, end=None, scope="DEPARTMENT"):
            result = management("POST", "/admin/grants", {"userId": state["outsider_id"], "ability": ability,
                "scope": scope, "departmentId": department, "validFrom": (start or now - timedelta(minutes=1)).isoformat().replace('+00:00','Z'),
                "validUntil": (end or now + timedelta(hours=1)).isoformat().replace('+00:00','Z'), "enterpriseConfirmed": scope == "ENTERPRISE"}, status=201)
            return result.get("id", result.get("grantId"))
        grant("STATISTICS")
        expect(req("GET", path, actor="outsider"), 404)
        grant("READ_BODY", department="2")
        expect(req("GET", path, actor="outsider"), 404)
        expired = grant("READ_BODY")
        # Time-travel fixture only: grant itself was created through the management API.
        sql(f"UPDATE data_grant SET valid_from=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),valid_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id={expired}")
        expect(req("GET", path, actor="outsider"), 404)
        grant_id = grant("READ_BODY")
        expect(req("GET", path, actor="outsider"), 200)
        expect(req("GET", f"/applications/{state['draft_id']}", actor="outsider"), 404)
        management("POST", f"/admin/grants/{grant_id}/revoke", {})
        expect(req("GET", path, actor="outsider"), 404)
        return "generic submitted-fixture body reads separate SYS_ADMIN/STATISTICS from READ_BODY; scope, expiry and revocation filter reads; drafts remain private"
    h.check("grant scope expiry and body separation", grants)

    def reset_disable() -> str:
        target = state["confirmed_id"]
        temporary = secrets.token_urlsafe(24)
        old = state["confirmed_token"]
        management("POST", f"/admin/users/{target}/reset-password", {"temporaryPassword": temporary}, uv(target))
        expect(app.request("GET", "/auth/me", token=old), 401)
        changed = login("fixture_pending", temporary)
        expect(app.request("GET", "/applications", token=changed), 403)
        expect(app.request("POST", "/profile/password", {"oldPassword": temporary, "newPassword": state["password"]}, token=changed), 204)
        valid = login("fixture_pending")
        management("PATCH", f"/admin/users/{target}", {"status": "DISABLED"}, uv(target))
        expect(app.request("GET", "/auth/me", token=valid), 401)
        return "management reset revokes previous sessions and forces password change; API disable revokes active sessions"
    h.check("administrative password reset and disable", reset_disable)

    def rollback_faults() -> str:
        before_config = config()
        before_name = h.scalar(f"SELECT name FROM department WHERE id={state['department_id']}")
        sql("CREATE TRIGGER task0405_audit_fault BEFORE INSERT ON audit_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated audit failure'")
        try:
            reply = req("PATCH", f"/admin/departments/{state['department_id']}", {"name": "Must Rollback", "reason": "isolated verification", "expectedConfigVersion": before_config, "expectedVersion": dv(state["department_id"])})
            expect(reply, 503)
            require(reply.data["retryable"] is True, "audit fault must be retryable503")
            require(config() == before_config and h.scalar(f"SELECT name FROM department WHERE id={state['department_id']}") == before_name, "audit failure did not rollback config/object")
        finally:
            sql("DROP TRIGGER task0405_audit_fault")
        count = h.scalar("SELECT COUNT(*) FROM application")
        request_key = key()
        sql("CREATE TRIGGER task0405_request_fault BEFORE INSERT ON request_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated request failure'")
        try:
            expect(req("POST", "/applications", {"type": "TRAVEL"}, actor="employee", request_key=request_key), 503)
            require(h.scalar("SELECT COUNT(*) FROM application") == count, "request-result failure leaked draft")
        finally:
            sql("DROP TRIGGER task0405_request_fault")
        expect(req("POST", "/applications", {"type": "TRAVEL"}, actor="employee", request_key=request_key), 201)
        return "audit failure rolls back management object/config; success-result insert failure rolls back draft and same request key recovers"
    h.check("audit and idempotency-result atomic rollback", rollback_faults)

    def delete_shared() -> str:
        target, other = state["draft_id"], state["other_draft_id"]
        owner = state["employee_id"]
        sql(f"INSERT INTO file_object(id,owner_id,storage_key,original_name,media_type,byte_size,sha256,status,created_at,updated_at) VALUES(9007199254742999,{owner},'fixture/private','fixture.pdf','application/pdf',1,UNHEX(SHA2('fixture',256)),'READY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
        sql(f"INSERT INTO file_binding(application_id,file_id,purpose,created_at) VALUES ({target},9007199254742999,'GENERAL',UTC_TIMESTAMP(6)),({other},9007199254742999,'GENERAL',UTC_TIMESTAMP(6))")
        for index, application in enumerate((target, other)):
            request_key = key()
            version = h.scalar(f"SELECT CAST(row_version AS CHAR) FROM application WHERE id={application}")
            body = {"expectedVersion": version}
            expect(req("DELETE", f"/applications/{application}", body, actor="employee", request_key=request_key), 204)
            expect(req("DELETE", f"/applications/{application}", body, actor="employee", request_key=request_key), 204)
            expect(req("GET", f"/applications/{application}", actor="employee"), 404)
            require(h.scalar(f"SELECT COUNT(*) FROM application_draft WHERE application_id={application}") == "0", "deleted draft payload remains")
            require(h.scalar(f"SELECT COUNT(*) FROM file_binding WHERE application_id={application}") == "0", "deleted draft binding remains")
            if index == 0:
                require(h.scalar("SELECT COUNT(*) FROM file_binding WHERE file_id=9007199254742999") == "1", "shared binding was destroyed")
                require(h.scalar("SELECT status FROM file_object WHERE id=9007199254742999") == "READY", "shared file not preserved")
        require(int(h.scalar("SELECT COUNT(*) FROM file_cleanup WHERE file_id=9007199254742999 AND status='PENDING'")) >= 1, "file cleanup candidate not queued")
        return "DELETE after-gone same-key replay remains204; payload/bindings removed atomically, shared file stays READY and cleanup candidates never delete bytes here"
    h.check("delete replay and shared-file cleanup", delete_shared)

    def lock_revalidation() -> str:
        lockname = "task0405_" + secrets.token_hex(5)
        target = state["department_id"]
        body = {"name": "Revalidated Unit", "reason": "isolated verification", "expectedConfigVersion": config("admin2"), "expectedVersion": dv(target)}
        statement = ("START TRANSACTION; SELECT id FROM system_config WHERE id=1 FOR UPDATE; "
                     f"SELECT GET_LOCK('{lockname}',0); SELECT SLEEP(1.5); "
                     f"DELETE FROM user_role WHERE user_id={state['admin2_id']}; COMMIT; SELECT RELEASE_LOCK('{lockname}');")
        with ThreadPoolExecutor(max_workers=1) as pool:
            holder = pool.submit(h.mysql, statement)
            while h.scalar(f"SELECT IS_USED_LOCK('{lockname}')") == "NULL":
                require(not holder.done(), "config lock fixture failed")
                time.sleep(.05)
            expect(req("PATCH", f"/admin/departments/{target}", body, actor="admin2"), 403)
            require(holder.result(timeout=15).returncode == 0, "lock revalidation fixture failed")
        # Restore bootstrap fixture only, not an administrative pass substitute.
        sql(f"INSERT INTO user_role(user_id,role_id,created_at) SELECT {state['admin2_id']},id,UTC_TIMESTAMP(6) FROM role WHERE code='SYS_ADMIN'")
        lockname = "task0405_" + secrets.token_hex(5)
        statement = ("START TRANSACTION; SELECT id FROM system_config WHERE id=1 FOR UPDATE; "
                     f"SELECT GET_LOCK('{lockname}',0); SELECT SLEEP(7); ROLLBACK; SELECT RELEASE_LOCK('{lockname}');")
        with ThreadPoolExecutor(max_workers=1) as pool:
            holder = pool.submit(h.mysql, statement)
            while h.scalar(f"SELECT IS_USED_LOCK('{lockname}')") == "NULL":
                require(not holder.done(), "lock timeout fixture failed")
                time.sleep(.05)
            reply = expect(req("PATCH", f"/admin/departments/{target}", body, actor="admin2"), 503)
            require(reply.data["retryable"] is True, "database lock timeout must be retryable")
            require(holder.result(timeout=20).returncode == 0, "timeout fixture cleanup failed")
        management("PATCH", f"/admin/departments/{target}", {"name": "Recovered Unit"}, dv(target), actor="admin2")
        return "administrator revoked while waiting config lock is denied after lock; 3s wait timeout503 is retryable and post-release API recovers"
    h.check("lock-time revalidation timeout and recovery", lock_revalidation)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="compatibility flag; never builds")
    parser.add_argument("--keep", action="store_true", help="keep MySQL only; application and jar copy still cleaned")
    parser.add_argument("--json-out", type=Path, default=BACKEND / "evidence/task0405-mysql.json")
    args = parser.parse_args()
    h = MysqlHarness(find_java(), build=False, keep=args.keep, migration_target=None)
    app = Application(h)
    result = 0
    try:
        require(docker(["version"], timeout=30).returncode == 0, "Docker is required")
        verify(h, app)
    except Exception as error:
        print(f"TASK04/05 verification FAIL: {error}", file=sys.stderr)
        result = 1
    finally:
        if len(h.checks) != 11 or any(item["status"] != "PASS" for item in h.checks):
            result = 1
        app.close()
        artifact = {"task": "TASK-04/05", "generated_at": datetime.now(timezone.utc).isoformat(),
                    "verdict": "PASS" if result == 0 else "FAIL", "checks": h.checks,
                    "passed_check_count": sum(item["status"] == "PASS" for item in h.checks),
                    "failed_check_count": sum(item["status"] == "FAIL" for item in h.checks),
                    "jar_sha256": app.jar_sha256, "jar_execution": "one frozen private copy; no Maven during verification",
                    "migration_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(MIGRATION_DIR.glob("V*.sql"))},
                    "mysql_image": IMAGE, "http_requests": app.requests, "application_starts": app.starts,
                    "success_envelopes_checked": app.envelopes, "jvm_timezone": "Asia/Shanghai",
                    "runtime_database_account": "approval_app", "runtime_flyway_enabled": False,
                    "scope": "TASK04/05 API+SQL blackbox; isolated bootstrap/fault/file/submitted-read fixtures; no workflow/template execution or full T acceptance",
                    "credentials_recorded": False}
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        h.stop()
    return result


if __name__ == "__main__":
    raise SystemExit(main())
