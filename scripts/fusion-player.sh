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
shot() { n=$((n + 1)); adb exec-out screencap -p > "$OUT/$(printf %02d $n)-$1.png" 2>/dev/null || true; echo "$OUT/$(printf %02d $n)-$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1; }
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
  settle 3
  close_on_screen
}
close_with_arrow() {
  tap_where "$1" "desc == 'Close' and y2 < $H // 4" > /dev/null || return 1
  settle 3
  ! close_on_screen
}

# The switcher under the transport: the nodes of its five tabs, and the band
# of the screen they sit in.
TABS="Cover Lyrics Queue Effects Credits"
tabs_band() {
  python3 - "$OUT/$1.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
want = {"Cover", "Lyrics", "Queue", "Effects", "Credits"}
boxes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    d = re.search(r'content-desc="([^"]*)"', n); t = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and ((d and d.group(1) in want) or (t and t.group(1) in want)):
        x1, y1, x2, y2 = map(int, b.groups())
        if y1 > 0:
            boxes.append((x1, y1, x2, y2))
if boxes:
    # The lowest row of them: the switcher, not a title saying "Lyrics".
    y = max(b[1] for b in boxes)
    row = [b for b in boxes if abs(b[1] - y) < 40]
    print(len(row), min(b[0] for b in row), min(b[1] for b in row), max(b[2] for b in row), max(b[3] for b in row))
PY
}
# How different two screenshots are inside a box, 0 to 255; and how much is
# drawn there at all (the spread of its pixels).
compare_band() {
  python3 - "$@" <<'PY'
import sys
from PIL import Image, ImageChops, ImageStat
a, b = Image.open(sys.argv[1]).convert("RGB"), Image.open(sys.argv[2]).convert("RGB")
x1, y1, x2, y2 = map(int, sys.argv[3:7])
pad = 12
box = (max(0, x1 - pad), max(0, y1 - pad), x2 + pad, y2 + pad)
ca, cb = a.crop(box), b.crop(box)
diff = ImageStat.Stat(ImageChops.difference(ca, cb)).mean
spread_a = ImageStat.Stat(ca.convert("L")).stddev[0]
spread_b = ImageStat.Stat(cb.convert("L")).stddev[0]
print(f"{sum(diff) / 3:.1f} {spread_a:.1f} {spread_b:.1f}")
PY
}

haptics() { adb shell dumpsys vibrator_manager 2>/dev/null | grep -ciE "usage=(TOUCH|PHYSICAL_EMULATION|HARDWARE_FEEDBACK)|HAPTIC|effect=" ; }

# A drag by hand: down, moved in steps, held, let go. $1 $2 start, $3 dy in
# dp, $4 steps, $5 seconds held before the release.
drag() {
  local x=$1 y=$2 dy=$(dp $3) steps=$4 hold=${5:-0} i
  adb shell input motionevent DOWN $x $y
  for i in $(seq 1 $steps); do adb shell input motionevent MOVE $x $((y + dy * i / steps)); done
  [ "$hold" = 0 ] || sleep "$hold"
  adb shell input motionevent UP $x $((y + dy))
}

# ── Install and first launch ────────────────────────────────────────────────
step "install"
adb install -r -g "$APK" > /dev/null || { echo "FAIL install"; exit 1; }
adb shell pm clear "$PKG" > /dev/null
adb shell settings put global animator_duration_scale 1
adb shell settings put system haptic_feedback_enabled 1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 15
tap_where onboarding 'text == "YouTube Music"' || echo "WARN no YouTube Music card"
sleep 15
dump home
song=$(find_node home "x1 > $(dp 70) and x1 < $(dp 110) and y1 > $(dp 180) and y2 < $H * 3 // 4 and len(text) > 1")
if [ -z "$song" ]; then echo "FAIL no song on the Home page to play"; shot no-home > /dev/null; exit 1; fi
adb shell input tap $song; sleep 10
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
alive || { echo "FAIL the app is gone after playing from Home"; exit 1; }
[ -n "$(bar_title)" ] && echo "OK   a song in the bar" || { echo "FAIL no bar after playing a song"; shot no-bar > /dev/null; exit 1; }

adb shell screenrecord --bit-rate 8000000 --time-limit 170 /sdcard/player.mp4 > /dev/null 2>&1 &
rec=$!
sleep 1

