#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

build_jar() {
  mkdir -p "$ROOT/release"
  JAVA_OUTPUT="$ROOT/release/carplay_hook.jar" ./build_java.sh
}

build_renderer() {
  RENDER_OUT="${RENDER_OUT:-$ROOT/release/maneuver_render}" ./compile_render_qnx.sh
}

write_sums() {
  local files=()
  [ -f "$ROOT/release/carplay_hook.jar" ] && files+=(carplay_hook.jar)
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
    ./compile_hook.sh
    ;;
  all)
    build_jar
    build_renderer
    write_sums
    ;;
  full)
    ./compile_hook.sh
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
