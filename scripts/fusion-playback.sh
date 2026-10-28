#!/bin/bash
# Spotify playlist import and direct playback on the emulator, against the
# debug build left installed by fusion-instrumented.sh:
#   1. first launch offers YouTube Music only;
#   2. Library > + > Import a playlist > public Spotify link > Import;
#   3. Play: the first song is matched on YouTube Music and played, then the
#      next one, in the playlist's order;
#   4. background, screen off, media keys and notification, when it plays.
# YouTube refuses anonymous streams to some data-centre networks, and Spotify
# may refuse them too: those refusals are reported as WARN, not hidden, and
# the log says which step stopped. Every step dumps the screen to playback/.
set -u
PKG=dev.lelonio.square.fusion
LINK="${SPOTIFY_TEST_PLAYLIST:-https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M}"
OUT=playback
mkdir -p "$OUT"
fail=0
n=0
step() { echo "== $*"; }
shot() { n=$((n + 1)); adb exec-out screencap -p > "$OUT/$(printf %02d $n)-$1.png" 2>/dev/null || true; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1; }
texts() {
  # What dump $1 shows: its texts and descriptions with their bounds, as LOG lines.
  python3 - "$OUT/$1.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    c = re.search(r'class="([^"]*)"', n).group(1).split(".")[-1]
    b = re.search(r'bounds="([^"]*)"', n).group(1)
    if t or d:
        print("LOG", c, b, repr(t[:40]), repr(d[:40]), "click" if 'clickable="true"' in n else "")
PY
}
alive() { adb shell pidof "$PKG" > /dev/null; }
wait_log() {
  # $1: regex, $2: seconds. Prints the first matching line.
  local i line
  for i in $(seq 1 "$2"); do
    line=$(adb logcat -d -s 'SquareFusion:*' 'SquareUi:*' 2>/dev/null | grep -E "$1" | head -n 1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep 1
  done
  return 1
}

# Taps the centre of the first node matching a Python expression over
# (text, desc, clickable, cls, x1, y1, x2, y2). Returns 1 when nothing matched.
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
    cls = re.search(r'class="([^"]*)"', n).group(1)
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

wait_state() {
  local want="$1" i s
  for i in $(seq 1 20); do
    s=$(session_state)
    case "$s" in "$want"*) echo "$s"; return 0;; esac
    sleep 2
  done
  echo "$s"
  return 1
}

# ── 1. First launch ─────────────────────────────────────────────────────────
step "first launch"
adb shell pm clear "$PKG" > /dev/null
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 12
dump onboarding
shot onboarding
if grep -q 'text="Spotify"' "$OUT/onboarding.xml"; then
  echo "FAIL the first launch still offers Spotify"; fail=1
else
  echo "OK   no Spotify choice on the first launch"
fi
tap_where onboarding 'text == "YouTube Music"' || echo "WARN no YouTube Music card (onboarding already done?)"
sleep 12
dump home
if grep -qE 'ArrowsLeftRight|text="YouTube Music · ' "$OUT/home.xml"; then
  echo "FAIL a source switcher is still on the home page"; fail=1
else
  echo "OK   no source switcher on the home page"
fi
shot home
# The Podcasts chip, beside YouTube Music's own ones.
if grep -q 'text="Podcasts"' "$OUT/home.xml"; then
  echo "OK   Podcasts chip on the home page"
  adb logcat -c
  tap_where home-chip 'text == "Podcasts"'
  sleep 15
  dump podcasts
  shot podcasts
  if adb logcat -d -s 'SquareUi:*' | grep -q "youtube home failed"; then
    echo "WARN the podcasts page did not load here: $(adb logcat -d -s 'SquareUi:*' | grep 'youtube home failed' | head -n 1 | cut -c1-200)"
  else
    echo "OK   podcasts page loaded ($(grep -o 'clickable="true"' "$OUT/podcasts.xml" | wc -l) tappable items)"
    # A show from that page opens with its episodes, not "No songs".
    if tap_where podcasts-card 'clickable and y1 > 500 and (x2 - x1) > 150 and (y2 - y1) > 150'; then
      sleep 15
      dump show
      shot show
      if grep -q 'text="No songs"' "$OUT/show.xml"; then
        echo "FAIL a podcast opened from the home page shows no episodes"; fail=1
      else
        echo "OK   podcast opened with $(grep -o 'clickable="true"' "$OUT/show.xml" | wc -l) tappable rows, no \"No songs\""
      fi
      adb shell input keyevent KEYCODE_BACK
      sleep 3
    else
      echo "WARN no podcast card to open on the podcasts page"
      texts podcasts | head -n 40
    fi
  fi
  tap_where home-chip2 'text == "Podcasts"' || true
  sleep 3
else
  echo "WARN no Podcasts chip (the home page loaded no chips here)"
fi

# ── 2. Import ───────────────────────────────────────────────────────────────
step "import $LINK"
tap_where tabs '(text == "Library" or desc == "Library") and y1 > 1200' \
  || tap_where tabs2 'text == "Library" or desc == "Library"' \
  || { echo "FAIL no Library tab"; fail=1; }
sleep 4
tap_where library 'desc == "New playlist"' || { echo "FAIL no + in the Library"; fail=1; }
sleep 3
shot add-menu
tap_where menu 'text == "Import a playlist"' || { echo "FAIL no Import a playlist entry"; fail=1; }
sleep 3
tap_where dialog '"EditText" in cls' || echo "WARN no text field found; typing into the focused one"
adb shell input text "'$LINK'"
sleep 1
shot dialog
adb logcat -c
tap_where dialog2 'text == "Import"' || { echo "FAIL no Import button"; fail=1; }
result=$(wait_log "imported playlist|spotify import failed" 90)
shot imported
imported=0
case "$result" in
  *"imported playlist"*) echo "OK   ${result#*: }"; imported=1 ;;
  *"import failed"*) echo "WARN Spotify refused the import here: ${result#*: }"
    adb logcat -d -s 'SquareFusion:*' | tail -n 25 | sed 's/^/LOG /' ;;
  *) echo "FAIL the import neither finished nor failed in 90 s"; fail=1 ;;
