#!/usr/bin/env bash
# Optional end-to-end test of the Android app: nested Hyprland + `stance deck serve`
# + an emulator (adb reverse), driven through uiautomator. Covers pairing, a layout
# button, the Keep / Revert banner and the automatic revert after the timeout.
#
#   scripts/e2e.sh [path/to/stance]   (default: $STANCE_BIN, else stance on PATH)
#
# Needs: Hyprland on a Wayland session (the nested compositor opens a window), python3,
# the Android SDK (adb + emulator) with an AVD (default name "stance34", override with
# STANCE_AVD; create it from a system image such as android-34 aosp_atd x86_64), and a
# built debug APK (`./gradlew :app:assembleDebug`). Skips cleanly (exit 0)
# when any of that is missing. Safe by construction: own Hyprland config/state/deck
# directories, loopback-only server, the layout buttons only touch headless outputs of
# the nested compositor. Never presses brightness or audio buttons. Tap coordinates assume
# a 1080x2400 screen (AVD device pixel_6).
set -u
ARGBIN=${1:-}
cd "$(dirname "$0")/.."
ROOT=$PWD
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/Android/avd}
AVD=${STANCE_AVD:-stance34}
APK=app/build/outputs/apk/debug/app-debug.apk
skip() { echo "skip: $1"; exit 0; }
[ -x "$ANDROID_HOME/platform-tools/adb" ] || skip "no adb in \$ANDROID_HOME/platform-tools"
[ -x "$ANDROID_HOME/emulator/emulator" ] || skip "no emulator in \$ANDROID_HOME/emulator"
"$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | grep -qx "$AVD" || skip "no AVD named $AVD (set STANCE_AVD)"
[ -f "$APK" ] || skip "no debug APK (./gradlew :app:assembleDebug)"
[ -n "${WAYLAND_DISPLAY:-}" ] && command -v Hyprland >/dev/null || skip "needs Hyprland and a running Wayland session"
[ -z "$($ANDROID_HOME/platform-tools/adb devices | sed 1d | grep -w device)" ] || skip "an Android device or emulator is already attached; detach it first"
PARSER=lua
BIN=$(realpath "${ARGBIN:-${STANCE_BIN:-$(command -v stance)}}")
WORK=$(mktemp -d "${TMPDIR:-/tmp}/stance-nested.XXXXXX")
chmod 700 "$WORK"
cat > "$WORK/ui.py" <<'PYEOF'
#!/usr/bin/env python3
import subprocess,sys,re,time,os
ADB=[os.environ.get('ADB','adb')]
def sh(*a): return subprocess.run(ADB+list(a),capture_output=True,text=True).stdout
def dump():
    sh('shell','uiautomator','dump','/sdcard/u.xml')
    return sh('shell','cat','/sdcard/u.xml')
