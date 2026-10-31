#!/usr/bin/env bash
# BitChord Glass on an emulator: install, walk the parts the fork changed and
# the BitChord features around them, and record what happened. Every step
# logs PASS/FAIL/INFO; screenshots and UI dumps go to glass-test/.
set -u
APK="$1"
PKG=com.music.bitchord.glass
OUT=glass-test
mkdir -p "$OUT"
fails=0
step=0
log() { echo "[$(date +%H:%M:%S)] $*"; }
pass() { log "PASS $*"; }
fail() { log "FAIL $*"; fails=$((fails+1)); }
shot() { step=$((step+1)); adb exec-out screencap -p > "$OUT/$(printf %02d $step)-$1.png" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/ui.xml"; }
# Centre of the first node whose text or content-desc matches (whole match).
find_node() {
  dump
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import re,sys
pat=re.compile(sys.argv[1],re.I)
xml=open(sys.argv[2],errors='ignore').read()
for m in re.finditer(r'<node [^>]*>',xml):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"',n); d=re.search(r'content-desc="([^"]*)"',n)
    if (t and pat.fullmatch(t.group(1))) or (d and pat.fullmatch(d.group(1))):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
}
tap_text() { local p; p=$(find_node "$1"); [ -n "$p" ] && adb shell input tap $p && sleep "${2:-2}" && return 0; return 1; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }

log "device: Android $(adb shell getprop ro.build.version.release) / API $(adb shell getprop ro.build.version.sdk)"
adb install -r -g "$APK" && pass "install" || { fail "install"; exit 1; }
adb logcat -c; adb logcat -b crash -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 15
alive && pass "launch" || fail "launch"
shot launch

# BitChord's onboarding: a name, then skip sign-in.
if p=$(find_node "Your name") && [ -n "$p" ]; then
  adb shell input tap $p; adb shell input text Test; sleep 1; adb shell input keyevent 111; sleep 1
  tap_text "Next" 3 && pass "onboarding name" || fail "onboarding name"
  shot onboarding-connect
  tap_text "Skip sign in" 6 && pass "onboarding skip sign-in" || fail "onboarding skip"
fi
sleep 8; shot home
alive && pass "home shown" || fail "home"

# Square's bar: each BitChord tab by its label.
for tab in Explore Library Home; do
  if tap_text "$tab" 3 && alive; then pass "tab $tab"; else fail "tab $tab"; fi
  shot "tab-$tab"
done

# Search grows out of the bar; type, submit, play a result.
if tap_text "Search" 3; then
  shot search-open
  adb shell input text lofi; sleep 1; adb shell input keyevent 66; sleep 10
  shot search-results
  alive && pass "search query" || fail "search query"
  dump; grep -q 'text="lofi"' "$OUT/ui.xml" && pass "search field in the bar holds the query" || fail "search field text"
  tap_text ".*[Ll]ofi.*" 12 && pass "search result tapped" || fail "search result tap"
  shot after-result
else fail "search tab"; fi

if adb shell dumpsys media_session | grep -q "state=PlaybackState {state=3"; then pass "audio playing"; else log "INFO no playing state (network or stream)"; fi
w=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${w%x*}; H=${w#*x}
# Open the player from the bar's now playing pill.
p=$(find_node "(?i).*lofi.*") ; [ -n "$p" ] || p="$((W/2)) $((H*85/100))"
adb shell input tap $p; sleep 5; shot player
for v in Lyrics Queue Effects Credits Cover; do
  if tap_text "$v" 3 && alive; then pass "player view $v"; else fail "player view $v"; fi
  shot "player-$v"
done
tap_text "Effects" 3
if tap_text "Slowed \+ Reverb" 3; then
  shot effects-slowed
  adb shell dumpsys media_session | grep -o "speed=[0-9.]*" | head -1 | tee "$OUT/speed.txt"
  grep -q "speed=0.85" "$OUT/speed.txt" && pass "slowed preset: session speed 0.85" || log "INFO speed not in media_session"
  tap_text "Originale|Original" 2 && pass "original preset" || fail "original preset"
else fail "slowed preset"; fi
tap_text "Cover" 3
shot before-seek
adb shell input swipe $((W*30/100)) $((H*64/100)) $((W*60/100)) $((H*64/100)) 2500 &
sleep 1.2; shot seek-held; wait
alive && pass "seek drag" || fail "seek drag"
adb shell input keyevent 4; sleep 3

# Settings (the account button at the top right), Appearance, Liquid Glass.
tap_text "Settings|Account|.*[Pp]rofile.*" 3 || adb shell input tap $((W*92/100)) $((H*7/100))
sleep 3; shot settings
tap_text "Appearance" 3 && pass "settings appearance" || fail "settings appearance"
for i in 1 2 3; do
  tap_text "Liquid Glass settings" 3 && { pass "glass settings page"; break; }
  adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*35/100)) 400; sleep 1
done
shot glass-settings
tap_text "Quality" 2 && pass "glass profile Quality" || fail "glass profile Quality"
shot glass-quality
tap_text "Balanced" 2 && pass "glass profile Balanced" || fail "glass profile Balanced"

if adb logcat -d -b crash | grep -q "$PKG"; then fail "no crash"; adb logcat -d -b crash > "$OUT/crash.txt"; else pass "no crash"; fi
adb logcat -d > "$OUT/logcat.txt"
log "failures: $fails"
exit $fails