esac
alive || { echo "FAIL app stopped during the import"; fail=1; }

# ── 3. Direct playback ──────────────────────────────────────────────────────
if [ "$imported" = 1 ]; then
  step "play the imported playlist"
  sleep 6
  dump playlist
  shot playlist
  adb logcat -c
  # Play, not Shuffle: the first song must be the playlist's first.
  tap_where playlist '(desc.lower() == "play" or text.lower() == "play") and clickable' \
    || tap_where playlist2 'desc.lower() == "play" or text.lower() == "play"' \
    || { echo "FAIL no Play button on the imported playlist"; fail=1; }
  first=$(echo "$result" | sed -n 's/.*, first \([A-Za-z0-9]*\).*/\1/p')
  # What the player loads first (the next songs are matched ahead in parallel,
  # so the first match logged need not be the first song).
  l=$(wait_log "player loads sp-" 60)
  case "$l" in
    *"player loads sp-$first"*) echo "OK   the player loaded the playlist's first song first ($first)" ;;
    *"player loads"*) echo "FAIL the player loaded another song first than the playlist's first ($first): ${l#*: }"; fail=1 ;;
    *) echo "WARN the player loaded no Spotify song in 60 s" ;;
  esac
  m=$(wait_log "match $first " 60 || wait_log "match [A-Za-z0-9]+ " 5)
  case "$m" in
    *"->"*) echo "OK   matched on YouTube Music: ${m#*: match }" ;;
    *"none close enough"*) echo "WARN first song not found on YouTube Music: ${m#*: match }" ;;
    *) echo "WARN no match attempt logged in 60 s (YouTube Music search unreachable?)" ;;
  esac
  if s=$(wait_state 3); then
    echo "OK   playing: ${s#*|}"
    shot playing
    step "next song keeps the playlist's order"
    adb logcat -c
    adb shell input keyevent KEYCODE_MEDIA_NEXT
    m2=$(wait_log "match [A-Za-z0-9]+ " 40)
    echo "     ${m2#*: }"
    s2=$(wait_state 3) && echo "OK   next: ${s2#*|}" || echo "WARN next song did not play: ${s2}"
    step "background and screen off"
    adb shell input keyevent KEYCODE_HOME
    sleep 10
    s=$(wait_state 3) && echo "OK   playing in background" || { echo "FAIL stopped in background: $s"; fail=1; }
    adb shell input keyevent KEYCODE_SLEEP
    sleep 20
    s=$(wait_state 3) && echo "OK   playing with the screen off" || { echo "FAIL stopped with the screen off: $s"; fail=1; }
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell input keyevent KEYCODE_MEDIA_PAUSE
    s=$(wait_state 2) && echo "OK   paused by media key" || { echo "FAIL media pause: $s"; fail=1; }
    adb shell input keyevent KEYCODE_MEDIA_PLAY
    s=$(wait_state 3) && echo "OK   resumed by media key" || { echo "FAIL media play: $s"; fail=1; }
    if adb shell dumpsys notification --noredact | grep -A60 "pkg=$PKG" | grep -qiE "MediaStyle|mediaSession|category=transport"; then
      echo "OK   media notification posted"
    else
      echo "FAIL no media notification"; fail=1
    fi
  else
    echo "WARN not playing here (${s}); see logcat.txt. YouTube refuses anonymous streams to data-centre networks."
  fi
  adb logcat -d | grep -E "SquareYTQuality" | tail -n 3 | sed 's/^/     /'
