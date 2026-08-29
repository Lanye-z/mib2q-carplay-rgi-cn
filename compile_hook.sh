#!/bin/bash
set -e

QNX_VM="${QNX_VM:-192.168.64.16}"
QNX_USER="${QNX_USER:-root}"
QNX_PASSWORD="${QNX_PASSWORD:-root}"
QNX_REMOTE_DIR="${QNX_REMOTE_DIR:-/tmp/carplay_hook}"
SSH_OPTS="${SSH_OPTS:--oHostKeyAlgorithms=+ssh-rsa -oPubkeyAcceptedAlgorithms=+ssh-rsa}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_HOOK_DIR="$SCRIPT_DIR/c_hook"
BUILD_DIR="${BUILD_DIR:-$SCRIPT_DIR/build}"
HOOK_DIR="$BUILD_DIR/c_hook-v38-amap"
OUT="${HOOK_OUT:-$BUILD_DIR/libcarplay_hook.so}"
OVERLAY="$SCRIPT_DIR/tools/apply_v38_amap_overlay.py"
mkdir -p "$(dirname "$OUT")"

for cmd in sshpass ssh scp tar python3; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: missing $cmd" >&2; exit 1; }
done
[ -f "$OVERLAY" ] || { echo "ERROR: missing $OVERLAY" >&2; exit 1; }

# Keep c_hook byte-for-byte based on the vehicle-tested main source.  Patch a
# disposable build copy with only the v38 Amap physical-head metadata fields.
rm -rf "$HOOK_DIR"
mkdir -p "$HOOK_DIR"
cp -R "$BASE_HOOK_DIR"/. "$HOOK_DIR"/
python3 "$OVERLAY" --native "$HOOK_DIR/routeguidance/rgd_hook.c"

FRAMEWORK_SRCS="framework/logging.c framework/bus.c framework/iap2_protocol.c framework/hook_framework.c"
RGD_SRCS="routeguidance/rgd_tlv.c routeguidance/rgd_hook.c"
CVR_SRCS="coverart/coverart_hook.c"
MAIN_SRCS="main.c"

EXTRA_CFLAGS="-D__QNX__"
EXTRA_LIBS="-lz -lsocket"
LOG="${LOG:-1}"
LOG_RGD_PACKET_RAW="${LOG_RGD_PACKET_RAW:-0}"

case "$LOG" in 0|1) ;; *) echo "Invalid LOG value: $LOG" >&2; exit 1;; esac
case "$LOG_RGD_PACKET_RAW" in 0|1) ;; *) echo "Invalid LOG_RGD_PACKET_RAW value: $LOG_RGD_PACKET_RAW" >&2; exit 1;; esac
if [ "$LOG" = "0" ]; then EXTRA_CFLAGS="$EXTRA_CFLAGS -DENABLE_LOGGING=0"; fi
if [ "$LOG_RGD_PACKET_RAW" = "1" ]; then
    [ "$LOG" = "1" ] || { echo "LOG_RGD_PACKET_RAW=1 requires LOG=1" >&2; exit 1; }
    EXTRA_CFLAGS="$EXTRA_CFLAGS -DRGD_TRACE_RAW_FULL=1"
fi

REMOTE="$QNX_USER@$QNX_VM"
SSH=(sshpass -p "$QNX_PASSWORD" ssh $SSH_OPTS "$REMOTE")
SCP=(sshpass -p "$QNX_PASSWORD" scp $SSH_OPTS)

echo "=== CarPlay Hook Build (main + v38 Amap metadata) ==="
echo "QNX host: $REMOTE"
echo "Remote directory: $QNX_REMOTE_DIR"
echo "Output: $OUT"

tar --disable-copyfile --format=ustar -C "$HOOK_DIR" -cf - framework routeguidance coverart main.c \
    | "${SSH[@]}" "rm -rf '$QNX_REMOTE_DIR' && mkdir -p '$QNX_REMOTE_DIR' && tar -xf - -C '$QNX_REMOTE_DIR'"

ALL_SRCS=""
for f in $FRAMEWORK_SRCS $RGD_SRCS $CVR_SRCS $MAIN_SRCS; do
    ALL_SRCS="$ALL_SRCS $QNX_REMOTE_DIR/$f"
done
BUILD_CMD="/usr/qnx650/host/qnx6/x86/usr/bin/ntoarmv7-gcc -shared -fPIC -O2 -std=gnu99 -fdata-sections -ffunction-sections $EXTRA_CFLAGS -I$QNX_REMOTE_DIR $ALL_SRCS -o $QNX_REMOTE_DIR/libcarplay_hook.so -Wl,--gc-sections $EXTRA_LIBS"

"${SSH[@]}" "export QNX_HOST=/usr/qnx650/host/qnx6/x86; export QNX_TARGET=/usr/qnx650/target/qnx6; cd '$QNX_REMOTE_DIR' && $BUILD_CMD"
"${SCP[@]}" "$REMOTE:$QNX_REMOTE_DIR/libcarplay_hook.so" "$OUT"
chmod +x "$OUT"
sha256sum "$OUT"