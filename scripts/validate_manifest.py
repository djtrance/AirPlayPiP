#!/usr/bin/env python3
"""Catch taskAffinity values accepted by aapt/lint but rejected by Android's package parser."""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def valid_task_affinity(value):
    if not value:
        return True  # Empty affinity explicitly opts out of a task affinity.
    private = value.startswith(":")
    name = value[1:] if private else value
    # Conservative package-style names; '-' is not legal here even though it's a legal filename.
    # AOSP: ComponentParseUtils.buildTaskAffinityName -> buildCompoundName -> validateName.
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*", name):
        return False
    return private or "." in name or name == "system"


def validate_manifest(path, application_id="io.github.airplaypip"):
    errors = []
    for element in ET.parse(path).getroot().iter():
        affinity = element.get(ANDROID + "taskAffinity")
        if affinity is None:
            continue
        resolved = affinity.replace("${applicationId}", application_id)
        if not valid_task_affinity(resolved):
            component = element.get(ANDROID + "name", element.tag)
            errors.append(f"{component}: invalid android:taskAffinity={resolved!r}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?", type=Path, default=ROOT / "app/src/main/AndroidManifest.xml")
    parser.add_argument("--application-id", default="io.github.airplaypip")
    args = parser.parse_args()
    errors = validate_manifest(args.manifest, args.application_id)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Manifest task affinities valid: {args.manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
