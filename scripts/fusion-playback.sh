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
greet=$(grep -oE 'text="Good (morning|afternoon|evening)[^"]*"' "$OUT/home.xml" | head -n 1)
hour=$(adb shell date +%H | tr -d '\r')
if [ -n "$greet" ]; then echo "OK   greeting on the home page at ${hour}h: $greet"; else echo "FAIL no greeting on the home page"; fail=1; texts home | head -n 8; fi
grep -q 'text="Square"' "$OUT/home.xml" && { echo "FAIL the home page still says Square"; fail=1; }
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
  # Typed in pieces: the emulator's keyboard drops letters from a long burst.
  q="Flippe Go Sleep"
  for ((i = 0; i < ${#q}; i++)); do
    c="${q:$i:1}"
    if [ "$c" = " " ]; then adb shell input keyevent KEYCODE_SPACE; else adb shell input text "$c"; fi
    sleep 0.4
  done
  sleep 2
  dump typed
  grep -q 'text="Flippe Go Sleep' "$OUT/typed.xml" && echo "OK   query typed" || echo "WARN query typed as: $(grep -o 'EditText[^>]*' "$OUT/typed.xml" | grep -o 'text="[^"]*"' | head -n 1)"
  adb shell input keyevent KEYCODE_ENTER
  sleep 12
  dump results1
  # The filters are a row wider than the screen: brought into view first.
  if ! tap_where results 'text == "Podcasts" and y1 < 150'; then
    chip_y=$(python3 - "$OUT/results1.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'text="Songs"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', xml)
print((int(m.group(1)) + int(m.group(2))) // 2 if m else 57)
PY
)
    found=0
    for swipe in 1 2 3; do
      adb shell input swipe 300 "$chip_y" 30 "$chip_y" 400
      sleep 2
      dump results1b
      if tap_where results1b 'text == "Podcasts" and y1 < 150'; then found=1; break; fi
    done
    [ $found = 1 ] || { echo "WARN no Podcasts filter in the results"; texts results1b | head -n 12; }
  fi
  sleep 5
  dump results2
  shot results
  if [ "$(selected_chip results2 Podcasts)" = yes ]; then echo "OK   Podcasts filter selected"; else echo "WARN the Podcasts filter does not read as selected"; fi
  if tap_where results3 '"flippe" in text.lower() and "EditText" not in cls and y1 > 90'; then
    sleep 15
    dump podcast
    shot podcast
    if grep -q 'text="No songs"' "$OUT/podcast.xml"; then
      echo "FAIL the podcast opened from search shows no episodes"; fail=1
    else
      rows=$(grep -o 'text="[^"]*FLIPPE[^"]*"' "$OUT/podcast.xml" | wc -l)
      if [ "$rows" -gt 0 ]; then
        echo "OK   podcast opened from search with $rows episode rows on screen"
      else
        echo "WARN the opened page shows no episode row"; texts podcast | head -n 20
      fi
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
      texts back | head -n 30
    fi
  else
    echo "WARN no podcast result to open (YouTube search unavailable here?)"
    texts results2 | head -n 12
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
apple_ok=0
apple=$(wait_log "imported playlist am\.|spotify import failed" 90)
shot apple
case "$apple" in
  *"imported playlist am."*) echo "OK   ${apple#*: }"; apple_ok=1 ;;
  *"import failed"*) echo "WARN Apple Music refused the import here: ${apple#*: }"
    adb logcat -d -s 'SquareFusion:*' | tail -n 15 | sed 's/^/LOG /' ;;
  *) echo "FAIL the Apple Music import neither finished nor failed in 90 s"; fail=1 ;;
esac

# ── 5. Song lengths on an imported Spotify playlist ─────────────────────────
if [ -f "$OUT/playlist.xml" ]; then
  dashes=$(grep -o 'text="--:--"' "$OUT/playlist.xml" | wc -l)
  lengths=$(grep -oE 'text="[0-9]+:[0-9]{2}"' "$OUT/playlist.xml" | wc -l)
  if [ "$dashes" != 0 ]; then echo "FAIL imported playlist shows $dashes --:--"; fail=1
  elif [ "$lengths" -gt 0 ]; then echo "OK   imported playlist: $lengths song lengths on screen, no --:--"
  else echo "WARN no song length visible on the imported playlist's first screen"; fi
fi

# ── 6. Edit a playlist's name: kept, shown, and still there after a restart ──
to_library() {
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 3
  for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 4
  tap_where "$1" '(text == "Library" or desc == "Library") and y1 > 400' || tap_where "$1-b" 'text == "Library" or desc == "Library"'
  sleep 4
}
long_press_where() {
  dump "$1"
  local b
  b=$(python3 - "$OUT/$1.xml" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    text = re.search(r'text="([^"]*)"', n).group(1)
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    if eval(sys.argv[2]):
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  [ -n "$b" ] || return 1
  adb shell input swipe $b $b 900
}
# A song of the open page added to the imported Apple Music playlist: listed
# in the sheet, then found in that playlist after a restart.
add_to_playlist() {
  step "add to a playlist"
  adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 4)) 500; sleep 3
  dump add-page
  local title
  title=$(python3 - "$OUT/add-page.xml" "$screen_h" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8").read()
h = int(sys.argv[2])
nodes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = html.unescape(re.search(r'text="([^"]*)"', n).group(1))
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    nodes.append((t, d, x1, y1, x2, y2))
more = next((n for n in nodes if n[1] == "More" and n[3] > 150 and n[5] < h * 3 // 4), None)
if more:
    for t, d, x1, y1, x2, y2 in nodes:
        if len(t) > 1 and x2 <= more[2] and more[3] <= (y1 + y2) // 2 <= more[5]:
            print(t); break
PY
)
  echo "LOG song to add: $title"
  sleep 5
  if ! menu_time song-add "$song_more" 'text="(Go to artist|Copy link|Add to queue|Add to a playlist)"'; then
    close_menu; sleep 3
    menu_time song-add2 "$song_more" 'text="(Go to artist|Copy link|Add to queue|Add to a playlist)"' || return
  fi
  if ! tap_where add-entry 'text == "Add to a playlist"'; then
    # Lower in the sheet on a short screen.
    adb shell input swipe 160 $((screen_h * 4 / 5)) 160 $((screen_h / 3)) 400; sleep 2
    tap_where add-entry2 'text == "Add to a playlist"' || { echo "FAIL no Add to a playlist in the song menu"; fail=1; texts add-entry2 | head -n 20; close_menu; return; }
  fi
  sleep 6
  dump add-sheet
  shot add-sheet
  grep -oE 'text="[^"]+"' "$OUT/add-sheet.xml" | head -n 12 | sed 's/^/LOG sheet /'
  if ! grep -qE 'text="Today(&apos;|.)s Hits"' "$OUT/add-sheet.xml"; then
    if [ "$apple_ok" = 1 ]; then echo "FAIL the imported Apple Music playlist is not offered"; fail=1; else echo "WARN no Apple Music playlist to add to (its import failed here)"; fi
    close_menu; return
  fi
  echo "OK   Add to a playlist lists the imported playlists, not only Liked"
  tap_where add-target 'text.startswith("Today") and text.endswith("s Hits")'; sleep 4
  adb shell am force-stop "$PKG"; sleep 2
  # Its page through the import of the same link, which opens the stored playlist.
  to_library add-lib
  tap_where add-plus 'desc == "New playlist"' && sleep 3 \
    && tap_where add-menu 'text == "Import a playlist"' && sleep 3 \
    && { tap_where add-field '"EditText" in cls' || true; } \
    && adb shell input text "'$APPLE'" && sleep 1 \
    && tap_where add-import 'text == "Import"' && sleep 15 \
    || { echo "WARN could not reopen the Apple Music playlist"; return; }
  local i
  for i in $(seq 1 14); do
    dump add-check
    if [ -n "$title" ] && grep -qF "text=\"$title\"" "$OUT/add-check.xml"; then
      echo "OK   the song is in the Apple Music playlist after a restart"; shot add-check
      # Whole: an artist and a length under its title, not an empty row.
      line=$(python3 - "$OUT/add-check.xml" "$title" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8").read()
nodes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = html.unescape(re.search(r'text="([^"]*)"', n).group(1))
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    nodes.append((t, x1, y1, x2, y2))
row = next((n for n in nodes if n[0] == sys.argv[2]), None)
if row:
    under = [t for t, x1, y1, x2, y2 in nodes if t and t != row[0] and row[4] - 2 <= y1 <= row[4] + 30 and abs(x1 - row[1]) < 10]
    print(under[0] if under else "")
PY
)
      if [ -n "$line" ] && echo "$line" | grep -q "[0-9]:[0-9][0-9]"; then echo "OK   the added song shows its artist and length: $line"
      else echo "FAIL the added song's row lacks its artist or length (\"$line\")"; fail=1; fi
      return
    fi
    adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 5)) 300; sleep 1
  done
  shot add-check
  echo "FAIL the added song is not in the playlist after a restart"; fail=1
}
step "rename a playlist"
open_imported() {
  # The imported playlist's page, through the Library's import: the same link
  # imported again opens the same playlist, freshly read from Spotify.
  to_library "$1"
  tap_where "$1-plus" 'desc == "New playlist"' && sleep 3 \
    && tap_where "$1-menu" 'text == "Import a playlist"' && sleep 3 \
    && { tap_where "$1-field" '"EditText" in cls' || true; } \
    && adb shell input text "'$LINK'" && sleep 1 \
    && tap_where "$1-import" 'text == "Import"' && sleep 15
}
# Menus: how long each takes to open, a few times over; on a stall, what the
# main thread is doing.
trace_main() {
  adb root > /dev/null 2>&1; sleep 3
  local pid
  pid=$(adb shell pidof "$PKG" | tr -d '\r')
  [ -n "$pid" ] || { echo "LOG no process to trace"; return; }
  adb shell kill -3 "$pid"; sleep 4
  local file
  file=$(adb shell 'ls -t /data/anr/ 2>/dev/null | head -n 1' | tr -d '\r')
  adb shell cat "/data/anr/$file" 2>/dev/null > "$OUT/trace-$1.txt"
  awk '/^"main"/{on=1} on{print "LOG " $0; n++} n>=45{exit}' "$OUT/trace-$1.txt"
}
menu_time() {
  # $1 label, $2 tap rule, $3 what the open menu shows (regex)
  tap_where "$1" "$2" || { echo "WARN $1: nothing to tap"; return 1; }
  local start ms i
  start=$(date +%s%N)
  for i in $(seq 1 40); do
    if adb shell uiautomator dump /sdcard/m.xml > /dev/null 2>&1 && adb shell cat /sdcard/m.xml | grep -qE "$3"; then
      ms=$(( ($(date +%s%N) - start) / 1000000 ))
      echo "OK   $1 opened in ${ms} ms"
      return 0
    fi
    sleep 0.5
  done
  echo "FAIL $1 did not open within 20 s"; fail=1
  texts "$1" | head -n 20
  trace_main "$1"
  return 1
}
step "menus on Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
screen_h=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n 1 | cut -dx -f2)
# Closes a menu as a finger does: a tap on the dimmed page beside it.
close_menu() { adb shell input tap 6 $((screen_h / 2)); sleep 2; }
open_imported page-diag
adb shell dumpsys gfxinfo "$PKG" reset > /dev/null 2>&1
for n in 1 2 3; do
  adb logcat -c
  menu_time "playlist-menu-$n" 'desc == "More" and y1 < 120' 'text="(Edit|Copy link)"' || break
  if [ "$n" = 1 ]; then
    if adb shell cat /sdcard/m.xml | grep -qE 'text="(Remove from library|Get YTDLnis|Download with YTDLnis)"'; then
      echo "FAIL the imported playlist's menu still offers Remove from library or YTDLnis"; fail=1
    else
      echo "OK   imported playlist's menu: no Remove from library, no YTDLnis"
    fi
    adb shell cat /sdcard/m.xml | grep -q 'text="Delete"' && echo "OK   Delete still offered" || echo "WARN Delete not on the menu's first screen"
  fi
  # The menu left open and untouched: the Android 11 blur must settle.
  sleep 5
  adb logcat -c
  sleep 4
  idle=$(adb logcat -d -s 'SquareGlass:*' | grep -c "android 11 blur")
  echo "LOG blur recomputations logged over 4 s with the menu idle: $idle"
  close_menu