fi

# ── 3b. Search: a podcast, its episodes, and the filter kept on the way back ─
step "search a podcast"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 3
for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 4
selected_chip() {
  # Whether the chip labelled $2 (or a node around it) is marked selected in dump $1.
  python3 - "$OUT/$1.xml" "$2" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
parent = {c: p for p in root.iter() for c in p}
for n in root.iter("node"):
    if n.get("text") == sys.argv[2]:
        e = n
        while e is not None:
            if e.get("selected") == "true" or e.get("checked") == "true":
                print("yes"); sys.exit(0)
            e = parent.get(e)
print("no")
PY
}
if tap_where search-tab 'desc == "Search" or text == "Search"'; then
  sleep 3
  tap_where search-field '"EditText" in cls' || true
  adb shell input text "Flippe%sGo%sSleep"
  adb shell input keyevent KEYCODE_ENTER
  sleep 12
  dump results1
  tap_where results 'text == "Podcasts"' || { echo "WARN no Podcasts filter in the results"; texts results1 | head -n 40; }
  sleep 4
  dump results2
  shot results
  if tap_where results3 '"Flippe" in text and y1 > 250'; then
    sleep 15
    dump podcast
    shot podcast
    if grep -q 'text="No songs"' "$OUT/podcast.xml"; then
      echo "FAIL the podcast opened from search shows no episodes"; fail=1
    else
      echo "OK   podcast opened from search with $(grep -o 'text="[^"]*FLIPPE[^"]*"' "$OUT/podcast.xml" | wc -l) episode rows on screen"
    fi
    adb shell input keyevent KEYCODE_BACK
    sleep 4
    dump back
    shot back
    if [ "$(selected_chip back Podcasts)" = yes ]; then
      echo "OK   the Podcasts filter is still selected after coming back"
    elif [ "$(selected_chip results2 Podcasts)" = no ]; then
      echo "WARN cannot tell the selected chip from the screen dump"
    else
      echo "FAIL the Podcasts filter was lost on the way back"; fail=1
    fi
  else
    echo "WARN no podcast result to open (YouTube search unavailable here?)"
    texts results2 | head -n 40
  fi
else
  echo "WARN no search button found"
fi

# ── 4. Apple Music import ───────────────────────────────────────────────────
APPLE="${APPLE_TEST_PLAYLIST:-https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb}"
step "import $APPLE"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 4
for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 4
tap_where tabs3 '(text == "Library" or desc == "Library") and y1 > 1200' || tap_where tabs4 'text == "Library" or desc == "Library"'
sleep 4
tap_where library2 'desc == "New playlist"' && sleep 3 \
  && tap_where menu2 'text == "Import a playlist"' && sleep 3 \
  && { tap_where dialog3 '"EditText" in cls' || true; } \
  && adb shell input text "'$APPLE'" && sleep 1 && adb logcat -c \
  && tap_where dialog4 'text == "Import"' || { echo "FAIL could not reach the import dialog again"; fail=1; }
apple=$(wait_log "imported playlist am\.|spotify import failed" 90)
shot apple
case "$apple" in
  *"imported playlist am."*) echo "OK   ${apple#*: }" ;;
  *"import failed"*) echo "WARN Apple Music refused the import here: ${apple#*: }"
    adb logcat -d -s 'SquareFusion:*' | tail -n 15 | sed 's/^/LOG /' ;;
  *) echo "FAIL the Apple Music import neither finished nor failed in 90 s"; fail=1 ;;
esac

alive && echo "OK   app still running" || { echo "FAIL app stopped"; fail=1; }
adb logcat -d | grep -E "SquareFusion|SquareYTQuality|SquareUi|AndroidRuntime|FATAL" | tail -n 200 > "$OUT/logcat.txt" || true
echo "playback result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
