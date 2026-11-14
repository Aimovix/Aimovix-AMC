#!/usr/bin/env python3
"""Checks the local model catalog against the Hugging Face Hub and fills missing checksums.

    python scripts/verify_model_catalog.py            # check only (exit 1 on any problem)
    python scripts/verify_model_catalog.py --write    # also fill empty sha256 / size fields

Checked for every entry: https URL on huggingface.co in the form /<owner>/<repo>/resolve/main/<file>,
the file exists on the Hub, its size matches size_bytes, and sha256 (when set) matches the Hub's
LFS object id (the SHA-256 of the file). Both catalog copies must be identical.
"""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOGS = [ROOT / "android/app/src/main/assets/model_catalog.json", ROOT / "termux-bridge/model_catalog.json"]
URL_PATTERN = re.compile(r"^https://huggingface\.co/([\w.\-]+)/([\w.\-]+)/resolve/main/([\w.\-]+\.gguf)$")
REQUIRED = ("id", "title", "file", "url", "sha256", "size_bytes", "size_mb", "min_ram_mb", "ctx", "tier")


def hub_files(owner: str, repo: str) -> dict:
    """{path: {"size": int, "sha256": str}} for the repo's top-level files."""
    url = f"https://huggingface.co/api/models/{owner}/{repo}/tree/main"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "amc-catalog-check"}), timeout=30) as r:
        entries = json.load(r)
    files = {}
    for e in entries:
        if e.get("type") != "file":
            continue
        lfs = e.get("lfs") or {}
        files[e["path"]] = {"size": lfs.get("size", e.get("size")), "sha256": lfs.get("oid", "")}
    return files


def check(catalog: dict, fetch=hub_files, write: bool = False) -> list:
    """Returns the problems found; with write=True fills empty sha256 and size fields in place."""
    problems, cache = [], {}
    for m in catalog.get("models", []):
        name = m.get("id", "?")
        missing = [k for k in REQUIRED if k not in m]
        if missing:
            problems.append(f"{name}: missing fields {missing}")
            continue
        match = URL_PATTERN.match(m["url"])
        if not match:
            problems.append(f"{name}: URL must be https://huggingface.co/<owner>/<repo>/resolve/main/<file>.gguf")
            continue
        owner, repo, file = match.groups()
        if file != m["file"]:
            problems.append(f"{name}: file '{m['file']}' does not match the URL ('{file}')")
            continue
        key = (owner, repo)
        if key not in cache:
            try:
                cache[key] = fetch(owner, repo)
            except Exception as error:  # network, 404
                problems.append(f"{name}: cannot list {owner}/{repo} on the Hub ({error})")
                cache[key] = None
        files = cache[key]
        if files is None:
            continue
        hub = files.get(file)
        if hub is None:
            problems.append(f"{name}: {file} does not exist in {owner}/{repo}")
            continue
        if write and not m["size_bytes"]:
            m["size_bytes"] = hub["size"]
        if m["size_bytes"] != hub["size"]:
            problems.append(f"{name}: size_bytes {m['size_bytes']} != Hub size {hub['size']}")
        expected_mb = -(-int(hub["size"]) // (1024 * 1024))
        if write:
            m["size_mb"] = expected_mb
        elif m["size_mb"] != expected_mb:
            problems.append(f"{name}: size_mb {m['size_mb']} != {expected_mb}")
        if not m["sha256"]:
            if write and re.fullmatch(r"[0-9a-f]{64}", hub["sha256"] or ""):
                m["sha256"] = hub["sha256"]
            else:
                print(f"note: {name} has no sha256 yet (Hub: {hub['sha256'] or 'unknown'})")
        elif m["sha256"].lower() != (hub["sha256"] or "").lower():
            problems.append(f"{name}: sha256 does not match the Hub")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="fill empty sha256/size fields in both catalogs")
    args = parser.parse_args()
    texts = [p.read_text(encoding="utf-8") for p in CATALOGS]
    if texts[0] != texts[1]:
        print("error: the two catalog copies differ:", *CATALOGS, sep="\n  ")
        return 1
    catalog = json.loads(texts[0])
    problems = check(catalog, write=args.write)
    for p in problems:
        print("error:", p)
    if args.write and not problems:
        text = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
        for p in CATALOGS:
            p.write_text(text, encoding="utf-8")
    print(f"{len(catalog.get('models', []))} models checked, {len(problems)} problems.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