done
# The songs up into the middle of the screen, clear of the mini player.
adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 4)) 500; sleep 3
song_more="desc == 'More' and y1 > 150 and y2 < $((screen_h * 3 / 4))"
for n in 1 2 3; do
  menu_time "song-menu-$n" "$song_more" 'text="(Go to artist|Copy link|Add to queue)"' || break
  if [ "$n" = 1 ]; then
    adb shell cat /sdcard/m.xml | grep -q 'text="Play next"' && echo "OK   song menu: Play next" || { echo "FAIL no Play next in the song menu"; fail=1; }
    if adb shell cat /sdcard/m.xml | grep -qE 'text="(Get YTDLnis|Download with YTDLnis)"'; then
      echo "FAIL the song menu still offers YTDLnis"; fail=1
    else
      echo "OK   song menu: no YTDLnis"
    fi
  fi
  close_menu
done
# An action from a song's menu, then the page is still usable.
if menu_time "song-menu-download" "$song_more" 'text="(Download|Go to artist)"'; then
  if tap_where song-download 'text == "Download"'; then sleep 3; echo "OK   Download tapped from the song menu"; else close_menu; fi
fi
adb shell input swipe 160 $((screen_h / 4)) 160 $((screen_h * 3 / 4)) 500; sleep 3
menu_time "playlist-menu-after" 'desc == "More" and y1 < 120' 'text="(Edit|Copy link)"' && close_menu
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|90th|99th|Number Frame deadline missed" | sed 's/^/LOG /'
adb logcat -d | grep -E "Choreographer.*Skipped|ANR in|Input dispatching timed out" | tail -n 5 | sed 's/^/LOG /'
alive || { echo "FAIL app stopped during the menus"; fail=1; }

