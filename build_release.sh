#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

case "${1:-all}" in
  jar)
    python3 tools/build_release_third_party.py
    ;;
  renderer)
    RENDER_OUT="${RENDER_OUT:-$ROOT/release/maneuver_render}" ./compile_render_qnx.sh
    ;;
  hook)
    ./compile_hook.sh
    ;;
  all)
    python3 tools/build_release_third_party.py
    RENDER_OUT="${RENDER_OUT:-$ROOT/release/maneuver_render}" ./compile_render_qnx.sh
    ;;
  full)
    ./compile_hook.sh
    python3 tools/build_release_third_party.py
    RENDER_OUT="${RENDER_OUT:-$ROOT/release/maneuver_render}" ./compile_render_qnx.sh
    ;;
  verify)
    cd release
    sha256sum -c SHA256SUMS
    ;;
  *)
    echo "Usage: $0 [jar|renderer|hook|all|full|verify]" >&2
    exit 2
    ;;
esac