# ── 1. The switcher on every opening ────────────────────────────────────────
step "panel switcher"
if open_from_bar; then echo "OK   the bar opens the player"; else echo "FAIL the bar did not open the player"; fail=1; fi
dump open-1; first=$(shot open-1)
band=$(tabs_band open-1)
if [ -z "$band" ]; then echo "FAIL no panel switcher in the player"; fail=1
else
  read count bx1 by1 bx2 by2 <<< "$band"
  echo "LOG switcher: $count tabs at [$bx1,$by1][$bx2,$by2]"
  for round in 2 3 4 5; do
    close_with_arrow "close-$round" || { echo "FAIL the arrow did not close the player (round $round)"; fail=1; }
    open_from_bar || { echo "FAIL the bar did not open the player (round $round)"; fail=1; }
    settle 2
    dump "open-$round"; again=$(shot "open-$round")
    b2=$(tabs_band "open-$round")
    [ -n "$b2" ] || { echo "FAIL no switcher on opening $round"; fail=1; continue; }
    read diff spread1 spread2 <<< "$(compare_band "$first" "$again" $bx1 $by1 $bx2 $by2)"
    echo "LOG opening $round: switcher differs from the first by $diff (detail $spread1 then $spread2)"
    # The whole capsule and its tabs or not: a capsule that is missing leaves
    # the backdrop alone there, much flatter than with it.
    if awk "BEGIN { exit !($diff < 14 && $spread2 > $spread1 * 0.7) }"; then
      echo "OK   the switcher is whole on opening $round"
    else
      echo "FAIL the switcher is not as on the first opening (opening $round)"; fail=1
    fi
  done
fi

# Every view, from its tab, named in the line above the picture.
for tab in Lyrics Queue Effects Credits Cover; do
  case $tab in Lyrics) title=Lyrics;; Queue) title=Queue;; Effects) title=Effects;; Credits) title=Credits;; Cover) title="";; esac
  if tap_where "tab-$tab" "(desc == '$tab' or text == '$tab') and y1 > $H // 2" > /dev/null; then
    settle 2; dump "view-$tab"; shot "view-$tab" > /dev/null
    if [ -z "$title" ] || grep -qE "text=\"($title|Up next|Queued|Next up)[^\"]*\"" "$OUT/view-$tab.xml"; then
      echo "OK   the $tab tab opens its view"
    else
      echo "FAIL the $tab tab did not open its view"; fail=1
    fi
  else
    echo "FAIL no $tab tab"; fail=1
  fi
done
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
vib=$(adb shell dumpsys vibrator_manager 2>/dev/null | grep -iE "vibrat|effect" | head -n 6 | cut -c1-160)
echo "LOG vibrator: $(echo "$vib" | head -n 3 | tr '\n' ' ')"

# Sideways on the switcher: views change, the player stays.
open_from_bar > /dev/null || true
dump side; sb=$(tabs_band side)
if [ -n "$sb" ]; then
  read c sx1 sy1 sx2 sy2 <<< "$sb"
  sy=$(((sy1 + sy2) / 2))
  adb shell input swipe $((sx1 + 20)) $sy $((sx2 - 20)) $sy 600; settle 2
  close_on_screen && echo "OK   a sideways drag along the switcher leaves the player open" || { echo "FAIL a sideways drag on the switcher closed the player"; fail=1; }
  # Sideways with some of the way down in it, as a thumb does.
  adb shell input swipe $((sx2 - 20)) $sy $((sx1 + 20)) $((sy + $(dp 30))) 500; settle 2
  close_on_screen && echo "OK   a slanting drag along the switcher leaves the player open" || { echo "FAIL a slanting drag on the switcher closed the player"; fail=1; }
  tap_where back-cover "(desc == 'Cover' or text == 'Cover') and y1 > $H // 2" > /dev/null; settle 2
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

wait $rec 2>/dev/null || true
sleep 1
adb pull /sdcard/player.mp4 "$OUT/player.mp4" > /dev/null 2>&1 && echo "LOG recording: player.mp4"
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|90th|99th" | sed 's/^/LOG /'
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
grep -E "FATAL|AndroidRuntime: " "$OUT/logcat.txt" | head -n 20 | sed 's/^/LOG /'
echo "player result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
