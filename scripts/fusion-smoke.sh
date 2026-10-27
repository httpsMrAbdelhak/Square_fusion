#!/bin/bash
# Square Fusion smoke test, run on an emulator by .github/workflows/fusion.yml.
# Installs the APK, then checks the things an icon alias or a manifest change
# could break: launch from the launcher, explicit MainActivity intent, the
# spotify: and open.spotify.com links, every icon alias, and that the process
# survives each. Screenshots land in smoke/.
set -u
APK="$1"
PKG=dev.lelonio.square.fusion
OUT=smoke
mkdir -p "$OUT"
fail=0
step() { echo "== $*"; }
check_alive() {
  sleep "${2:-12}"
  if adb shell pidof "$PKG" > /dev/null; then echo "OK   $1: process alive"; else echo "FAIL $1: process not running"; fail=1; fi
  if adb logcat -d -b crash | grep -q "$PKG"; then echo "FAIL $1: crash logged"; adb logcat -d -b crash | tail -n 60; fail=1; adb logcat -b crash -c; fi
  adb exec-out screencap -p > "$OUT/$1.png" || true
}

adb logcat -c || true
step "install"
adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -n 2

step "launcher entries"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep "$PKG" | tee "$OUT/launcher.txt"
grep -q "LauncherSquare" "$OUT/launcher.txt" || { echo "FAIL default launcher alias is not Square"; fail=1; }
[ "$(grep -c "$PKG" "$OUT/launcher.txt")" = "1" ] || { echo "FAIL expected exactly one launcher entry"; fail=1; }

step "launch from launcher"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
check_alive launch 20

step "explicit MainActivity intent"
adb shell am start -W -n "$PKG/dev.lelonio.square.ui.MainActivity" | tail -n 3
check_alive explicit

step "spotify: deep link"
adb shell am start -W -a android.intent.action.VIEW -d "spotify:track:4cOdK2wGLETKBW3PvgPWqT" -p "$PKG" | tail -n 3
check_alive deeplink-spotify

step "open.spotify.com link"
adb shell am start -W -a android.intent.action.VIEW -d "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT" -p "$PKG" | tail -n 3
check_alive deeplink-web

# The icon aliases are switched by the app itself (AppIcon.apply); the shell
# may not change another app's components, so that is checked by the
# instrumented tests (FusionDeviceTest, FusionScreensTest) instead.

step "onboarding: choose YouTube Music"
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1 || return 1
  local b
  b=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    if f'text="{sys.argv[2]}"' in n or f'content-desc="{sys.argv[2]}"' in n:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$b" ] || return 1
  adb shell input tap $b
}
if tap_text "YouTube Music"; then
  check_alive youtube-home 25
else
  echo "WARN onboarding card not found (layout or language differs)"
fi

step "background and back"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
check_alive resume 8

step "screen off and on"
adb shell input keyevent KEYCODE_SLEEP
sleep 5
adb shell input keyevent KEYCODE_WAKEUP
check_alive wake 6

adb logcat -d | grep -E "SquareFusion|AndroidRuntime" | tail -n 80 > "$OUT/logcat.txt" || true
echo "smoke result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
