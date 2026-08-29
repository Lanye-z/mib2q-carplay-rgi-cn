#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

build_jar() {
  mkdir -p "$ROOT/release"
  JAVA_OUTPUT="$ROOT/release/carplay_hook.jar" ./build_java.sh
}

build_hook() {
  mkdir -p "$ROOT/release"
  HOOK_OUT="$ROOT/release/libcarplay_hook.so" ./compile_hook.sh
}

build_renderer() {
  RENDER_OUT="${RENDER_OUT:-$ROOT/release/maneuver_render}" ./compile_render_qnx.sh
}

write_sums() {
  local files=()
  [ -f "$ROOT/release/carplay_hook.jar" ] && files+=(carplay_hook.jar)
  [ -f "$ROOT/release/libcarplay_hook.so" ] && files+=(libcarplay_hook.so)
  [ -f "$ROOT/release/maneuver_render" ] && files+=(maneuver_render)
  if [ ${#files[@]} -gt 0 ]; then
    (cd "$ROOT/release" && sha256sum "${files[@]}" > SHA256SUMS)
  fi
}

case "${1:-all}" in
  jar)
    build_jar
    write_sums
    ;;
  renderer)
    build_renderer
    write_sums
    ;;
  hook)
    build_hook
    write_sums
    ;;
  all)
    build_jar
    build_renderer
    write_sums
    ;;
  full)
    # Full is the intended deployment build for this branch: the JAR consumes
    # the v38 metadata published by the newly built main-based native hook.
    build_hook
    build_jar
    build_renderer
    write_sums
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