open_imported page-a
if tap_where page-more 'desc == "More" and y1 < 120'; then
  sleep 4
  # Once more if the sheet is not up yet.
  if ! grep -q 'text="Edit"' <(adb shell uiautomator dump /dev/tty 2>/dev/null); then
    tap_where page-more2 'desc == "More" and y1 < 120'; sleep 5
  fi
  if tap_where edit-menu 'text == "Edit"'; then
    sleep 3
    dump edit-dialog
    shot edit-dialog
    if grep -q 'text="Change cover"' "$OUT/edit-dialog.xml"; then echo "FAIL the edit window still has Change cover"; fail=1; else echo "OK   edit window without Change cover"; fi
    tap_where edit-field '"EditText" in cls' || true
    adb shell input keyevent KEYCODE_MOVE_END
    for i in $(seq 1 40); do adb shell input keyevent KEYCODE_DEL; done
    for c in F u s i o n T e s t; do adb shell input text "$c"; sleep 0.3; done
    tap_where edit-save 'text == "Save"'
    sleep 4
    dump renamed
    shot renamed
    if grep -q 'could not be saved' "$OUT/renamed.xml"; then echo "FAIL the rename showed an error"; fail=1; fi
    if grep -q 'text="FusionTest"' "$OUT/renamed.xml"; then echo "OK   renamed: the page shows the new name"; else echo "FAIL the new name is not shown"; fail=1; texts renamed | head -n 15; fi
    adb shell am force-stop "$PKG"
    sleep 2
    open_imported page-b
    dump restarted
    shot restarted
    if grep -q 'text="FusionTest"' "$OUT/restarted.xml"; then
      echo "OK   the name is still there after a restart and a new read from Spotify"
    else
      echo "FAIL the name was lost on restart"; fail=1; texts restarted | head -n 15
    fi
    add_to_playlist
  else
    echo "FAIL no Edit in the playlist's menu"; fail=1; texts edit-menu | head -n 15
  fi
