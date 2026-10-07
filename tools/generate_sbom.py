#!/usr/bin/env python3

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


KNOWN_MAVEN_LICENSES = {
    ("androidx.appcompat", "appcompat"): "Apache-2.0",
    ("androidx.preference", "preference"): "Apache-2.0",
    ("com.google.android.material", "material"): "Apache-2.0",
    ("com.github.luben", "zstd-jni"): "BSD-2-Clause",
    ("org.apache.commons", "commons-compress"): "Apache-2.0",
    ("junit", "junit"): "EPL-1.0",
}


def license_list(value):
    return [{"license": {"id": value}}] if value else []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dependencies", required=True, type=Path)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--gradle-lock", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    dependencies = json.loads(args.dependencies.read_text(encoding="utf-8"))
    sources = json.loads(args.sources.read_text(encoding="utf-8"))
    components = []

    locked_maven = set()
    for line in args.gradle_lock.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate = line.split("=", 1)[0]
        parts = coordinate.split(":")
        if len(parts) != 3:
            raise RuntimeError(f"Unexpected Gradle lock coordinate: {coordinate}")
        locked_maven.add(tuple(parts))

    for group, artifact, version in sorted(locked_maven):
        purl = f"pkg:maven/{group}/{artifact}@{version}"
        component = {
            "type": "library",
            "bom-ref": purl,
            "name": f"{group}:{artifact}",
            "version": version,
            "purl": purl,
        }
        license_id = KNOWN_MAVEN_LICENSES.get((group, artifact))
        if license_id:
            component["licenses"] = license_list(license_id)
        components.append(component)

    for artifact in dependencies["artifacts"]:
        component = {
            "type": "file",
            "bom-ref": f"urn:winlator:dependency:{artifact['id']}",
            "name": artifact["id"],
            "hashes": [{"alg": "SHA-256", "content": artifact["sha256"]}],
            "externalReferences": [
                {"type": "distribution", "url": artifact["url"]}
            ],
            "properties": [
                {"name": "winlator:size", "value": str(artifact["size"])}
            ],
        }
        if artifact.get("license"):
            component["licenses"] = [{"license": {"name": artifact["license"]}}]
        components.append(component)

    for name, url, size, digest in sources["sources"]:
        components.append({
            "type": "file",
            "bom-ref": f"urn:winlator:source:{name}",
            "name": name,
            "hashes": [{"alg": "SHA-256", "content": digest}],
            "externalReferences": [{"type": "distribution", "url": url}],
            "properties": [{"name": "winlator:size", "value": str(size)}],
        })

    lock_digest = hashlib.sha256(args.dependencies.read_bytes()).hexdigest()
    source_digest = hashlib.sha256(args.sources.read_bytes()).hexdigest()
    bom = {
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": "urn:uuid:3da460c4-bef8-5d86-832f-1a67ef30b813",
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "component": {
                "type": "application",
                "bom-ref": "pkg:generic/winlator-secure@11.1-secure.81",
                "name": "Winlator Secure",
                "version": "11.1-secure.81",
                "licenses": license_list("LGPL-2.1-only"),
            },
            "properties": [
                {"name": "winlator:dependencies-lock-sha256", "value": lock_digest},
                {"name": "winlator:sources-lock-sha256", "value": source_digest},
                {
                    "name": "winlator:gradle-lock-sha256",
                    "value": hashlib.sha256(args.gradle_lock.read_bytes()).hexdigest(),
                },
                {"name": "winlator:upstream-revision", "value": "7e2699213a1e6282263706392ba6b644a2178e3b"},
            ],
        },
        "components": components,
    }
    args.output.write_text(json.dumps(bom, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
