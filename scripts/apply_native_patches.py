#!/usr/bin/env python3
"""Apply the entire UxPlay patch stack atomically, never resetting developer changes."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(directory, *args, **kwargs):
    return subprocess.run(
        ["git", "-C", str(directory), *args], capture_output=True, check=True, **kwargs
    ).stdout


def apply_stack(source, patches):
    # Later patches edit earlier patches' lines. Reversing them individually is not idempotent.
    # Build one final diff in an isolated checkout, then check/apply it in one operation.
    with tempfile.TemporaryDirectory(prefix=".airplay-patches-", dir=source.parent) as temporary:
        scratch = Path(temporary) / "UxPlay"
        subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(source), str(scratch)],
                       check=True, capture_output=True)
        run(scratch, "checkout", "--quiet", "--detach", "HEAD")
        for patch in patches:
            run(scratch, "apply", "--unidiff-zero", str(patch.resolve()))
        combined = run(scratch, "diff", "--binary", "HEAD")
        if not combined:
            return
        reverse = subprocess.run(["git", "-C", str(source), "apply", "--reverse", "--check", "-"],
                                 input=combined, capture_output=True)
        if reverse.returncode == 0:
            print("UxPlay patch stack already applied; local changes preserved.")
            return
        run(source, "apply", "--check", "-", input=combined)
        run(source, "apply", "-", input=combined)
        print(f"Applied {len(patches)} UxPlay patches atomically.")


def main():
    source = ROOT / "app/src/main/cpp/third_party/UxPlay"
    lock = json.loads((ROOT / "native-dependencies.json").read_text())
    expected = next(d["commit"] for d in lock["dependencies"] if d["name"] == "UxPlay")
    if run(source, "rev-parse", "HEAD").decode().strip() != expected:
        raise RuntimeError("Unexpected UxPlay revision. Run scripts/bootstrap_native.py first.")
    patches = sorted((ROOT / "app/src/main/cpp/patches/UxPlay").glob("*.patch"))
    apply_stack(source, patches)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Patch application failed (no reset performed): {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr.decode(errors="replace"), file=sys.stderr)
        sys.exit(1)