else
  echo "WARN no More button on the imported playlist's page"; texts page-more | head -n 15
fi

# ── 7. Titles on one line, settings index and About ─────────────────────────
# A title node of one line: present, inside the screen, and no taller than
# one line of its text ($3 px, measured on this emulator's density).
one_line() {
  python3 - "$OUT/$1.xml" "$2" "$3" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    text = re.search(r'text="([^"]*)"', n).group(1)
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    if eval(sys.argv[2]):
        ok = (y2 - y1) <= int(sys.argv[3]) and x1 >= 0 and not text.endswith("…")
        print(("OK  " if ok else "FAIL"), repr(text), "bounds", x1, y1, x2, y2)
        break
else:
    print("WARN no such title:", sys.argv[2])
PY
}
step "titles on one line"
density=$(adb shell wm density | grep -oE '[0-9]+' | tail -n 1)
to_library lib-title-nav
dump library-title
shot library-title
r=$(one_line library-title 'text == "Library" and y1 < 200' $((64 * density * 13 / 10 / 160)))
echo "$r (Library header)"; case "$r" in FAIL*) fail=1 ;; esac
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_where home-title-nav '(text == "Home" or desc == "Home") and y1 > 400' || true
sleep 4
dump greeting
shot greeting
r=$(one_line greeting 'text.startswith("Good ")' $((28 * density * 13 / 10 / 160)))
echo "$r (home greeting)"; case "$r" in FAIL*) fail=1 ;; esac

