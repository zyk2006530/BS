"""Verify locked official OCI indexes. Does not pull layers or run Docker.

Default is read-only network verification. --capture also preserves exact response
bytes and metadata under deploy/ for later offline digest regression tests.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

DEPLOY = Path(__file__).resolve().parent
ACCEPT = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json"
INDEX_TYPES = set(ACCEPT.split(", "))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", action="store_true")
    args = parser.parse_args()
    lock_path = DEPLOY / "image-lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    captures = []
    for entry in lock["checks"]:
        repository = entry["repository"]
        tag = entry["image"].split(":", 1)[1]
        if entry["registry"] != "registry-1.docker.io" or not repository.startswith("library/"):
            raise ValueError("Only the reviewed official Docker registry repositories are allowed")
        query = urlencode({"service": "registry.docker.io", "scope": f"repository:{repository}:pull"})
        with urlopen("https://auth.docker.io/token?" + query, timeout=20) as response:
            token = json.load(response)["token"]
        url = f"https://registry-1.docker.io/v2/{repository}/manifests/{tag}"
        request = Request(url, headers={"Authorization": "Bearer " + token, "Accept": ACCEPT})
        with urlopen(request, timeout=20) as response:
            raw = response.read()
            reported = response.headers.get("Docker-Content-Digest")
            status = response.status
        computed = "sha256:" + hashlib.sha256(raw).hexdigest()
        payload = json.loads(raw)
        if status != 200 or computed != reported or computed != entry["digest"]:
            raise ValueError(f"Registry digest changed or is inconsistent for {entry['image']}")
        if payload.get("mediaType") not in INDEX_TYPES:
            raise ValueError("Expected a multi-platform index, not a single-platform manifest")
        platforms = [item.get("platform", {}) for item in payload["manifests"]]
        for architecture in ("amd64", "arm64"):
            if not any(p.get("os") == "linux" and p.get("architecture") == architecture for p in platforms):
                raise ValueError(f"Missing Linux {architecture} platform")
        relative = "registry/" + repository.replace("/", "-") + ".index.json"
        update = dict(entry, capturedAt=datetime.now(timezone.utc).isoformat(),
                      sourceUrl=url, manifestFile=relative, manifestBytes=len(raw),
                      dockerContentDigest=reported, httpStatus=status,
                      mediaType=payload["mediaType"], platforms=platforms)
        captures.append((update, raw))
        print(f"PASS {entry['image']}@{computed}; {len(raw)} bytes; HTTP {status}", flush=True)
    if args.capture:
        # Capture only after all three verifications succeed. Never stores bearer tokens.
        (DEPLOY / "registry").mkdir(exist_ok=True)
        for entry, raw in captures:
            (DEPLOY / entry["manifestFile"]).write_bytes(raw)
        lock["checks"] = [entry for entry, _ in captures]
        lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
