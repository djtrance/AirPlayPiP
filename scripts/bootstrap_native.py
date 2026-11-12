#!/usr/bin/env python3
"""Fetch pinned native sources without requiring a root Git repo or discarding edits."""

import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def git(directory, *args):
    return subprocess.check_output(
        ["git", "-C", str(directory), *args], text=True, stderr=subprocess.STDOUT
    ).strip()


def ensure_dependency(base, dependency):
    name, commit = dependency["name"], dependency["commit"]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        raise ValueError(f"Invalid dependency name: {name}")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError(f"Expected a full commit SHA for {name}")
    path = base / name
    if (path / ".git").exists():
        try:
            head = git(path, "rev-parse", "HEAD")
        except subprocess.CalledProcessError:
            head = None  # An interrupted initial fetch is safe to retry.
        if head == commit:
            print(f"{name}: pinned commit already present (local edits preserved)")
            return
        if head is not None:
            raise RuntimeError(f"{path} is at {head}, expected {commit}. Refusing to overwrite it.")
    else:
        if path.exists() and any(path.iterdir()):
            raise RuntimeError(f"{path} contains files but is not a Git checkout. Refusing to overwrite it.")
        path.mkdir(parents=True, exist_ok=True)
        git(path, "init", "--quiet")
    print(f"Fetching {name} @ {commit}", flush=True)
    git(path, "fetch", "--depth", "1", dependency["url"], commit)
    git(path, "checkout", "--detach", "FETCH_HEAD")
    if git(path, "rev-parse", "HEAD") != commit:
        raise RuntimeError(f"Unexpected commit for {name}")


def main():
    lock = json.loads((ROOT / "native-dependencies.json").read_text())
    base = ROOT / "app/src/main/cpp/third_party"
    for dependency in lock["dependencies"]:
        ensure_dependency(base, dependency)
    print("Native sources ready. Gradle applies the local UxPlay patches during CMake configuration.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Bootstrap failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.output, file=sys.stderr)
        sys.exit(1)