step "settings and About"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 3
for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 4
tap_where tabs-home '(text == "Home" or desc == "Home") and y1 > 400' || true
sleep 3
if tap_where avatar 'clickable and x1 > 230 and y2 < 160'; then
  sleep 4
  dump settings
  shot settings
  if grep -qi 'Support development\|ko-fi' "$OUT/settings.xml"; then echo "FAIL the support tile is still there"; fail=1; else echo "OK   no support tile; $(grep -oE 'text="(Playback|Equalizer|Glass|Downloads|App|About)"' "$OUT/settings.xml" | wc -l) page tiles"; fi
  # The Glass page: its settings on this Android 11 emulator, not the "no hardware blur" note.
  if tap_where settings-glass 'text == "Glass"'; then
    sleep 4
    dump glass
    shot glass
    if grep -q 'no hardware blur' "$OUT/glass.xml"; then echo "FAIL the Glass page says this phone has no blur"; fail=1
    elif grep -q 'text="Colour"' "$OUT/glass.xml"; then echo "OK   Glass page shows its settings on Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
    else echo "WARN Glass page: settings not recognised"; texts glass | head -n 15; fi
    adb shell input keyevent KEYCODE_BACK
    sleep 3
    dump settings
  fi
  if tap_where settings-about 'text == "About"'; then
    sleep 4
    # The page may still be sliding in, and the section sits lower down.
    for i in 1 2 3 4 5 6; do
      dump about
      grep -qi 'text="Developed by"' "$OUT/about.xml" && break
      sleep 2
      [ "$i" -gt 2 ] && adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 4)) 400 && sleep 2
    done
    shot about
    if grep -qi 'ko-fi\|Support development' "$OUT/about.xml"; then echo "FAIL About still has the support links"; fail=1; texts about | head -n 20; else echo "OK   About without support links"; fi
    # The section title is drawn in capitals.
    if grep -qi 'text="Developed by"' "$OUT/about.xml" && grep -q 'github.com/Lelonio' "$OUT/about.xml"; then echo "OK   About: Developed by Lelonio"; else echo "FAIL About does not show Developed by Lelonio"; fail=1; texts about | head -n 20; fi
    grep -q 'httpsMrAbdelhak' "$OUT/about.xml" && echo "OK   About shows this repository" || echo "WARN this repository is not on the first screen of About"
  else
    echo "WARN no About tile"; texts settings | head -n 20
  fi
else
  echo "WARN could not open the settings from the profile picture"
fi

# ── 8. Home: long press, sections; content country ─────────────────────────
open_home() {
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 3
  for i in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  sleep 4
  tap_where "$1" '(text == "Home" or desc == "Home") and y1 > 400' || true
  sleep 6
}
open_settings_app() {
  open_home "$1-home"
  tap_where "$1-avatar" 'clickable and x1 > 230 and y2 < 160' && sleep 4 \
    && tap_where "$1-tile" 'text == "App"' && sleep 4
}
type_slowly() { local c; for c in $(echo "$1" | sed 's/./& /g'); do adb shell input text "$c"; sleep 0.3; done; }

step "long press on a Home song"
open_home home-menu
dump home-before
shot home-before
if long_press_where home-song 'x1 >= 80 and x1 <= 100 and y1 > 250 and y2 < 560 and len(text) > 1'; then
  sleep 3
  dump home-song-menu
  shot home-song-menu
  if grep -q 'text="Play next"' "$OUT/home-song-menu.xml" && grep -q 'text="Add to queue"' "$OUT/home-song-menu.xml"; then
    echo "OK   long press on a Home song opens its menu, with Play next and Add to queue"
  else
    echo "FAIL long press on a Home song did not open the song menu"; fail=1; texts home-song-menu | head -n 20
  fi
  close_menu
else
  echo "WARN no song row found on the Home page"; texts home-before | head -n 20
fi

