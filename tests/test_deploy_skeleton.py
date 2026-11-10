"""Standard-library static guards for the TASK-01/02 deployment contract; never runs Docker."""
from __future__ import annotations

import copy
from http.server import BaseHTTPRequestHandler, HTTPServer
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "deploy"
EXPECTED_BASES = {
    "backend": "eclipse-temurin:17.0.17_10-jdk-jammy@sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed",
    "frontend": "nginx:1.28.0-alpine3.21@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284",
}
EXPECTED_MYSQL = "mysql:8.4.7@sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903"
REQUIRED_SECRET = re.compile(r"^\$\{[A-Z_]+:\?[^}]+\}$")


def validate_config(config: dict, backend: str, frontend: str, nginx: str) -> None:
    """Reject regressions in reviewed static configuration, not runtime behaviour."""
    def require(condition: bool, message: str) -> None:
        if not condition:
            raise ValueError(message)

    services = config["services"]
    require(
        set(services) == {"frontend", "backend", "mysql", "migrate"},
        "exactly three long-running services plus the migration job",
    )
    require(services["frontend"].get("ports") == ["127.0.0.1:8088:80"], "loopback-only development HTTP")
    for name in ("backend", "mysql", "migrate"):
        require(not services[name].get("ports"), "no backend/database/migration published ports")
    for service in services.values():
        require(not service.get("privileged"), "no privileged containers")
        require(service.get("network_mode") != "host", "no host networking")
        require(not service.get("links"), "no implicit legacy network links")
    require(config["networks"]["data"].get("internal") is True, "private database network")
    require(services["mysql"]["networks"] == ["data"], "mysql only on private network")
    require(services["migrate"]["networks"] == ["data"], "migration job only on private network")
    require(services["frontend"]["networks"] == ["app"], "frontend cannot join database network")
    require(set(services["backend"]["networks"]) == {"app", "data"}, "backend network bridge")
    require(services["mysql"]["image"] == EXPECTED_MYSQL, "exact MySQL image version")
    for name, text in (("backend", backend), ("frontend", frontend)):
        bases = re.findall(r"^FROM\s+(\S+)", text, re.MULTILINE)
        require(bases == [EXPECTED_BASES[name]], "exact reviewed base image version")
        require(services[name]["image"] == f"approval-{name}:0.1.0-skeleton", "version local build images")
        build = services[name]["build"]
        require(build == {"context": "..", "dockerfile": f"deploy/{name}.Dockerfile"}, "reviewed build context")
    require(services["migrate"]["image"] == "approval-backend:0.1.0-skeleton", "migration reuses backend artifact")
    require("build" not in services["migrate"], "migration must not rebuild the backend")
    mysql_env = services["mysql"]["environment"]
    backend_env = services["backend"]["environment"]
    migrate_env = services["migrate"]["environment"]
    for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_APP_PASSWORD", "MYSQL_MIGRATION_PASSWORD"):
        require(bool(REQUIRED_SECRET.fullmatch(mysql_env[key])), "passwords must use required environment expansion")
    require(mysql_env["MYSQL_ROOT_PASSWORD"].startswith("${MYSQL_ROOT_PASSWORD:?"), "root password variable")
    require(mysql_env["MYSQL_APP_PASSWORD"].startswith("${MYSQL_APP_PASSWORD:?"), "app password variable")
    require(
        mysql_env["MYSQL_MIGRATION_PASSWORD"].startswith("${MYSQL_MIGRATION_PASSWORD:?"),
        "migration password variable",
    )
    require("MYSQL_USER" not in mysql_env and "MYSQL_PASSWORD" not in mysql_env, "init script creates fixed accounts")
    require(
        not any(
            key in mysql_env
            for key in ("MYSQL_ALLOW_EMPTY_PASSWORD", "MYSQL_RANDOM_ROOT_PASSWORD", "MYSQL_ROOT_HOST")
        ),
        "no empty/implicit/remote-root bootstrap",
    )
    require(backend_env["DB_USERNAME"] == "approval_app", "backend uses the application account")
    require(backend_env["DB_PASSWORD"] == mysql_env["MYSQL_APP_PASSWORD"], "backend uses app credential")
    require(backend_env["SPRING_PROFILES_ACTIVE"] == "db", "backend activates the database profile")
    require(backend_env["SPRING_FLYWAY_ENABLED"] == "false", "backend never migrates at runtime")
    require(not any(key.startswith("DB_MIGRATION_") for key in backend_env), "backend has no migration credential")
    require(migrate_env["DB_USERNAME"] == "approval_migrator", "migration uses the migration account")
    require(migrate_env["DB_PASSWORD"] == mysql_env["MYSQL_MIGRATION_PASSWORD"], "migration uses migration credential")
    require(migrate_env["DB_MIGRATION_USER"] == "approval_migrator", "Flyway uses the migration account")
    require(migrate_env["DB_MIGRATION_PASSWORD"] == mysql_env["MYSQL_MIGRATION_PASSWORD"], "Flyway migration credential")
    command = services["migrate"]["command"]
    require("--spring.profiles.active=db,migrate" in command, "migration profile is explicit")
    require("--spring.main.web-application-type=none" in command, "migration job is one-shot")
    require(
        not any(part in {"java", "-jar", "/opt/approval/app.jar"} for part in command),
        "migration command must inherit the image entrypoint, not repeat it",
    )
    require(services["migrate"]["restart"] == "no", "migration job does not restart")
    require(
        services["mysql"]["volumes"]
        == ["mysql-data:/var/lib/mysql", "./mysql/init:/docker-entrypoint-initdb.d:ro"],
        "database volume plus reviewed account-init mount only",
    )
    require(services["backend"]["volumes"] == ["uploads:/var/lib/approval/uploads"], "private backend uploads volume")
    require(not services["migrate"].get("volumes"), "migration job has no data volume")
    require(not services["frontend"].get("volumes"), "no shared frontend attachment volume")
    require(config["volumes"] == {"mysql-data": {}, "uploads": {}}, "private named volumes without public bind paths")
    require(backend_env["APP_UPLOAD_DIR"] == "/var/lib/approval/uploads", "private upload path")
    require(
        services["backend"]["depends_on"] == {"migrate": {"condition": "service_completed_successfully"}},
        "backend waits for a successful migration",
    )
    require(services["frontend"]["depends_on"] == {"backend": {"condition": "service_healthy"}}, "wait for backend health")
    require(services["migrate"]["depends_on"] == {"mysql": {"condition": "service_healthy"}}, "wait for mysql")
    health = services["backend"]["healthcheck"]
    require(health["test"] == ["CMD", "java", "/opt/approval/HealthProbe.java"], "JDK-only health probe")
    require("COPY deploy/HealthProbe.java /opt/approval/HealthProbe.java" in backend, "package health probe")
    require("USER 10001:10001" in backend, "non-root backend")
    require("COPY backend/target/approval-backend-0.1.0-SNAPSHOT.jar app.jar" in backend, "artifact-first jar contract")
    require("COPY web/dist/ /usr/share/nginx/html/" in frontend, "artifact-first frontend contract")
    mysql_health = services["mysql"]["healthcheck"]["test"]
    require(mysql_health[0] == "CMD-SHELL" and "mysqladmin ping" in mysql_health[1], "installed mysql health command")
    require('MYSQL_PWD="$$MYSQL_ROOT_PASSWORD"' in mysql_health[1], "Compose must escape healthcheck shell variable")
    require("location /api/" in nginx and "proxy_pass http://backend:8080;" in nginx, "same-origin API proxy keeps URI")
    require("proxy_set_header X-Forwarded-For $remote_addr;" in nginx, "overwrite untrusted forwarding chain")
    require("proxy_set_header Forwarded \"\";" in nginx, "discard untrusted Forwarded header")
    require("$proxy_add_x_forwarded_for" not in nginx, "do not append untrusted proxy headers")
    require(not re.search(r"\balias\b\s+[^;]+;", nginx), "no public filesystem aliases")
    require("/var/lib/approval/uploads" not in nginx, "no static attachment path")
    require("location ~ ^/(uploads|attachments|private)(/|$)" in nginx, "explicit private path denial")
    upload = re.search(r"location = /api/v1/files\s*\{([^}]+)\}", nginx)
    require(upload is not None, "exact upload proxy location")
    upload_config = upload.group(1)
    require("client_max_body_size 11m;" in upload_config, "upload multipart envelope bound")
    require("proxy_request_buffering off;" in upload_config, "upload must stream to backend deadline")
    require("proxy_read_timeout 125s;" in upload_config and "proxy_send_timeout 125s;" in upload_config, "upload timeout must cover server deadline")
    without_upload = nginx[:upload.start()] + nginx[upload.end():]
    require("client_max_body_size 1m;" in without_upload and "client_max_body_size 11m;" not in without_upload, "JSON limit must remain separate from upload")


