#!/usr/bin/env python3
"""Build the legacy release JAR with only the two generic third-party nav fixes.

The original build_release.py is intentionally left untouched. This wrapper
copies its required inputs to a temporary workspace, applies the generic Luka
overlay there, runs the original release builder, then copies the resulting JAR
and checksum file back. The tracked legacy Java sources are never modified.
"""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ROUTE_REL = Path("java_patch/com/luka/carplay/routeguidance/RouteGuidance.java")
BAP_REL = Path("java_patch/com/luka/carplay/routeguidance/BAPBridge.java")


def copy_file(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(src), str(dst))


def main():
    with tempfile.TemporaryDirectory(prefix="carplay-third-party-release-") as tmp:
        work = Path(tmp)

        required = [
            Path("tools/build_release.py"),
            Path("tools/apply_third_party_nav_overlay.py"),
            Path("build_assets/carplay_hook_base.jar"),
            ROUTE_REL,
            BAP_REL,
        ]
        for rel in required:
            src = ROOT / rel
            if not src.exists():
                raise RuntimeError("missing required build input: %s" % src)
            copy_file(src, work / rel)

        # Preserve the legacy checksum behavior when a renderer already exists.
        renderer = ROOT / "release/maneuver_render"
        if renderer.exists():
            copy_file(renderer, work / "release/maneuver_render")

        subprocess.run([
            sys.executable,
            str(work / "tools/apply_third_party_nav_overlay.py"),
            "--route-guidance", str(work / ROUTE_REL),
            "--bap-bridge", str(work / BAP_REL),
        ], check=True)

        subprocess.run([
            sys.executable,
            str(work / "tools/build_release.py"),
        ], check=True)

        release = ROOT / "release"
        release.mkdir(parents=True, exist_ok=True)
        copy_file(work / "release/carplay_hook.jar", release / "carplay_hook.jar")
        copy_file(work / "release/SHA256SUMS", release / "SHA256SUMS")

    print("Built legacy release with generic third-party navigation compatibility")


if __name__ == "__main__":
    main()