step "content country"
adb logcat -c
if open_settings_app country && tap_where country-group 'text == "Music content country"'; then
  sleep 3
  for name in Algeria France; do
    tap_where "country-field-$name" '"EditText" in cls' || true
    adb shell input keyevent KEYCODE_MOVE_END
    for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DEL; done
    type_slowly "${name:0:4}"
    sleep 2
    adb shell input keyevent KEYCODE_BACK; sleep 1
    tap_where "country-$name" "text == \"$name\"" || { echo "FAIL $name not offered"; fail=1; texts "country-$name" | head -n 15; continue; }
    sleep 3
    code=$([ "$name" = Algeria ] && echo DZ || echo FR)
    if adb logcat -d -s 'SquareFusion:*' | grep -q "youtube region $code (chosen: $code)"; then echo "OK   $name chosen: YouTube Music asked for region $code"; else echo "FAIL choosing $name did not reach YouTube Music"; fail=1; fi
  done
  dump country-chosen
  grep -q 'text="France"' "$OUT/country-chosen.xml" && echo "OK   the heading shows France" || echo "WARN France not shown under the heading"
  tap_where country-field-clear '"EditText" in cls' || true
  adb shell input keyevent KEYCODE_MOVE_END
  for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DEL; done
  adb shell input keyevent KEYCODE_BACK; sleep 1
  tap_where country-auto 'text == "Automatic"' || { adb shell input swipe 160 $((screen_h / 3)) 160 $((screen_h * 3 / 4)) 300; sleep 2; tap_where country-auto2 'text == "Automatic"'; }
  sleep 3
  if adb logcat -d -s 'SquareFusion:*' | grep -q "chosen: automatic"; then echo "OK   back to Automatic"; else echo "FAIL Automatic was not applied"; fail=1; texts country-auto | head -n 25; fi
  adb shell am force-stop "$PKG"; sleep 2
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 6
  adb logcat -d -s 'SquareFusion:*' | grep "youtube region" | tail -n 1 | sed 's/^/LOG after restart: /'
else
  echo "FAIL could not reach Settings, App, Music content country"; fail=1
fi

step "home sections"
if open_settings_app sections && tap_where appearance-group 'text == "Appearance"'; then
  sleep 3
  first=""
  for i in 1 2 3 4 5; do
    dump sections
    first=$(python3 - "$OUT/sections.xml" "$OUT/home-before.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8").read()
nodes = [(html.unescape(re.search(r'text="([^"]*)"', m.group(0)).group(1)), int(re.search(r'bounds="\[\d+,(\d+)\]', m.group(0)).group(1))) for m in re.finditer(r'<node [^>]*>', xml)]
head = next((y for t, y in nodes if t.lower() == "home sections"), None)
if head is not None:
    after = [t for t, y in nodes if y > head and t and t.lower() != "home sections"]
    home = open(sys.argv[2], encoding="utf-8").read()
    # One the Home page showed on its first screen, so its going can be seen.
    on_home = [t for t in after if 'text="%s"' % t in html.unescape(home)]
    if on_home or after: print((on_home or after)[0])
PY
)
    [ -n "$first" ] && break
    adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 4)) 400; sleep 2
  done
  if [ -z "$first" ]; then
    echo "FAIL no Home sections in Appearance"; fail=1; texts sections | head -n 20
  else
    echo "LOG first section: $first"
    tap_where section-off "text == \"$first\"" && sleep 2
    open_home sections-home
    dump sections-home
    shot sections-home
    if grep -qF "text=\"$first\"" "$OUT/sections-home.xml"; then echo "FAIL \"$first\" still on the Home page"; fail=1
    elif grep -qF "text=\"$first\"" "$OUT/home-before.xml"; then echo "OK   \"$first\" switched off: gone from the Home page"
    else echo "WARN \"$first\" was not on the first screen of the Home page before either"; fi
    # Back on, as it was.
    open_settings_app sections2 && tap_where appearance-group2 'text == "Appearance"' && sleep 3
    for i in 1 2 3 4 5; do tap_where section-on "text == \"$first\"" && break; adb shell input swipe 160 $((screen_h * 3 / 4)) 160 $((screen_h / 4)) 400; sleep 2; done
  fi
else
  echo "FAIL could not reach Settings, App, Appearance"; fail=1
fi

alive && echo "OK   app still running" || { echo "FAIL app stopped"; fail=1; }
adb logcat -d | grep -E "SquareFusion|SquareYTQuality|SquareUi|AndroidRuntime|FATAL" | tail -n 200 > "$OUT/logcat.txt" || true
echo "playback result: $([ $fail = 0 ] && echo PASS || echo FAIL)"
exit $fail