def validate_registry_evidence(raw: bytes, entry: dict) -> None:
    """Check preserved response bytes; this does not make a live network request."""
    digest = "sha256:" + hashlib.sha256(raw).hexdigest()
    if digest != entry["digest"] or digest != entry["dockerContentDigest"]:
        raise ValueError("registry index digest mismatch")
    if len(raw) != entry["manifestBytes"] or entry["httpStatus"] != 200:
        raise ValueError("registry response evidence mismatch")
    payload = json.loads(raw)
    if payload["mediaType"] != "application/vnd.oci.image.index.v1+json":
        raise ValueError("expected OCI multi-platform index")
    platforms = [item.get("platform", {}) for item in payload["manifests"]]
    for architecture in ("amd64", "arm64"):
        if not any(p.get("os") == "linux" and p.get("architecture") == architecture for p in platforms):
            raise ValueError("expected Linux amd64 and arm64 platforms")


class DeploySkeletonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # JSON is a YAML subset accepted by Compose. No third-party YAML parser needed.
        cls.config = json.loads((DEPLOY / "compose.yaml").read_text(encoding="utf-8"))
        cls.backend = (DEPLOY / "backend.Dockerfile").read_text(encoding="utf-8")
        cls.frontend = (DEPLOY / "frontend.Dockerfile").read_text(encoding="utf-8")
        cls.nginx = (DEPLOY / "nginx.conf").read_text(encoding="utf-8")

    def check(self, config=None, backend=None, frontend=None, nginx=None) -> None:
        validate_config(
            self.config if config is None else config,
            self.backend if backend is None else backend,
            self.frontend if frontend is None else frontend,
            self.nginx if nginx is None else nginx,
        )

    def test_reviewed_configuration(self) -> None:
        self.check()

    def test_upload_proxy_preserves_private_streaming_boundaries(self) -> None:
        for before, after in (("location = /api/v1/files", "location /api/v1/files"),
                              ("client_max_body_size 11m;", "client_max_body_size 100m;"),
                              ("proxy_request_buffering off;", "proxy_request_buffering on;"),
                              ("proxy_read_timeout 125s;", "proxy_read_timeout 30s;"),
                              ("client_max_body_size 1m;", "client_max_body_size 11m;")):
            with self.subTest(before=before), self.assertRaises(ValueError):
                self.check(nginx=self.nginx.replace(before, after))

    def test_reject_database_host_port(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["mysql"]["ports"] = ["3306:3306"]
        with self.assertRaisesRegex(ValueError, "published ports"):
            self.check(config=config)

    def test_reject_backend_host_port(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["backend"]["ports"] = ["8080:8080"]
        with self.assertRaisesRegex(ValueError, "published ports"):
            self.check(config=config)

    def test_reject_migration_host_port(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["migrate"]["ports"] = ["8081:8080"]
        with self.assertRaisesRegex(ValueError, "published ports"):
            self.check(config=config)

    def test_reject_public_development_listener(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["frontend"]["ports"] = ["8088:80"]
        with self.assertRaisesRegex(ValueError, "loopback"):
            self.check(config=config)

    def test_reject_public_database_network(self) -> None:
        config = copy.deepcopy(self.config)
        config["networks"]["data"]["internal"] = False
        with self.assertRaisesRegex(ValueError, "private database"):
            self.check(config=config)

    def test_reject_host_network(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["mysql"]["network_mode"] = "host"
        with self.assertRaisesRegex(ValueError, "host networking"):
            self.check(config=config)

    def test_reject_frontend_database_access(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["frontend"]["networks"].append("data")
        with self.assertRaisesRegex(ValueError, "frontend cannot"):
            self.check(config=config)

    def test_reject_literal_and_default_passwords(self) -> None:
        for key in ("MYSQL_APP_PASSWORD", "MYSQL_MIGRATION_PASSWORD", "MYSQL_ROOT_PASSWORD"):
            for password in ("password", "", "${PASSWORD:-changeme}", "${PASSWORD}"):
                with self.subTest(key=key, password=password):
                    config = copy.deepcopy(self.config)
                    config["services"]["mysql"]["environment"][key] = password
                    with self.assertRaisesRegex(ValueError, "required environment"):
                        self.check(config=config)

    def test_reject_allow_empty_password(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["mysql"]["environment"]["MYSQL_ALLOW_EMPTY_PASSWORD"] = "yes"
        with self.assertRaisesRegex(ValueError, "no empty"):
            self.check(config=config)

    def test_reject_root_backend_account(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["backend"]["environment"]["DB_USERNAME"] = "root"
        with self.assertRaisesRegex(ValueError, "application account"):
            self.check(config=config)

    def test_reject_migration_using_app_account(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["migrate"]["environment"]["DB_USERNAME"] = "approval_app"
        with self.assertRaisesRegex(ValueError, "migration account"):
            self.check(config=config)

    def test_reject_backend_migration_credential(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["backend"]["environment"]["DB_MIGRATION_USER"] = "approval_migrator"
        with self.assertRaisesRegex(ValueError, "no migration credential"):
            self.check(config=config)

    def test_reject_mutable_mysql_image(self) -> None:
        for tag in ("mysql:latest", "mysql:8", "mysql:8.4"):
            with self.subTest(tag=tag):
                config = copy.deepcopy(self.config)
                config["services"]["mysql"]["image"] = tag
                with self.assertRaisesRegex(ValueError, "exact MySQL"):
                    self.check(config=config)

    def test_reject_mutable_base_images(self) -> None:
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            self.check(backend=self.backend.replace(EXPECTED_BASES["backend"], "eclipse-temurin:17"))
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            self.check(frontend=self.frontend.replace(EXPECTED_BASES["frontend"], "nginx:latest"))

    def test_reject_public_attachment_volume(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["frontend"]["volumes"] = ["uploads:/usr/share/nginx/html/uploads"]
        with self.assertRaisesRegex(ValueError, "frontend attachment"):
            self.check(config=config)

    def test_reject_static_attachment_alias(self) -> None:
        with self.assertRaisesRegex(ValueError, "filesystem aliases"):
            self.check(nginx=self.nginx + "\nlocation /files/ { alias /uploads/; }\n")

    def test_reject_init_sql_mount(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["mysql"]["volumes"].append("./init.sql:/docker-entrypoint-initdb.d/init.sql")
        with self.assertRaisesRegex(ValueError, "database volume plus reviewed"):
            self.check(config=config)

    def test_reject_healthcheck_with_uninstalled_curl(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["backend"]["healthcheck"]["test"] = ["CMD", "curl", "http://localhost:8080"]
        with self.assertRaisesRegex(ValueError, "JDK-only"):
            self.check(config=config)

    def test_reject_untrusted_forwarding_header(self) -> None:
        with self.assertRaisesRegex(ValueError, "forwarding chain"):
            self.check(nginx=self.nginx.replace("X-Forwarded-For $remote_addr", "X-Forwarded-For $proxy_add_x_forwarded_for"))

    def test_env_example_has_no_password(self) -> None:
        values = {}
        for line in (DEPLOY / ".env.example").read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value
        self.assertEqual(values["MYSQL_ROOT_PASSWORD"], "")
        self.assertEqual(values["MYSQL_APP_PASSWORD"], "")
        self.assertEqual(values["MYSQL_MIGRATION_PASSWORD"], "")
        self.assertNotIn("MYSQL_APP_USER", values)
        self.assertNotIn("MYSQL_MIGRATION_USER", values)

    def test_database_init_creates_separate_fixed_accounts(self) -> None:
        script = (DEPLOY / "mysql/init/01-accounts.sh").read_text(encoding="utf-8")
        self.assertIn("DROP USER IF EXISTS 'approval_app'@'%';", script)
        self.assertIn("DROP USER IF EXISTS 'approval_migrator'@'%';", script)
        self.assertIn("CREATE USER 'approval_app'@'%'", script)
        self.assertIn("CREATE USER 'approval_migrator'@'%'", script)
        self.assertIn("WITH GRANT OPTION", script)
        self.assertIn("MYSQL_DATABASE", script)
        self.assertIn("GRANT ALL PRIVILEGES ON \\`${MYSQL_DATABASE}\\`.*", script)
        self.assertIn("MYSQL_APP_PASSWORD", script)
        self.assertIn("MYSQL_MIGRATION_PASSWORD", script)
        self.assertNotIn("password123", script.lower())

    def test_runtime_grant_migration_is_least_privilege(self) -> None:
        sql = (ROOT / "backend/src/main/resources/db/migration/V3__task02_runtime_grants.sql").read_text(
            encoding="utf-8"
        )
        audit_grants = [
            line for line in sql.splitlines() if line.startswith("GRANT") and "audit_record" in line
        ]
        self.assertEqual(
            audit_grants,
            [
                "GRANT SELECT, INSERT ON `${flyway:database}`.`audit_record` "
                "TO 'approval_app'@'%';"
            ],
        )
        self.assertFalse(
            any(line.startswith("GRANT") and "approval_record" in line for line in sql.splitlines())
        )
        self.assertNotIn("GRANT ALL", sql)
        self.assertIn("GRANT SELECT, UPDATE ON `${flyway:database}`.`system_config`", sql)
        self.assertIn("GRANT SELECT ON `${flyway:database}`.`role`", sql)

    def test_build_context_is_allowlisted(self) -> None:
        for name in ("backend", "frontend"):
            text = (DEPLOY / f"{name}.Dockerfile.dockerignore").read_text(encoding="utf-8")
            self.assertTrue(text.startswith("**\n"))
            self.assertNotIn("!.env", text)
            self.assertNotIn("!deploy/**", text)
            self.assertNotIn("!**", text)

    def test_health_probe_marks_skeleton_not_business(self) -> None:
        probe = (DEPLOY / "HealthProbe.java").read_text(encoding="utf-8")
        self.assertIn("/api/v1/health", probe)
        self.assertIn("SKELETON", probe)
        self.assertIn("businessReady", probe)
        self.assertIn("false", probe)
        self.assertIn("setConnectTimeout(2000)", probe)
        self.assertIn("setReadTimeout(2000)", probe)
        self.assertIn("System.exit(1)", probe)

    def test_registry_manifest_bytes_match_recorded_digests(self) -> None:
        lock = json.loads((DEPLOY / "image-lock.json").read_text(encoding="utf-8"))
        self.assertEqual(len(lock["checks"]), 3)
        for entry in lock["checks"]:
            with self.subTest(image=entry["image"]):
                raw = (DEPLOY / entry["manifestFile"]).read_bytes()
                validate_registry_evidence(raw, entry)
                self.assertEqual(entry["registry"], "registry-1.docker.io")
                self.assertTrue(entry["sourceUrl"].startswith("https://registry-1.docker.io/v2/library/"))

    def test_registry_records_match_deployment_image_references(self) -> None:
        lock = json.loads((DEPLOY / "image-lock.json").read_text(encoding="utf-8"))
        actual = {entry["image"] + "@" + entry["digest"] for entry in lock["checks"]}
        self.assertEqual(actual, {*EXPECTED_BASES.values(), EXPECTED_MYSQL})

    def test_reject_index_evidence_tampering(self) -> None:
        entry = json.loads((DEPLOY / "image-lock.json").read_text(encoding="utf-8"))["checks"][0]
        raw = (DEPLOY / entry["manifestFile"]).read_bytes()
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate_registry_evidence(raw + b"\n", entry)

    def test_reject_registry_header_digest_mismatch(self) -> None:
        entry = json.loads((DEPLOY / "image-lock.json").read_text(encoding="utf-8"))["checks"][0]
        raw = (DEPLOY / entry["manifestFile"]).read_bytes()
        entry["dockerContentDigest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate_registry_evidence(raw, entry)

    def test_reject_exact_tags_without_digest(self) -> None:
        config = copy.deepcopy(self.config)
        config["services"]["mysql"]["image"] = EXPECTED_MYSQL.split("@", 1)[0]
        with self.assertRaisesRegex(ValueError, "exact MySQL"):
            self.check(config=config)
        for name, reference in EXPECTED_BASES.items():
            with self.subTest(image=name):
                kwargs = {name: getattr(self, name).replace(reference, reference.split("@", 1)[0])}
                with self.assertRaisesRegex(ValueError, "exact reviewed"):
                    self.check(**kwargs)

    def test_documentation_does_not_claim_docker_validation(self) -> None:
        text = (DEPLOY / "README.md").read_text(encoding="utf-8")
        self.assertIn("NOT_RUN", text)
        self.assertIn("不是生产 HTTPS", text)
        self.assertIn("businessReady=false", text)
        self.assertIn("3.9.11", text)
        self.assertIn("24.17.0", text)
        self.assertIn("11.13.0", text)


@unittest.skipUnless(os.environ.get("DEPLOY_JAVA_HOME"), "Set DEPLOY_JAVA_HOME to run real JDK probe tests; Docker remains NOT_RUN")
class HealthProbeJavaTests(unittest.TestCase):
    """Real Java/HTTP unit fixture, not a Docker or backend integration simulation."""

    @classmethod
    def setUpClass(cls) -> None:
        java_home = Path(os.environ["DEPLOY_JAVA_HOME"])
        suffix = ".exe" if os.name == "nt" else ""
        cls.java = java_home / "bin" / ("java" + suffix)
        javac = java_home / "bin" / ("javac" + suffix)
        local = ROOT / ".local"
        local.mkdir(exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="deploy-probe-", dir=local)
        cls.addClassCleanup(cls.temp.cleanup)
        # A failure is a real compilation failure, not a test skip.
        subprocess.run([str(javac), "--release", "17", "-d", cls.temp.name,
                        str(DEPLOY / "HealthProbe.java")], check=True, timeout=30)
        cls.status = 200
        cls.body = b'{"status":"UP","stage":"SKELETON","businessReady":false}'

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/api/v1/health":
                    self.send_error(404)
                    return
                self.send_response(cls.status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(cls.body)

            def log_message(self, *args):
                pass

        cls.server = HTTPServer(("127.0.0.1", 8080), Handler)
        cls.addClassCleanup(cls.server.server_close)
        thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        thread.start()
        cls.addClassCleanup(thread.join, 5)
        cls.addClassCleanup(cls.server.shutdown)

    def run_probe(self, status: int, body: bytes, source_mode: bool = False) -> int:
        type(self).status, type(self).body = status, body
        command = [str(self.java)]
        command += [str(DEPLOY / "HealthProbe.java")] if source_mode else ["-cp", self.temp.name, "HealthProbe"]
        result = subprocess.run(command, check=False, timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return result.returncode

    def test_real_source_file_launch(self) -> None:
        self.assertEqual(self.run_probe(200, b'{"status":"UP","stage":"SKELETON","businessReady":false}', True), 0)

    def test_accepts_reordered_whitespace_json(self) -> None:
        self.assertEqual(self.run_probe(200, b' { "businessReady" : false, "stage": "SKELETON", "status":"UP" } '), 0)

    def test_rejects_business_or_wrong_stage(self) -> None:
        for body in (b'{"status":"UP","stage":"SKELETON","businessReady":true}',
                     b'{"status":"UP","stage":"READY","businessReady":false}'):
            with self.subTest(body=body):
                self.assertNotEqual(self.run_probe(200, body), 0)

    def test_rejects_error_http_status(self) -> None:
        self.assertNotEqual(self.run_probe(503, b'{"status":"UP","stage":"SKELETON","businessReady":false}'), 0)

    def test_rejects_malformed_or_duplicate_fields(self) -> None:
        for body in (b'not JSON "status":"UP","stage":"SKELETON","businessReady":false',
                     b'{"status":"UP","status":"UP","businessReady":false}',
                     b'{"status":"U P","stage":"SKELETON","businessReady":false}',
                     b'{"status":"UP","stage":"SKELETON","businessReady":false,}'):
            with self.subTest(body=body):
                self.assertNotEqual(self.run_probe(200, body), 0)

    def test_rejects_oversized_response(self) -> None:
        self.assertNotEqual(self.run_probe(200, b" " * 4097), 0)


if __name__ == "__main__":
    unittest.main()
