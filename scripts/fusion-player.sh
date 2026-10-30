#!/bin/bash
# The player on a recent Android, where the glass is drawn for real (Android
# 13 and later; Android 11 gets the plain film). Against the release APK:
#   1. the panel switcher under the transport is whole on every opening, after
#      any number of closes, and switches between the cover, lyrics, queue,
#      effects and credits;
#   2. the drag down: a short or an accidental one leaves the player open, a
#      long one closes it, a sideways one on the switcher never does, and the
#      haptic tick is asked for as the threshold is crossed;
#   3. a screen recording of slow, normal and quick closes, releases at
#      several points and openings, for looking at frame by frame;
#   4. a change of song during all of it.
# What the network refuses (YouTube streams) is a WARN; nothing here needs a
# song to actually play, only a cover in the bar.
set -u
APK="$1"
PKG=dev.lelonio.square.fusion
OUT=player
mkdir -p "$OUT"
fail=0
n=0
step() { echo "== $*"; }
# Named by the caller, not numbered: it is called inside $(...) as well.
shot() { adb shell screencap -p /sdcard/shot.png > /dev/null 2>&1; adb pull /sdcard/shot.png "$OUT/$1.png" > /dev/null 2>&1; echo "$OUT/$1.png"; }
dump() {
  adb shell rm -f /sdcard/ui.xml
  local said; said=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1 | tr -d '\r')
  adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || { echo "LOG dump $1 failed: $said" >&2; : > "$OUT/$1.xml"; }
}
alive() { adb shell pidof "$PKG" > /dev/null; }

