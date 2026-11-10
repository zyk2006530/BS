#!/usr/bin/env python3
"""TASK-03 HTTP/SQL black-box checks on isolated MySQL and an already built jar.

Never runs Maven. The caller must serialize builds and supply a current jar.
All credentials/cookies/password hashes stay in memory, not in evidence or logs.
SQL fixtures for disabled/forced-password/clock cases are not management API tests.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import http.client
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

from verify_task02_mysql import (
    BACKEND, JAR, MIGRATION_DIR, ROOT, MysqlHarness, docker, find_java, free_port, require,
)


@dataclass
class Reply:
    status: int
    data: object
    headers: list[tuple[str, str]]
    location: str = ""

    def header(self, name: str) -> str | None:
        return next((v for k, v in self.headers if k.lower() == name.lower()), None)


def expect(reply: Reply, status: int, code: str | None = None) -> Reply:
    # Deliberately never interpolate response bodies: successful ones contain tokens.
    actual_code = reply.data.get("code") if isinstance(reply.data, dict) else None
    require(reply.status == status, f"{reply.location}: HTTP expected {status}, got {reply.status}; code={actual_code}")
    if code is not None:
        require(actual_code == code, f"HTTP error code expected {code}, got {actual_code}")
        require(reply.header("X-Request-Id") == reply.data.get("requestId"), "error request ID mismatch")
        require(reply.header("Cache-Control") == "no-store", "error must not be cached")
        require(set(reply.data) == {"code", "message", "fieldErrors", "requestId", "retryable"},
                "unexpected error details")
        require(reply.data["message"] == "Request could not be completed" and reply.data["fieldErrors"] == {},
                "error response must remain generic and field-redacted")
    return reply


class Application:
    def __init__(self, harness: MysqlHarness):
        self.h = harness
        self.port = free_port()
        self.origin = f"http://127.0.0.1:{self.port}"
        self.process: subprocess.Popen | None = None
        self.log = None
        self.requests = 0
        self.envelopes = 0
        self.lock = threading.Lock()
        self.secret = secrets.token_urlsafe(48)
        self.starts = 0
        self.jar_dir = None
        self.runtime_jar: Path | None = None
        self.jar_sha256: str | None = None

    def prepare_jar(self) -> None:
        self.jar_dir = tempfile.TemporaryDirectory(prefix="approval-task03-")
        self.runtime_jar = Path(self.jar_dir.name) / JAR.name
        shutil.copyfile(JAR, self.runtime_jar)
        self.jar_sha256 = hashlib.sha256(self.runtime_jar.read_bytes()).hexdigest()

    def close(self) -> None:
        self.stop()
        if self.jar_dir is not None:
            self.jar_dir.cleanup()
            self.jar_dir = None

    def request(self, method: str, path: str, data=None, *, token: str | None = None,
                cookies: dict[str, str] | None = None, csrf: str | None = None,
                origin: str | None = None, extra: list[tuple[str, str]] | None = None) -> Reply:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else b""
        headers = [("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
        if token is not None:
            headers.append(("Authorization", "Bearer " + token))
        if cookies is not None:
            headers.append(("Cookie", "; ".join(f"{k}={v}" for k, v in cookies.items())))
        if csrf is not None:
            headers.append(("X-CSRF-Token", csrf))
        if origin is not None:
            headers.append(("Origin", origin))
        headers.extend(extra or [])
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=40)
        try:
            connection.putrequest(method, "/api/v1" + path)
            for key, value in headers:
                connection.putheader(key, value)
            connection.endheaders(body)
            response = connection.getresponse()
            payload = response.read()
            with self.lock:
                self.requests += 1
            try:
                parsed = json.loads(payload) if payload else None
                if 200 <= response.status < 300 and response.status != 204 and path != "/health":
                    require(isinstance(parsed, dict) and set(parsed) == {"data", "requestId", "serverTime"},
                            "identity success response lacks standard envelope")
                    require(parsed["requestId"] == response.getheader("X-Request-Id"), "success request ID mismatch")
                    server_time = datetime.fromisoformat(parsed["serverTime"].replace("Z", "+00:00"))
                    require(server_time.utcoffset().total_seconds() == 0 and
                            abs((datetime.now(timezone.utc) - server_time).total_seconds()) < 30,
                            "success serverTime must describe current UTC instant")
                    require(response.getheader("Cache-Control") == "no-store", "success response must not be cached")
                    with self.lock:
                        self.envelopes += 1
                    parsed = parsed["data"]
            except (UnicodeDecodeError, json.JSONDecodeError):
                raise RuntimeError(f"non-JSON HTTP response ({response.status}); body withheld") from None
            return Reply(response.status, parsed, response.getheaders(), method + " " + path)
        finally:
            connection.close()

    def start(self) -> None:
        require(self.process is None, "application already running")
        env = self.h.app_env("approval", "approval_app", self.h.app_password, demo=False)
        env.update({"SPRING_FLYWAY_ENABLED": "false", "APP_CSRF_SECRET": self.secret,
                    "APP_WEB_ORIGIN": self.origin, "APP_COOKIE_SECURE": "false"})
        # Anonymous temporary output is never published: startup/debug logs may contain secrets.
        self.log = tempfile.TemporaryFile(mode="w+b")
        self.process = subprocess.Popen(
            [str(self.h.java), "-Duser.timezone=Asia/Shanghai", "-jar", str(self.runtime_jar), "--spring.profiles.active=db",
             f"--server.port={self.port}", "--server.address=127.0.0.1"],
            cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=self.log, stderr=self.log,
        )
        self.starts += 1
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.log.seek(0)
                text = self.log.read().decode("utf-8", "replace")
                types = sorted(set(re.findall(r"[A-Za-z0-9_.]+Exception", text)))
                raise RuntimeError(f"application exited before readiness; exception types={types}; raw logs withheld")
            try:
                if self.request("GET", "/health").status == 200:
                    return
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(0.25)
        raise RuntimeError("application readiness timed out; raw logs withheld")

    def stop(self) -> None:
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=10)
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None

    def restart(self) -> None:
        self.stop()
        self.start()


class Browser:
    def __init__(self, app: Application):
        self.app = app
        self.cookies: dict[str, str] = {}
        self.csrf: str | None = None

    def absorb(self, reply: Reply) -> Reply:
        for key, value in reply.headers:
            if key.lower() == "set-cookie":
                parsed = SimpleCookie()
                parsed.load(value)
                for name, cookie in parsed.items():
                    if cookie["max-age"] == "0":
                        self.cookies.pop(name, None)
                    else:
                        self.cookies[name] = cookie.value
        if isinstance(reply.data, dict) and "csrfToken" in reply.data:
            self.csrf = reply.data["csrfToken"]
        return reply

    def issue(self) -> None:
        self.absorb(expect(self.app.request("GET", "/auth/csrf", cookies=self.cookies or None), 200))
        require(self.csrf is not None and "csrf_ctx" in self.cookies, "missing pre-auth CSRF context")

    def request(self, method: str, path: str, data=None) -> Reply:
        return self.absorb(self.app.request(method, path, data, cookies=self.cookies or None,
                                            csrf=self.csrf, origin=self.app.origin))


def parallel_calls(count: int, function) -> list[Reply]:
    barrier = threading.Barrier(count)
    def one(index):
        barrier.wait(timeout=20)
        return function(index)
    with ThreadPoolExecutor(max_workers=count) as pool:
        return list(pool.map(one, range(count)))


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def verify(h: MysqlHarness, app: Application) -> None:
    app.prepare_jar()
    with zipfile.ZipFile(app.runtime_jar) as jar:
        require("BOOT-INF/classes/cn/edu/approval/security/IdentityController.class" in jar.namelist(),
                "jar lacks TASK-03 identity implementation; ask parent to build")
        for migration in sorted(MIGRATION_DIR.glob("V*.sql")):
            require(jar.read("BOOT-INF/classes/db/migration/" + migration.name) == migration.read_bytes(),
                    f"stale packaged migration {migration.name}; ask parent to build")
    h.start()

    def sql(statement: str) -> None:
        # Do not include SQL/stderr in a failure: some fixtures involve password/token hashes.
        require(h.mysql(statement).returncode == 0, "isolated SQL fixture/cleanup failed (details withheld)")

    def clear_limits() -> None:
        sql("DELETE FROM rate_limit_bucket")

    def login(username: str, password: str) -> str:
        reply = expect(app.request("POST", "/native-auth/login", {"username": username, "password": password}), 200)
        token = reply.data.get("token")
        require(isinstance(token, str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", token) is not None,
                "native session token must encode 32 random bytes")
        return token

    def registration(username: str, email: str, password: str) -> dict:
        return {"username": username, "name": "验证用户", "email": email, "password": password,
                "requestedDepartmentId": "1"}

    def session_where(token: str) -> str:
        return "token_hash=UNHEX('" + digest(token) + "')"

    state: dict = {"password": secrets.token_urlsafe(18), "username": "native_user"}

    def setup() -> str:
        env = h.app_env("approval", "approval_migrator", h.migration_password, demo=False)
        result = subprocess.run(
            [str(h.java), "-jar", str(app.runtime_jar), "--spring.profiles.active=db,migrate",
             "--spring.main.web-application-type=none"],
            cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180,
        )
        require(result.returncode == 0, "isolated V1-V4 migration failed; raw logs withheld")
        require(h.query("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank") ==
                [[str(i), "1"] for i in range(1, 5)], "expected successful V1-V4 migration history")
        require(h.scalar("SELECT COUNT(*) FROM user") == "0", "test schema is not fresh")
        h.expect_error("SELECT * FROM flyway_schema_history", 1142, user="approval_app", password=h.app_password)
        h.expect_error("CREATE TABLE forbidden(id INT)", 1142, user="approval_app", password=h.app_password)
        app.start()
        expect(app.request("GET", "/auth/me"), 401, "UNAUTHENTICATED")
        require(len(expect(app.request("GET", "/departments"), 200).data) == 3, "department list mismatch")
        return "fresh V1-V4; real db-profile HTTP app uses approval_app without runtime Flyway, DDL/history denied"
    h.check("isolated application and least-privilege startup", setup)

    def register_native() -> str:
        clear_limits()
        reply = expect(app.request("POST", "/native-auth/register",
                                  registration("  Native_User  ", "  Native@Example.Test  ", state["password"])), 201)
        require(reply.data.get("status") == "PENDING" and reply.data.get("mustChangePassword") is False,
                "registration status/flag mismatch")
        state["user_id"] = reply.data["userId"]
        require(h.query("SELECT username,email,status,department_id,requested_department_id,must_change_password,row_version FROM user") ==
                [["native_user", "native@example.test", "PENDING", "NULL", "1", "0", "1"]], "registration normalization/defaults")
        require(h.query("SELECT last_delivery_seq,revision FROM inbox_state") == [["0", "0"]], "registration inbox missing")
        require(h.scalar("SELECT COUNT(*) FROM user_role") == "0", "self registration granted roles")
        stored = h.scalar("SELECT password_hash FROM user WHERE username='native_user'")
        require(re.fullmatch(r"\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}", stored) is not None,
                "password hash does not match required Argon2id parameters")
        for username, email in (("Kelvin_user", "kelvin@example.test"),
                                ("kelvin_email", "Kelvin@example.test")):
            expect(app.request("POST", "/native-auth/register", registration(username, email, state["password"])),
                   400, "INVALID_INPUT")
        require(h.scalar("SELECT COUNT(*) FROM user") == "1", "non-ASCII normalization probe inserted user")
        for field, value in (("username", 12345), ("requestedDepartmentId", 1), ("username", True)):
            malformed = registration("malformed_user", "malformed@example.test", state["password"])
            malformed[field] = value
            expect(app.request("POST", "/native-auth/register", malformed), 400, "INVALID_REQUEST")
        expect(app.request("POST", "/native-auth/login", {"username": 12345, "password": state["password"]}),
               400, "INVALID_REQUEST")
        expect(app.request("POST", "/native-auth/login", {"username": state["username"], "password": True}),
               400, "INVALID_REQUEST")
        expect(app.request("POST", "/native-auth/register", registration("kelvin_user", "ascii-k@example.test", state["password"])), 201)
        login("kelvin_user", state["password"])
        expect(app.request("POST", "/native-auth/login", {"username": "Kelvin_user", "password": state["password"]}),
               401, "INVALID_CREDENTIALS")
        token = login(state["username"], state["password"])
        state["native_token"] = token
        require(h.scalar(f"SELECT COUNT(*) FROM auth_session WHERE {session_where(token)} AND client_type='MINI_PROGRAM'") == "1",
                "session stores no matching SHA-256 token digest")
        require(h.scalar(f"SELECT COUNT(*) FROM auth_session WHERE {session_where(token)} "
                         "AND ABS(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(6))) < 15 "
                         "AND ABS(TIMESTAMPDIFF(SECOND,last_seen_at,UTC_TIMESTAMP(6))) < 15 "
                         "AND TIMESTAMPDIFF(SECOND,created_at,absolute_expires_at)=604800") == "1",
                "new session timestamps/deadline drift from UTC or seven-day duration")
        require(h.scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='approval' AND table_name='auth_session' AND column_name IN ('token','sid','raw_token')") == "0",
                "raw token storage column detected")
        require(expect(app.request("GET", "/auth/me", token=token), 200).data.get("status") == "PENDING",
                "PENDING self profile unavailable")
        return "native registration normalizes username/email; PENDING/no roles/no department; inbox atomic; Argon2id parameters and SHA-256 session digest verified"
    h.check("native registration login and secret storage", register_native)

    def web_transport() -> str:
        clear_limits()
        browser = Browser(app)
        browser.issue()
        body = registration("web_user", "web@example.test", state["password"])
        expect(app.request("POST", "/auth/register", body, cookies=browser.cookies, csrf=browser.csrf), 403, "UNTRUSTED_ORIGIN")
        expect(app.request("POST", "/auth/register", body, cookies=browser.cookies, csrf=browser.csrf,
                           origin="https://attacker.invalid"), 403, "UNTRUSTED_ORIGIN")
        expect(app.request("POST", "/auth/register", body, cookies=browser.cookies, origin=app.origin), 403, "CSRF_INVALID")
        expect(browser.request("POST", "/auth/register", body), 201)
        old_cookies, old_proof = browser.cookies.copy(), browser.csrf
        reply = expect(browser.request("POST", "/auth/login", {"username": "web_user", "password": state["password"]}), 200)
        require("token" not in reply.data and "sid" in browser.cookies, "Web login must use HttpOnly sid, not response token")
        for cookie_name in ("sid", "csrf_ctx"):
            cookie_header = next((v for k, v in reply.headers if k.lower() == "set-cookie" and v.startswith(cookie_name + "=")), "")
            require("HttpOnly" in cookie_header and "SameSite=Lax" in cookie_header and "Path=/" in cookie_header,
                    "missing HttpOnly/SameSite/path cookie attributes")
        expect(app.request("POST", "/auth/logout", cookies=browser.cookies, csrf=old_proof, origin=app.origin), 403, "CSRF_INVALID")
        expect(app.request("POST", "/auth/logout", cookies=old_cookies, csrf=browser.csrf, origin=app.origin), 403, "CSRF_INVALID")
        old_sid = browser.cookies["sid"]
        expect(browser.request("POST", "/auth/login", {"username": "web_user", "password": state["password"]}), 200)
        require(browser.cookies["sid"] != old_sid, "successful Web login did not rotate sid")
        expect(app.request("GET", "/auth/me", cookies={"sid": old_sid}), 401, "INVALID_CREDENTIALS")
        expect(browser.request("GET", "/auth/me"), 200)
        token = state["native_token"]
        expect(app.request("GET", "/auth/me", token=token, cookies=browser.cookies), 400, "AMBIGUOUS_CREDENTIALS")
        expect(app.request("POST", "/native-auth/login", {}, cookies=browser.cookies), 400, "AMBIGUOUS_CREDENTIALS")
        expect(app.request("POST", "/native-auth/login", {}, token=token), 400, "AMBIGUOUS_CREDENTIALS")
        expect(app.request("GET", "/auth/me", extra=[("Authorization", "Bearer " + token), ("Authorization", "Bearer " + token)]), 400, "AMBIGUOUS_CREDENTIALS")
        expect(app.request("GET", "/auth/me", extra=[("Cookie", "sid=a; sid=b")]), 400, "AMBIGUOUS_CREDENTIALS")
        expect(app.request("GET", "/auth/me", token=browser.cookies["sid"]), 401, "INVALID_CREDENTIALS")
        expect(app.request("GET", "/auth/me", cookies={"sid": token}), 401, "INVALID_CREDENTIALS")
        require(app.request("POST", "/api-not-implemented", token=token).status in (401, 403, 404),
                "unimplemented route must not authorize access")
        state["web"] = browser
        return "Web registration/login; signed pre-auth/session-bound CSRF, exact Origin, HttpOnly cookies, sid rotation; mixed/duplicate credentials rejected; client transports cannot cross"
    h.check("Web CSRF Origin and credential selection", web_transport)

    def unique_races() -> str:
        clear_limits()
        replies = parallel_calls(2, lambda i: app.request("POST", "/native-auth/register",
            registration(("Race_User", "  race_user  ")[i], f"race{i}@example.test", state["password"])))
        require(Counter(r.status for r in replies) == Counter({201: 1, 409: 1}), "normalized username race must have one winner and one conflict")
        replies = parallel_calls(2, lambda i: app.request("POST", "/native-auth/register",
            registration(f"email_race_{i}", ("Shared@Example.Test", " shared@example.test ")[i], state["password"])))
        require(Counter(r.status for r in replies) == Counter({201: 1, 409: 1}), "normalized email race must have one winner and one conflict")
        require(h.scalar("SELECT COUNT(*) FROM user WHERE username='race_user'") == "1", "username race inserted duplicate users")
        require(h.scalar("SELECT COUNT(*) FROM user WHERE email='shared@example.test'") == "1", "email race inserted duplicate users")
        require(h.scalar("SELECT COUNT(*) FROM user u LEFT JOIN inbox_state i ON i.user_id=u.id WHERE i.user_id IS NULL") == "0", "registration produced an orphan inbox")
        before = h.scalar("SELECT COUNT(*) FROM user")
        body = registration("injected_user", "injected@example.test", state["password"])
        body["roles"] = ["SYS_ADMIN"]
        expect(app.request("POST", "/native-auth/register", body), 400, "INVALID_REQUEST")
        body.pop("roles")
        body["status"] = "ACTIVE"
        expect(app.request("POST", "/native-auth/register", body), 400, "INVALID_REQUEST")
        require(h.scalar("SELECT COUNT(*) FROM user") == before, "privilege injection inserted user")
        require(h.scalar("SELECT COUNT(*) FROM user_role") == "0", "role injection granted privileges")
        before_users = h.scalar("SELECT COUNT(*) FROM user")
        before_inboxes = h.scalar("SELECT COUNT(*) FROM inbox_state")
        sql("CREATE TRIGGER task03_inbox_failure BEFORE INSERT ON inbox_state FOR EACH ROW "
            "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='task03 fixture failure'")
        try:
            failure = expect(app.request("POST", "/native-auth/register",
                registration("rollback_user", "rollback@example.test", state["password"])), 503, "DATABASE_UNAVAILABLE")
            require(failure.data.get("retryable") is True, "inbox failure must be retryable")
            require(h.scalar("SELECT COUNT(*) FROM user") == before_users and
                    h.scalar("SELECT COUNT(*) FROM inbox_state") == before_inboxes,
                    "inbox insert failure left a partial registration")
        finally:
            sql("DROP TRIGGER IF EXISTS task03_inbox_failure")
        expect(app.request("POST", "/native-auth/register",
            registration("rollback_user", "rollback@example.test", state["password"])), 201)
        require(h.scalar("SELECT COUNT(*) FROM user u LEFT JOIN inbox_state i ON i.user_id=u.id WHERE i.user_id IS NULL") == "0",
                "registration recovery produced orphan inbox")
        return "two synchronized normalization races each produce 201+409; role/status overposting rejected; injected inbox failure returns redacted retryable503 and rolls back both rows, then recovery succeeds"
    h.check("concurrent normalized uniqueness and privilege injection", unique_races)

    def durable_limits() -> str:
        clear_limits()
        # Avoid a legitimate fixed-window reset during the concurrent requests/restart.
        remaining = 60 - time.time() % 60
        if remaining < 35:
            time.sleep(remaining + 0.1)
        start_window = int(time.time()) // 60
        replies = parallel_calls(8, lambda _: app.request("POST", "/native-auth/login",
            {"username": " NATIVE_USER ", "password": "wrong-password"}))
        require(Counter(r.status for r in replies) == Counter({401: 5, 429: 3}), "account concurrency must permit only five failed checks")
        account_key = digest("account:native_user")
        require(h.scalar(f"SELECT counter FROM rate_limit_bucket WHERE bucket_key=UNHEX('{account_key}') ORDER BY window_start DESC LIMIT 1") == "5",
                "failed credentials rolled back durable account counter")
        ip_key = digest("ip:login:127.0.0.1")
        sql(f"DELETE FROM rate_limit_bucket WHERE bucket_key=UNHEX('{ip_key}')")
        replies = parallel_calls(24, lambda i: app.request("POST", "/native-auth/login",
            {"username": f"unknown_{i}", "password": "wrong-password"}))
        require(Counter(r.status for r in replies) == Counter({401: 20, 429: 4}), "IP concurrency must permit only twenty login attempts")
        require(h.scalar(f"SELECT counter FROM rate_limit_bucket WHERE bucket_key=UNHEX('{ip_key}') ORDER BY window_start DESC LIMIT 1") == "20",
                "IP counter not durable/exact")
        for reply in replies:
            if reply.status == 429:
                require(1 <= int(reply.header("Retry-After") or "0") <= 60, "invalid IP Retry-After")
        app.restart()
        require(int(time.time()) // 60 == start_window, "test crossed fixed IP minute; rerun required")
        expect(app.request("POST", "/native-auth/login", {"username": "after_restart", "password": "wrong-password"}), 429, "RATE_LIMITED")
        sql(f"DELETE FROM rate_limit_bucket WHERE bucket_key=UNHEX('{ip_key}')")
        for password in ("wrong-password", state["password"]):
            reply = expect(app.request("POST", "/native-auth/login", {"username": state["username"], "password": password}), 429, "RATE_LIMITED")
            require(1 <= int(reply.header("Retry-After") or "0") <= 900, "invalid account Retry-After")
        return "8 concurrent account failures => 5x401+3x429/counter=5; 24 concurrent IP attempts => 20x401+4x429/counter=20; both survive application restart"
    h.check("durable concurrent account and login IP limits", durable_limits)

    def registration_limit() -> str:
        clear_limits()
        remaining = 60 - time.time() % 60
        if remaining < 15:
            time.sleep(remaining + 0.1)
        before = int(h.scalar("SELECT COUNT(*) FROM user"))
        replies = parallel_calls(12, lambda i: app.request("POST", "/native-auth/register",
            registration(f"limited_user_{i}", f"limited{i}@example.test", state["password"])))
        require(Counter(r.status for r in replies) == Counter({201: 10, 429: 2}), "registration IP concurrency limit mismatch")
        require(int(h.scalar("SELECT COUNT(*) FROM user")) == before + 10, "throttled registrations wrote users")
        key = digest("ip:register:127.0.0.1")
        require(h.scalar(f"SELECT counter FROM rate_limit_bucket WHERE bucket_key=UNHEX('{key}') ORDER BY window_start DESC LIMIT 1") == "10", "register IP counter mismatch")
        return "12 concurrent native registrations => 10x201+2x429; exactly ten users/inboxes and durable IP counter=10"
    h.check("concurrent registration IP limit", registration_limit)

    def logout_password() -> str:
        clear_limits()
        first = login(state["username"], state["password"])
        second = login(state["username"], state["password"])
        browser = Browser(app)
        browser.issue()
        expect(browser.request("POST", "/auth/login", {"username": state["username"], "password": state["password"]}), 200)
        expect(app.request("POST", "/auth/logout", token=first), 204)
        expect(app.request("POST", "/auth/logout", token=first), 204)
        expect(app.request("GET", "/auth/me", token=first), 401, "INVALID_CREDENTIALS")
        expect(app.request("GET", "/auth/me", token=second), 200)
        expect(browser.request("GET", "/auth/me"), 200)
        expect(app.request("POST", "/profile/password", {"oldPassword": "wrong-password", "newPassword": "Valid-New-Password-1"}, token=second), 401, "INVALID_CREDENTIALS")
        expect(app.request("GET", "/auth/me", token=second), 200)
        new_password = secrets.token_urlsafe(18)
        expect(app.request("POST", "/profile/password", {"oldPassword": state["password"], "newPassword": new_password}, token=second), 204)
        expect(app.request("GET", "/auth/me", token=second), 401, "INVALID_CREDENTIALS")
        expect(browser.request("GET", "/auth/me"), 401, "INVALID_CREDENTIALS")
        require(h.scalar(f"SELECT COUNT(*) FROM auth_session WHERE user_id={state['user_id']} AND revoked_at IS NULL") == "0", "password change did not revoke all transports")
        expect(app.request("POST", "/native-auth/login", {"username": state["username"], "password": state["password"]}), 401, "INVALID_CREDENTIALS")
        state["password"] = new_password
        state["native_token"] = login(state["username"], new_password)
        # Web logout additionally clears both browser cookies and its server session.
        web = state["web"]
        web_token = web.cookies["sid"]
        expect(web.request("POST", "/auth/logout"), 204)
        require("sid" not in web.cookies and "csrf_ctx" not in web.cookies, "logout did not expire Web cookies")
        expect(app.request("GET", "/auth/me", cookies={"sid": web_token}), 401, "INVALID_CREDENTIALS")
        return "logout is idempotent and revokes only current session; password change verifies old secret and revokes all Web/native sessions; old password rejected; Web cookies expire"
    h.check("logout and password change revocation", logout_password)

    def account_gates() -> str:
        clear_limits()
        token = state["native_token"]
        sql(f"UPDATE user SET must_change_password=1 WHERE id={state['user_id']}")
        reply = expect(app.request("GET", "/auth/me", token=token), 200)
        require(reply.data.get("mustChangePassword") is True, "forced-password flag missing")
        expect(app.request("GET", "/profile", token=token), 403, "PASSWORD_CHANGE_REQUIRED")
        expect(app.request("PATCH", "/profile", {"name": "blocked", "email": "blocked@example.test"}, token=token), 403, "PASSWORD_CHANGE_REQUIRED")
        expect(app.request("POST", "/profile/confirmation-request", {}, token=token), 403, "PASSWORD_CHANGE_REQUIRED")
        new_password = secrets.token_urlsafe(18)
        expect(app.request("POST", "/profile/password", {"oldPassword": state["password"], "newPassword": new_password}, token=token), 204)
        require(h.scalar(f"SELECT must_change_password FROM user WHERE id={state['user_id']}") == "0", "forced flag not cleared after password change")
        expect(app.request("GET", "/auth/me", token=token), 401, "INVALID_CREDENTIALS")
        state["password"] = new_password
        token = login(state["username"], new_password)
        sql(f"UPDATE user SET status='DISABLED' WHERE id={state['user_id']}")
        expect(app.request("GET", "/auth/me", token=token), 401, "INVALID_CREDENTIALS")
        expect(app.request("POST", "/native-auth/login", {"username": state["username"], "password": new_password}), 401, "INVALID_CREDENTIALS")
        sql(f"UPDATE user SET status='PENDING' WHERE id={state['user_id']}")
        return "SQL-forced mustChangePassword allows me/password only, changing password clears flag/revokes sessions; SQL-DISABLED user denied existing-session auth and new login (not admin API validation)"
    h.check("forced-password and disabled-account authorization", account_gates)

    def session_clocks() -> str:
        clear_limits()
        token = login(state["username"], state["password"])
        where = session_where(token)
        sql(f"UPDATE auth_session SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 29 MINUTE) WHERE {where}")
        expect(app.request("GET", "/auth/me", token=token), 200)
        sql(f"UPDATE auth_session SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE) WHERE {where}")
        expect(app.request("GET", "/auth/me", token=token), 401, "INVALID_CREDENTIALS")
        sql(f"UPDATE auth_session SET last_seen_at=UTC_TIMESTAMP(6),absolute_expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE {where}")
        expect(app.request("GET", "/auth/me", token=token), 200)
        sql(f"UPDATE auth_session SET absolute_expires_at=UTC_TIMESTAMP(6) WHERE {where}")
        expect(app.request("GET", "/auth/me", token=token), 401, "INVALID_CREDENTIALS")
        return "SQL-adjusted session clocks: 29min idle accepted, at/after 30min rejected; future absolute deadline accepted, reached deadline rejected; exact frozen-clock boundary belongs to Java unit tests"
    h.check("idle and absolute session expiry", session_clocks)

    def profiles() -> str:
        clear_limits()
        token = login(state["username"], state["password"])
        pending_reply = app.request("GET", "/profile", token=token)
        pending_reply.location += " [PENDING before update]"
        profile = expect(pending_reply, 200).data
        update = {"name": "Updated Fixture", "email": "updated@example.test", "expectedVersion": profile["rowVersion"]}
        changed = expect(app.request("PATCH", "/profile", update, token=token), 200).data
        require(int(changed["rowVersion"]) == int(profile["rowVersion"]) + 1, "profile update did not increment version")
        expect(app.request("PATCH", "/profile", update, token=token), 409, "IDENTITY_CONFLICT")
        sql(f"UPDATE user SET status='REJECTED',rejection_reason='isolated fixture',row_version=row_version+1 WHERE id={state['user_id']}")
        rejected_reply = app.request("GET", "/profile", token=token)
        rejected_reply.location += " [SQL-REJECTED after profile update]"
        profile = expect(rejected_reply, 200).data
        body = {"requestedDepartmentId": "2", "expectedVersion": profile["rowVersion"], "expectedConfigVersion": profile["configVersion"]}
        changed = expect(app.request("POST", "/profile/confirmation-request", body, token=token), 200).data
        require(changed["status"] == "PENDING" and changed["requestedDepartmentId"] == "2" and changed["rejectionReason"] is None,
                "rejected confirmation resubmit did not transition atomically")
        expect(app.request("POST", "/profile/confirmation-request", body, token=token), 409, "IDENTITY_CONFLICT")
        require(h.scalar("SELECT COUNT(*) FROM user u LEFT JOIN inbox_state i ON i.user_id=u.id WHERE i.user_id IS NULL") == "0", "missing registered inbox")
        require(h.scalar("SELECT COUNT(*) FROM user_role") == "0", "self APIs assigned roles")
        return "versioned self-profile update rejects stale version; SQL-REJECTED fixture resubmits to PENDING with user/config versions; all registered users retain inbox/no roles"
    h.check("versioned profile and confirmation resubmission", profiles)

    def lock_timeout() -> str:
        clear_limits()
        token = login(state["username"], state["password"])
        lock_name = "task03_fixture_" + secrets.token_hex(6)
        statement = (f"START TRANSACTION; SELECT id FROM user WHERE id={state['user_id']} FOR UPDATE; "
                     f"SELECT GET_LOCK('{lock_name}',0); SELECT SLEEP(7); ROLLBACK; "
                     f"SELECT RELEASE_LOCK('{lock_name}');")
        with ThreadPoolExecutor(max_workers=1) as pool:
            holder = pool.submit(h.mysql, statement)
            deadline = time.monotonic() + 10
            while h.scalar(f"SELECT IS_USED_LOCK('{lock_name}')") == "NULL":
                require(not holder.done() and time.monotonic() < deadline, "SQL lock fixture did not acquire user row")
                time.sleep(0.05)
            reply = expect(app.request("GET", "/auth/me", token=token), 503)
            require(isinstance(reply.data, dict) and reply.data.get("retryable") is True,
                    "lock timeout must be retryable")
            require(set(reply.data) == {"code", "message", "fieldErrors", "requestId", "retryable"},
                    "lock timeout leaked database details")
            require(reply.data["message"] == "Request could not be completed" and reply.data["fieldErrors"] == {},
                    "lock error response must remain generic")
            require(reply.header("X-Request-Id") == reply.data.get("requestId"), "lock error request ID mismatch")
            require(holder.result(timeout=20).returncode == 0, "SQL lock fixture did not release cleanly")
        expect(app.request("GET", "/auth/me", token=token), 200)
        return "real user-row lock exceeds configured 3s timeout => redacted retryable HTTP503; same session succeeds after rollback releases lock"
    h.check("real database lock timeout and recovery", lock_timeout)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="compatibility flag; this verifier never builds")
    parser.add_argument("--keep", action="store_true", help="keep isolated MySQL container; application always stops")
    parser.add_argument("--json-out", type=Path, default=BACKEND / "evidence/task03-mysql.json")
    args = parser.parse_args()
    h = MysqlHarness(find_java(), build=False, keep=args.keep, migration_target=None)
    app = Application(h)
    exit_code = 0
    try:
        require(docker(["version"], timeout=30).returncode == 0, "Docker is required")
        verify(h, app)
    except Exception as error:
        # Helpers expose only statuses/codes; never log request/response bodies or raw app output.
        print(f"TASK-03 verification FAIL: {error}", file=sys.stderr)
        exit_code = 1
    finally:
        app.close()
        artifact = {
            "task": "TASK-03", "generated_at": datetime.now(timezone.utc).isoformat(),
            "verdict": "PASS" if exit_code == 0 else "FAIL", "checks": h.checks,
            "planned_check_count": 11, "passed_check_count": sum(c["status"] == "PASS" for c in h.checks),
            "failed_check_count": sum(c["status"] == "FAIL" for c in h.checks),
            "jar_sha256": app.jar_sha256,
            "jar_execution": "one private immutable copy used for migration and every application start",
            "migration_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(MIGRATION_DIR.glob("V*.sql"))},
            "mysql_image": __import__("verify_task02_mysql").IMAGE,
            "application_profile": "db", "runtime_database_account": "approval_app",
            "migration_database_account": "approval_migrator", "runtime_flyway_enabled": False,
            "http_requests": app.requests, "application_starts": app.starts,
            "jvm_timezone": "Asia/Shanghai", "identity_success_envelopes_checked": app.envelopes,
            "fixture_scope": "disposable MySQL only; SQL resets limiter buckets between independent checks and sets account/session fixtures",
            "transport_scope": "loopback HTTP only; Secure=false test configuration, not TLS/deployment acceptance",
            "scope": "TASK-03 HTTP/SQL black-box only; no administrative API, no TASK09/10, no full T01-T22 acceptance promotion",
            "credentials_recorded": False,
        }
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        h.stop()
        print(f"evidence: {args.json_out}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