def nodes():
    x=dump()
    out=[]
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
        t,d,a,b,c,e=m.groups(); out.append((t or d,(int(a)+int(c))//2,(int(b)+int(e))//2))
    return out
def texts(): return [n[0] for n in nodes() if n[0]]
def tap(label):
    for t,x,y in nodes():
        if (label[1:]==t if label.startswith("=") else label in t):
            sh('shell','input','tap',str(x),str(y)); return True
    return False
if __name__=='__main__':
    c=sys.argv[1]
    if c=='texts': print('\n'.join(texts()))
    elif c=='tap': print(tap(sys.argv[2]))
    elif c=='type': sh('shell','input','text',sys.argv[2])
PYEOF
mkdir -m 700 -p "$WORK/cfg/hypr" "$WORK/state"
export XDG_CONFIG_HOME=$WORK/cfg XDG_STATE_HOME=$WORK/state
unset STANCE_STATE_DIR

if [ "$PARSER" = legacy ]; then
  v=$(Hyprland --version-json 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])" 2>/dev/null)
  if python3 -c "import sys; v=tuple(map(int,'${v:-0.0}'.split('.')[:2])); sys.exit(0 if v>=(0,57) else 1)"; then
    echo "skip: Hyprland $v removed hyprlang configs (legacy path needs 0.55/0.56)"; exit 0
  fi
fi

if [ "$PARSER" = lua ]; then
  CONF=$WORK/hyprland.lua
  cat > "$CONF" <<'EOF'
hl.config({ misc = { disable_hyprland_logo = true, disable_splash_rendering = true }, animations = { enabled = true }, decoration = { blur = { enabled = true } } })
EOF
else
  CONF=$WORK/hyprland.conf
  cat > "$CONF" <<'EOF'
misc {
  disable_hyprland_logo = true
  disable_splash_rendering = true
}
animations {
  enabled = true
}
decoration {
  blur {
    enabled = true
  }
}
EOF
fi

before=$(ls "$XDG_RUNTIME_DIR/hypr" 2>/dev/null | sort)
wl_before=$(ls "$XDG_RUNTIME_DIR" | grep -E '^wayland-[0-9]+$' | sort)
Hyprland -c "$CONF" >"$WORK/hyprland.out" 2>&1 &
HPID=$!
# Only hyprsunsets whose environment carries the NESTED instance signature
# (the user's own one has the live signature and is never matched).
nested_sunsets() {
  local p
  for p in /proc/[0-9]*; do
    [ "$(cat "$p/comm" 2>/dev/null)" = hyprsunset ] || continue
    tr '\0' '\n' < "$p/environ" 2>/dev/null | grep -qx "HYPRLAND_INSTANCE_SIGNATURE=${SIG:-none}" && echo "${p#/proc/}"
  done
}
kill_sunsets() { local p; for p in $(nested_sunsets); do kill "$p" 2>/dev/null; done; sleep 0.3; }
cleanup() { kill_sunsets; kill "$HPID" 2>/dev/null; wait "$HPID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

SIG=
for _ in $(seq 50); do
  sleep 0.2
  SIG=$(comm -13 <(echo "$before") <(ls "$XDG_RUNTIME_DIR/hypr" | sort) | head -1)
  [ -n "$SIG" ] && [ -S "$XDG_RUNTIME_DIR/hypr/$SIG/.socket.sock" ] && break
done
[ -n "$SIG" ] || { echo "nested Hyprland did not start"; cat "$WORK/hyprland.out"; exit 1; }
export HYPRLAND_INSTANCE_SIGNATURE=$SIG
NWL=$(comm -13 <(echo "$wl_before") <(ls "$XDG_RUNTIME_DIR" | grep -E '^wayland-[0-9]+$' | sort) | head -1)
sleep 1

hc() { python3 - "$@" <<'EOF'
import os, socket, sys
p = f"{os.environ['XDG_RUNTIME_DIR']}/hypr/{os.environ['HYPRLAND_INSTANCE_SIGNATURE']}/.socket.sock"
s = socket.socket(socket.AF_UNIX); s.connect(p); s.sendall(" ".join(sys.argv[1:]).encode()); s.shutdown(socket.SHUT_WR)
d = b""
while (c := s.recv(4096)): d += c
print(d.decode())
EOF
}
# Headless outputs render on a timer, so rule changes always land. The
# nested window itself may be hidden and never repaint.
for o in TEST-1 TEST-2 ANCHOR-1; do hc output create headless "$o" >/dev/null; done
sleep 0.5


ok() { echo "  ok   $1"; }
FAILN=0
bad() { FAILN=$((FAILN+1)); echo "  FAIL $1"; [ -n "${2:-}" ] && echo "       $2"; }
export ANDROID_HOME=$HOME/Android/sdk ANDROID_SDK_ROOT=$HOME/Android/sdk ANDROID_AVD_HOME=$HOME/Android/avd
export ADB=$ANDROID_HOME/platform-tools/adb
EMU_PID=
cleanup2() { [ -n "$EMU_PID" ] && { $ADB emu kill >/dev/null 2>&1; sleep 3; }; [ -n "${DECK:-}" ] && kill $DECK 2>/dev/null; cleanup; }
trap cleanup2 EXIT
run() { "$BIN" "$@" >"$WORK/last.out" 2>&1; }
ylt() { "$BIN" monitors --json | python3 -c "
import json,sys
m={x['name']:x for x in json.load(sys.stdin)['data']}
print(m['TEST-2']['y']>m['TEST-1']['y'] if sys.argv[1]=='below' else m['TEST-2']['y']<m['TEST-1']['y'])" "$1"; }
pending() { "$BIN" status --json | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['pending'] is not None)"; }

run set ANCHOR-1 --pos 0x0 --no-confirm
run set TEST-1 --pos 1920x0 --no-confirm
run set TEST-2 --pos 3840x0 --no-confirm
export STANCE_DECK_DIR=$WORK/deck; mkdir -m 700 -p "$STANCE_DECK_DIR"; mkdir -p "$XDG_CONFIG_HOME/stance"
cat > "$XDG_CONFIG_HOME/stance/deck.toml" <<'EOT'
[[button]]
id = "below"
label = "TEST-2 below"
action = "layout"
kind = "below"
monitor = "TEST-2"
reference = "TEST-1"

[[button]]
id = "above"
label = "TEST-2 above"
action = "layout"
kind = "above"
monitor = "TEST-2"
reference = "TEST-1"

[[button]]
id = "keep"
label = "Keep it"
action = "confirm"
EOT
chmod 600 "$XDG_CONFIG_HOME/stance/deck.toml"
WAYLAND_DISPLAY=$NWL "$BIN" deck serve --loopback --port 0 2>"$WORK/deck.log" & DECK=$!
sleep 1
PORT=$(grep -o '127.0.0.1:[0-9]*' "$WORK/deck.log" | head -1 | cut -d: -f2)
[ -n "$PORT" ] && ok "deck server on $PORT" || { bad "deck server" "$(cat $WORK/deck.log)"; exit 1; }

echo "boot emulator"
(setsid $ANDROID_HOME/emulator/emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot > $WORK/emu.log 2>&1 &)
EMU_PID=1
for i in $(seq 80); do [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
$ADB reverse tcp:$PORT tcp:$PORT >/dev/null
$ADB uninstall dev.stance.deck >/dev/null 2>&1
$ADB install -r "$ROOT/$APK" >/dev/null
$ADB shell pm grant dev.stance.deck android.permission.CAMERA
$ADB shell am start -n dev.stance.deck/.MainActivity >/dev/null; sleep 4
UI="python3 $WORK/ui.py"
$ADB shell input tap 881 2284; sleep 2
PAY=$("$BIN" deck pair --json | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['payload'])")
$ADB shell input tap 500 2274; sleep 1
$ADB shell input text "$(echo "$PAY" | sed 's/&/\\\&/g')"; sleep 1
$UI tap "Use code" >/dev/null; sleep 2
$UI tap "=Pair" >/dev/null; sleep 5
T=$($UI texts)
echo "$T" | grep -q "TEST-2 below" && ok "app paired and shows the layout button" || bad "app shows buttons" "$T"

press() { $UI tap "$1" >/dev/null; }
wait_banner() { for i in $(seq 20); do $UI texts | grep -q "Keep this display setting" && return 0; sleep 0.5; done; return 1; }

# 1. layout below -> banner -> Keep
press "TEST-2 below"
wait_banner && ok "banner appears" || bad "banner appears" "$($UI texts)"
$UI texts | grep -q "reverts in" && ok "banner has a countdown" || bad "banner countdown"
[ "$(pending)" = True ] && ok "stance status: pending" || bad "stance status: pending"
$UI tap "=Keep" >/dev/null; sleep 2
[ "$(pending)" = False ] && ok "Keep cleared pending" || bad "Keep cleared pending"
[ "$(ylt below)" = True ] && ok "layout below stayed" || bad "layout below stayed"
$UI texts | grep -q "Keep this display setting" && bad "banner gone after Keep" || ok "banner gone after Keep"

# 2. layout above -> banner -> Revert
press "TEST-2 above"
wait_banner && ok "banner again" || bad "banner again"
[ "$(ylt above)" = True ] && ok "layout above applied" || bad "layout above applied"
$UI tap "=Revert" >/dev/null; sleep 3
[ "$(pending)" = False ] && ok "Revert cleared pending" || bad "Revert cleared pending"
[ "$(ylt below)" = True ] && ok "Revert restored below" || bad "Revert restored below"

# 3. layout above -> wait for the automatic revert
press "TEST-2 above"
wait_banner && ok "banner third time" || bad "banner third time"
sleep 16
[ "$(pending)" = False ] && ok "timeout cleared pending" || bad "timeout cleared pending"
[ "$(ylt below)" = True ] && ok "timeout restored below" || bad "timeout restored below"
$UI texts | grep -q "Keep this display setting" && bad "banner gone after timeout" || ok "banner gone after timeout"

echo "FAILED=$FAILN"
exit $FAILN