# The centre of the first node matching a Python expression over
# (text, desc, clickable, x1, y1, x2, y2) in dump $1; nothing when none does.
find_node() {
  python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys, html
try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
rule = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    d = re.search(r'content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not (t and d and b):
        continue
    text, desc = html.unescape(t.group(1)), html.unescape(d.group(1))
    clickable = 'clickable="true"' in n
    x1, y1, x2, y2 = map(int, b.groups())
    if eval(rule):
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}
# The same, for the last node matching: the player's own controls come after
# the page's in a dump, and the bar under the player has a Pause button too.
find_last() {
  python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys, html
try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
rule = sys.argv[2]
last = None
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    d = re.search(r'content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not (t and d and b):
        continue
    text, desc = html.unescape(t.group(1)), html.unescape(d.group(1))
    clickable = 'clickable="true"' in n
    x1, y1, x2, y2 = map(int, b.groups())
    if eval(rule):
        last = ((x1 + x2) // 2, (y1 + y2) // 2)
if last:
    print(*last)
PY
}
tap_where() {
  dump "$1"
  local b; b=$(find_node "$1" "$2")
  [ -n "$b" ] || return 1
  echo "LOG tap $b ($1)"
  adb shell input tap $b
}

read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n 1 | tr x ' ')
DENSITY=$(adb shell wm density | grep -oE '[0-9]+' | tail -n 1)
dp() { echo $(( $1 * DENSITY / 160 )); }
echo "LOG screen ${W}x${H} at ${DENSITY} dpi, Android $(adb shell getprop ro.build.version.release | tr -d '\r')"

player_open() { dump po; grep -q 'content-desc="Close"' "$OUT/po.xml"; }
# Where the player's Close arrow is: on screen means the player is open, not
# only composed somewhere below it.
close_on_screen() { dump pc; [ -n "$(find_node pc "desc == 'Close' and y2 < $H // 4")" ]; }
settle() { sleep "${1:-3}"; }

# The bar's title: the text on the line of the bar's own Next button.
bar_title() {
  dump bar
  python3 - "$OUT/bar.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
nodes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and d and b:
        nodes.append((t.group(1), d.group(1), *map(int, b.groups())))
nxt = [n for n in nodes if n[1] == "Next"]
if nxt:
    _, _, nx1, ny1, _, ny2 = nxt[-1]
    for t, d, x1, y1, x2, y2 in nodes:
        if t and x2 <= nx1 and ny1 - 20 <= y1 <= ny2:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}
open_from_bar() {
  local b; b=$(bar_title)
  [ -n "$b" ] || { echo "LOG no bar to open from ($1)"; return 1; }
  adb shell input tap $b
  local i
  for i in 1 2 3 4 5; do settle 2; close_on_screen && return 0; done
  return 1
}
close_with_arrow() {
  tap_where "$1" "desc == 'Close' and y2 < $H // 4" > /dev/null || return 1
  settle 3
  ! close_on_screen
}

haptics() { { adb shell dumpsys vibrator_manager 2>/dev/null; adb shell dumpsys vibrator 2>/dev/null; } | grep -ciE "vibration|effect=|usage=" ; }

# A drag by hand: down, moved in steps, held, let go. $1 $2 start, $3 dy in
# dp, $4 steps, $5 seconds held before the release.
drag() {
  local x=$1 y=$2 dy=$(dp $3) steps=$4 hold=${5:-0} i
  adb shell input motionevent DOWN $x $y
  for i in $(seq 1 $steps); do adb shell input motionevent MOVE $x $((y + dy * i / steps)); done
  [ "$hold" = 0 ] || sleep "$hold"
  adb shell input motionevent UP $x $((y + dy))
}

# The device's log as it goes, kept on this side: if the emulator itself goes
# away, what it said last is still here.
adb logcat -c
adb logcat -v time > "$OUT/logcat-live.txt" 2>/dev/null &
logpid=$!
trap 'kill $logpid 2>/dev/null' EXIT

# ── Install and first launch ────────────────────────────────────────────────
step "install"
adb install -r -g "$APK" > /dev/null || { echo "FAIL install"; exit 1; }
adb shell pm clear "$PKG" > /dev/null
adb shell settings put global animator_duration_scale 1
adb shell settings put system haptic_feedback_enabled 1
ACT=$(adb shell cmd package resolve-activity --brief "$PKG" | tr -d '\r' | tail -n 1)
echo "LOG launcher activity: $ACT"
adb logcat -c
adb shell am start -W -n "$ACT" | grep -E "Status|LaunchState|Error" | sed 's/^/LOG /'
sleep 15
alive || { echo "FAIL the app is not running after launch"; adb logcat -d -b crash | head -n 30 | sed 's/^/LOG /'; }
tap_where onboarding 'text == "YouTube Music"' || echo "WARN no YouTube Music card"
sleep 15
dump home
song=$(find_node home "x1 > $(dp 70) and x1 < $(dp 110) and y1 > $(dp 180) and y2 < $H * 3 // 4 and len(text) > 1")
if [ -z "$song" ]; then
  echo "FAIL no song on the Home page to play"; shot no-home > /dev/null
  grep -oE 'text="[^"]+"' "$OUT/home.xml" | head -n 15 | sed 's/^/LOG /'
  adb logcat -d > "$OUT/logcat.txt"; grep -E "FATAL|AndroidRuntime: " "$OUT/logcat.txt" | head -n 20 | sed 's/^/LOG /'
  exit 1
fi
adb shell input tap $song; sleep 10
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 3
# Back to Home without Back, which on this Android leaves the app.
adb shell am start -n "$ACT" > /dev/null 2>&1; sleep 3
tap_where home-tab "(text == 'Home' or desc == 'Home') and y1 > $H * 3 // 4" > /dev/null || true
sleep 4
alive || { echo "FAIL the app is gone after playing from Home"; exit 1; }
[ -n "$(bar_title)" ] && echo "OK   a song in the bar" || { echo "FAIL no bar after playing a song"; shot no-bar > /dev/null; exit 1; }

rec=
if [ "${RECORD:-1}" = 1 ]; then
  adb shell screenrecord --bit-rate 4000000 --time-limit 170 /sdcard/player.mp4 > /dev/null 2>&1 &
  rec=$!
fi
sleep 1

# ── 1. The switcher on every opening ────────────────────────────────────────
gone() {
  adb get-state 2>/dev/null | grep -q device && return 0
  echo "FAIL the emulator went away (adb: $(adb get-state 2>&1 | head -n 1))"
  grep -iE "FATAL|crash|signal|lowmemory|killing|watchdog|died|gralloc|EGL|vulkan|hwui" "$OUT/logcat-live.txt" | tail -n 30 | cut -c1-200 | sed 's/^/LOG /'
  tail -n 15 "$OUT/logcat-live.txt" | cut -c1-200 | sed 's/^/LOG last: /'
  exit 1
}
step "panel switcher"
if open_from_bar; then echo "OK   the bar opens the player"; else
  gone
  echo "FAIL the bar did not open the player"; fail=1
  shot open-failed > /dev/null
  grep -oE '(text|content-desc)="[^"]+"' "$OUT/pc.xml" | head -n 20 | sed 's/^/LOG /'
fi
# Whether the whole switcher is drawn: under the transport, five tabs' worth
# of glyphs across the capsule, not the lit one alone. Taken again while a
# toast covers that part of the screen.
switcher_drawn() {
  local label=$1 try png pause out
  for try in 1 2 3 4; do
    dump "$label"
    pause=$(find_last "$label" "desc in ('Pause', 'Play') and y1 > $H // 2")
    [ -n "$pause" ] || { sleep 2; continue; }
    png=$(shot "$label")
    out=$(python3 - "$png" "${pause#* }" "$W" "$DENSITY" <<'PY'
import sys
from PIL import Image, ImageStat
png, py, w, density = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
dp = density / 160
im = Image.open(png).convert("L")
band = im.crop((int(w * 0.10), int(py + 45 * dp), int(w * 0.90), int(py + 110 * dp)))
if ImageStat.Stat(band).mean[0] > 150:
    print("toast"); sys.exit()
lit = 0
for i in range(5):
    c = band.crop((band.width * i // 5, 0, band.width * (i + 1) // 5, band.height))
    px = sorted(c.getdata())
    base = px[len(px) // 2]
    if sum(1 for p in px if p > base + 60) > 12 * dp * dp:
        lit += 1
print(lit)
PY
)
    [ "$out" = toast ] && { sleep 3; continue; }
    echo "$out"; return 0
  done
  echo "none"
}
for round in 1 2 3 4 5; do
  if [ "$round" -gt 1 ]; then
    close_with_arrow "close-$round" || { echo "FAIL the arrow did not close the player (round $round)"; fail=1; }
    open_from_bar || { echo "FAIL the bar did not open the player (round $round)"; fail=1; }
  fi
  settle 2
  lit=$(switcher_drawn "open-$round" | tail -n 1)
  case "$lit" in
    4|5) echo "OK   opening $round: the switcher is whole ($lit of 5 tabs drawn)";;
    toast|none) echo "WARN opening $round: the switcher could not be looked at ($lit)";;
    *) echo "FAIL opening $round: the switcher is not whole ($lit of 5 tabs drawn)"; fail=1;;
  esac
done

# Every view, from its tab (found by position: the tabs are not exposed to
# accessibility), named in the line above the picture.
dump views; pause=$(find_last views "desc in ('Pause', 'Play') and y1 > $H // 2")
if [ -n "$pause" ]; then
  ty=$(( ${pause#* } + $(dp 77) ))
  i=0
  for tab in Cover Lyrics Queue Effects Credits; do
    tx=$(( W * 8 / 100 + (W * 84 / 100) * (2 * i + 1) / 10 ))
    i=$((i + 1))
    # Where the transport is now: a view can move it.
    dump "before-$tab"; p2=$(find_last "before-$tab" "desc in ('Pause', 'Play') and y1 > $H // 2")
    [ -n "$p2" ] && ty=$(( ${p2#* } + $(dp 77) ))
    adb shell input tap $tx $ty; settle 2
    dump "view-$tab"; shot "view-$tab" > /dev/null
    case $tab in Cover) want='Trending now|Now playing|text="[^"]+"';; Lyrics) want='text="Lyrics"';; Queue) want='text="Queued"';; Effects) want='text="Effects"';; Credits) want='text="Credits"';; esac
    if grep -qE "$want" "$OUT/view-$tab.xml"; then echo "OK   the $tab tab opens its view"; else echo "FAIL the $tab tab did not open its view"; fail=1; fi
  done
  adb shell input tap $(( W * 8 / 100 + (W * 84 / 100) / 10 )) $ty; settle 2
else
  echo "FAIL no transport to find the tabs under"; fail=1
fi
close_on_screen || { echo "FAIL the player closed while switching views"; fail=1; }

# ── 2. The drag down ────────────────────────────────────────────────────────
step "drag down"
# On the picture, clear of the switcher and of anything that scrolls.
DX=$((W / 2)); DY=$((H / 4))
try_drag() {
  # $1 label, $2 dy dp, $3 steps, $4 hold, $5 expected: open | closed
  close_on_screen || open_from_bar > /dev/null || true
  local before; before=$(haptics)
  drag $DX $DY "$2" "$3" "$4"
  settle 3
  local after; after=$(haptics)
  local state=closed; close_on_screen && state=open
  local tick="no haptic"; [ "${after:-0}" -gt "${before:-0}" ] 2>/dev/null && tick="haptic"
  if [ "$state" = "$5" ]; then echo "OK   $1: player $state ($tick)"; else echo "FAIL $1: player $state, wanted $5 ($tick)"; fail=1; fi
}
try_drag "a 30 dp drag" 30 6 0 open
try_drag "a quick 50 dp flick" 50 2 0 open
try_drag "a 90 dp drag" 90 10 0 open
try_drag "a 90 dp drag held, then let go" 90 10 1 open
try_drag "a 200 dp drag" 200 16 0 closed
try_drag "a 200 dp drag held, then let go" 200 16 1 closed
try_drag "a 160 dp drag in two moves" 160 2 0 closed
vib=$({ adb shell dumpsys vibrator_manager 2>/dev/null; adb shell dumpsys vibrator 2>/dev/null; } | grep -iE "vibrat|effect" | tail -n 4 | cut -c1-160)
echo "LOG vibrator: $(echo "$vib" | head -n 3 | tr '\n' ' ')"

# Sideways on the switcher: views change, the player stays.
open_from_bar > /dev/null || true
dump side; pause=$(find_last side "desc in ('Pause', 'Play') and y1 > $H // 2")
if [ -n "$pause" ]; then
  sy=$(( ${pause#* } + $(dp 77) ))
  adb shell input swipe $((W * 15 / 100)) $sy $((W * 85 / 100)) $sy 600; settle 2
  close_on_screen && echo "OK   a sideways drag along the switcher leaves the player open" || { echo "FAIL a sideways drag on the switcher closed the player"; fail=1; }
  # Sideways with some of the way down in it, as a thumb does.
  adb shell input swipe $((W * 85 / 100)) $sy $((W * 15 / 100)) $((sy + $(dp 30))) 500; settle 2
  close_on_screen && echo "OK   a slanting drag along the switcher leaves the player open" || { echo "FAIL a slanting drag on the switcher closed the player"; fail=1; }
  adb shell input tap $(( W * 8 / 100 + (W * 84 / 100) / 10 )) $sy; settle 2
fi

# ── 3. For the recording: closes and opens of every kind ────────────────────
step "motion"
close_on_screen || open_from_bar > /dev/null
echo "LOG slow close"; drag $DX $DY 420 40 0; settle 3
open_from_bar > /dev/null; settle 2
echo "LOG normal close"; adb shell input swipe $DX $DY $DX $((DY + $(dp 300))) 350; settle 3
open_from_bar > /dev/null; settle 2
echo "LOG quick close"; adb shell input swipe $DX $DY $DX $((DY + $(dp 220))) 120; settle 3
open_from_bar > /dev/null; settle 2
echo "LOG let go before the threshold"; drag $DX $DY 80 12 1; settle 3
close_on_screen || { echo "FAIL let go before the threshold closed the player"; fail=1; }
echo "LOG let go past the threshold"; drag $DX $DY 180 16 1; settle 3
close_on_screen && { echo "FAIL let go past the threshold left the player open"; fail=1; }
echo "LOG down past the threshold, then back up"; open_from_bar > /dev/null; settle 1
adb shell input motionevent DOWN $DX $DY
for i in 1 2 3 4 5 6 7 8; do adb shell input motionevent MOVE $DX $((DY + $(dp 25) * i)); done
for i in 7 6 5 4 3 2 1 0; do adb shell input motionevent MOVE $DX $((DY + $(dp 25) * i)); done
adb shell input motionevent UP $DX $DY; settle 3
close_on_screen && echo "OK   back up before letting go keeps the player open" || { echo "FAIL back up before letting go closed the player"; fail=1; }
close_with_arrow arrow-final > /dev/null

# ── 4. A change of song in the middle of it ─────────────────────────────────
step "change of song"
open_from_bar > /dev/null
adb shell input keyevent KEYCODE_MEDIA_NEXT
adb shell input swipe $DX $DY $DX $((DY + $(dp 300))) 300
adb shell input keyevent KEYCODE_MEDIA_NEXT; settle 4
alive && echo "OK   alive after songs changed during a close" || { echo "FAIL the app died during a change of song"; fail=1; }
open_from_bar > /dev/null && echo "OK   the player opens again after the change of song" || { echo "FAIL the player does not open after the change of song"; fail=1; }
shot after-change > /dev/null
close_with_arrow arrow-end > /dev/null
alive || { echo "FAIL the app is gone"; fail=1; }

[ -n "$rec" ] && wait $rec 2>/dev/null || true
sleep 1
[ -n "$rec" ] && adb pull /sdcard/player.mp4 "$OUT/player.mp4" > /dev/null 2>&1 && echo "LOG recording: player.mp4"
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|90th|99th" | sed 's/^/LOG /'
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
grep -E "FATAL|AndroidRuntime: " "$OUT/logcat.txt" | head -n 20 | sed 's/^/LOG /'
echo "player result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
