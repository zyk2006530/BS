"""Smoke-test the packaged skeleton on loopback, then stop it; not business acceptance."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
import zipfile


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    jar = root / "backend/target/approval-backend-0.1.0-SNAPSHOT.jar"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / (
        "java.exe" if os.name == "nt" else "java"
    )
    with zipfile.ZipFile(jar) as artifact:
        manifest = artifact.read("META-INF/MANIFEST.MF").decode("utf-8")
        assert "Main-Class: org.springframework.boot.loader.launch.JarLauncher" in manifest
        assert "Start-Class: cn.edu.approval.ApprovalApplication" in manifest
        assert "BOOT-INF/classes/application.yml" in artifact.namelist()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    url = f"http://127.0.0.1:{port}"
    log_path = root / ".local/task-01-jar-smoke.log"
    log_path.parent.mkdir(exist_ok=True)
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            [str(java), "-jar", str(jar), "--server.address=127.0.0.1", f"--server.port={port}"],
            cwd=root, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Artifact exited before readiness; inspect {log_path}")
                try:
                    with urllib.request.urlopen(url + "/api/v1/health", timeout=1) as response:
                        assert response.status == 200
                        assert json.load(response) == {
                            "status": "UP", "stage": "SKELETON", "businessReady": False,
                        }
                        assert "no-store" in response.headers["Cache-Control"]
                        assert response.headers.get("Set-Cookie") is None
                    break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f"Artifact readiness timeout; inspect {log_path}")
                    time.sleep(0.2)
            cases = [
                ("GET", "/api/v1/purchases", 401),
                ("POST", "/api/v1/health", 403),
            ]
            for method, path, expected in cases:
                request = urllib.request.Request(url + path, method=method)
                try:
                    with urllib.request.urlopen(request, timeout=3):
                        raise AssertionError(f"Unexpected public endpoint: {method} {path}")
                except urllib.error.HTTPError as error:
                    assert error.code == expected, (method, path, error.code)
                    error.close()
            print("PASS: executable jar, health=200, private GET=401, POST health=403; businessReady=false")
            with jar.open("rb") as artifact_file:
                print("artifact SHA-256:", hashlib.file_digest(artifact_file, "sha256").hexdigest())
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == "__main__":
    main()
