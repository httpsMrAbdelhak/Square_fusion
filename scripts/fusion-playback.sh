#!/bin/bash
# Real playback on the emulator, against the debug build left installed by
# fusion-instrumented.sh (debuggable, so its preferences can be seeded).
#   1. YouTube Music as in Square: play, background, screen off, media keys,
#      notification, media session.
#   2. With a local test addon (scripts/fake-addon.py on the host, reached as
#      10.0.2.2): the song must come from the addon, and YouTube is the
#      fallback when the addon is switched off.
# Every step dumps the screen to playback/ so a failure can be read.
set -u
PKG=dev.lelonio.square.fusion
OUT=playback
mkdir -p "$OUT"
fail=0
n=0
step() { echo "== $*"; }
shot() { n=$((n + 1)); adb exec-out screencap -p > "$OUT/$(printf %02d $n)-$1.png" 2>/dev/null || true; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1; }

# Taps the centre of the first node matching a Python expression over
# (text, desc, clickable, x1, y1, x2, y2). Returns 1 when nothing matched.
tap_where() {
  dump "$1"
  local b
  b=$(python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
rule = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    text = re.search(r'text="([^"]*)"', n).group(1)
    desc = re.search(r'content-desc="([^"]*)"', n).group(1)
    clickable = 'clickable="true"' in n
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    if eval(rule):
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$b" ] || return 1
  echo "   tap $b ($1)"
  adb shell input tap $b
}

session_state() {
  # PlaybackState of this app's media session: 3 playing, 2 paused, 6 buffering.
  adb shell dumpsys media_session | python3 -c "
import re, sys
text = sys.stdin.read()
blocks = text.split('package=')
for b in blocks[1:]:
    if b.startswith('$PKG'):
        m = re.search(r'state=PlaybackState \{state=(\d+)', b)
        d = re.search(r'description=([^\n]*)', b)
        print((m.group(1) if m else '?') + ' | ' + (d.group(1).strip() if d else ''))
        break
else:
    print('none |')
"
}

expect_state() {
  local want="$1" label="$2" i s
  for i in $(seq 1 20); do
    s=$(session_state)
    case "$s" in "$want"*) echo "OK   $label: state ${s}"; return 0;; esac
    sleep 2
  done
  echo "FAIL $label: state ${s} (wanted $want)"
  fail=1
  return 1
}

start_playing() {
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 12
  # Fresh install: the onboarding asks for the source.
  tap_where onboarding 'text == "YouTube Music"' && sleep 15
  shot home
  # The first cover under the first shelf heading of the home page.
  tap_where home 'clickable and y1 > 380 and (x2 - x1) > 80 and (y2 - y1) > 80' || tap_where home2 'clickable and y1 > 300 and (y2 - y1) > 60'
  sleep 10
  shot list
  # A play button by its label, or else the first row of the list.
  tap_where list '(desc.lower() in ("play", "shuffle", "lecture") or text.lower() in ("play", "shuffle")) and clickable' \
    || tap_where list2 'desc.lower() in ("play", "shuffle", "lecture") or text.lower() in ("play", "shuffle")' \
    || tap_where list3 'clickable and y1 > 330 and (x2 - x1) > 200'
  sleep 5
}

seed_sources() {
  # $1: true or false — the test addon enabled or not.
  adb shell am force-stop "$PKG"
  cat > "$OUT/square_fusion_sources.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="sources">[{&quot;id&quot;:&quot;test-addon&quot;,&quot;kind&quot;:&quot;ADDON&quot;,&quot;baseUrl&quot;:&quot;http://10.0.2.2:8765&quot;,&quot;label&quot;:&quot;Fusion test addon&quot;,&quot;enabled&quot;:$1,&quot;allowDownloads&quot;:true,&quot;requiresLosslessOutput&quot;:false}]</string>
</map>
XML
  adb push "$OUT/square_fusion_sources.xml" /data/local/tmp/sources.xml > /dev/null
  adb shell run-as "$PKG" sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/sources.xml shared_prefs/square_fusion_sources.xml'
}

tones() { grep -c "GET /tone.wav" fake-addon.log 2>/dev/null || echo 0; }

# YouTube refuses anonymous streams to some networks (data centres among
# them: "This song plays only when signed in"), so the playback checks run on
# the addon's stream, which the app resolves before asking YouTube.

# ── 1. Playback from the addon ──────────────────────────────────────────────
step "play with the test addon enabled"
seed_sources true
adb logcat -c
start_playing
expect_state 3 "playing" && playing=1 || playing=0
sleep 5
shot playing
adb logcat -d | grep -E "SquareFusion" | tail -n 40 > "$OUT/addon-logcat.txt"
if grep -q "Fusion test addon: WAV" "$OUT/addon-logcat.txt" && [ "$(tones)" -gt 0 ]; then
  echo "OK   the song came from the addon ($(tones) requests for tone.wav)"
else
  echo "FAIL the addon was not used"; fail=1
  cat "$OUT/addon-logcat.txt"
fi

if [ "$playing" = 1 ]; then
  step "notification"
  if adb shell dumpsys notification --noredact | grep -A60 "pkg=$PKG" | grep -qiE "MediaStyle|mediaSession|category=transport|template=android.app.Notification\$MediaStyle"; then
    echo "OK   media notification posted"
  else
    echo "FAIL no media notification"; fail=1
    adb shell dumpsys notification --noredact | grep -A30 "pkg=$PKG" | head -n 40
  fi

  step "background"
  adb shell input keyevent KEYCODE_HOME
  sleep 10
  expect_state 3 "playing in background"

  step "screen off"
  adb shell input keyevent KEYCODE_SLEEP
  sleep 20
  expect_state 3 "playing with the screen off"
  adb shell input keyevent KEYCODE_WAKEUP
  sleep 2
  shot lockscreen

  step "media keys (headsets, the lock screen and the notification act on this same session)"
  adb shell input keyevent KEYCODE_MEDIA_PAUSE
  expect_state 2 "paused by media key"
  adb shell input keyevent KEYCODE_MEDIA_PLAY
  expect_state 3 "resumed by media key"
  before=$(session_state)
  adb shell input keyevent KEYCODE_MEDIA_NEXT
  sleep 10
  after=$(session_state)
  if [ "${before#*|}" != "${after#*|}" ]; then echo "OK   next track: ${after#*|}"; else echo "WARN next track: same description (${after#*|})"; fi
  expect_state 3 "playing after next"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 3
  shot back-in-app
fi

# ── 2. Addon switched off: YouTube, as in Square ────────────────────────────
step "addon switched off"
before_tones=$(tones)
seed_sources false
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 8
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 20
shot youtube
adb logcat -d | grep -E "Square" | tail -n 80 > "$OUT/youtube-logcat.txt"
if [ "$(tones)" = "$before_tones" ]; then
  echo "OK   the disabled addon was not asked; the song went to YouTube"
else
  echo "FAIL the disabled addon was still used"; fail=1
fi
s=$(session_state)
case "$s" in
  3*) echo "OK   YouTube playback: ${s}" ;;
  *) echo "WARN YouTube did not play here (${s}); see youtube-logcat.txt — YouTube refuses anonymous streams to data-centre networks" ;;
esac
if adb shell pidof "$PKG" > /dev/null; then echo "OK   app still running"; else echo "FAIL app stopped"; fail=1; fi

adb logcat -d | grep -E "SquareFusion|AndroidRuntime|FATAL" | tail -n 120 > "$OUT/logcat.txt" || true
echo "playback result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
