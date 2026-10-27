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

step "every icon alias"
ALIASES="LauncherBitChord LauncherGlassLight LauncherGlassDark LauncherGlassClear LauncherGlassClearDark LauncherSamsungGlass LauncherSamsungGlassDark LauncherSamsungGlassClear LauncherSamsungGlassClearDark LauncherPixelGlass LauncherPixelGlassDark LauncherPixelGlassClear LauncherPixelGlassClearDark LauncherSquare"
prev=LauncherSquare
for a in $ALIASES; do
  # What AppIcon.apply does: the new one on, then the previous one off.
  adb shell pm enable "$PKG/dev.lelonio.square.$a" > /dev/null
  adb shell pm disable "$PKG/dev.lelonio.square.$prev" > /dev/null 2>&1 || true
  entries=$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -c "$PKG")
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  if [ "$entries" = "1" ] && sleep 6 && adb shell pidof "$PKG" > /dev/null; then echo "OK   icon $a"; else echo "FAIL icon $a (launcher entries: $entries)"; fail=1; fi
  prev=$a
done
# Back on Square: the manifest default.
adb shell pm enable "$PKG/dev.lelonio.square.LauncherSquare" > /dev/null

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
