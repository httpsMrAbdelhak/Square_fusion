# BitChord Glass

BitChord, unchanged in what it does, with Square's Liquid Glass on the parts
chosen for it. BitChord is the base; Square is a source of components.

## Branches and remotes

- `bitchord-glass`: the app. Its history is BitChord's own history plus the
  fork's commits, so an official release merges like any upstream change.
- `bitchord` remote: https://github.com/kushagrasinghx/BitChord.git
- `glass-wip`: work in progress, compile check only.

## Layout

| Where | What |
| --- | --- |
| `app/src/main/java/com/music/bitchord/glass/ui/` | Square's glass, vendored as is (`dev.lelonio.square.ui.glass` renamed): backdrop library fork, LiquidBottomTabs, LiquidButton, LiquidSlider, FloatingTabBar, shapes. |
| `glass/theme/` | The four theme values Square's glass reads, taken from BitChord's theme. |
| `glass/data/GlassStore.kt` | Square's glass settings store (profiles, sliders, tint, where the glass goes). |
| `glass/settings/GlassSettings.kt` | Square's glass settings page. |
| `glass/integration/` | The bridge: Square's glass samples BitChord's page recording; the settings page host. |
| `glass/nav/SquareNavBar.kt` | Square's navigation bar with BitChord's tabs and now playing pill; Square's search field in the bar. |
| `glass/effects/` | Square's effects: pitch, reverb (EnvironmentalReverb aux effect), presets, panel. Tempo is BitChord's own speed setting. |
| `glass/player/` | The player's view switcher, effects and credits views, and the seek bar's glass bubble. |
| `glass/update/` | Background update check and notification. |
| `res/values*/glass_strings.xml` | Every string the fork adds. |

## Hooks into BitChord's own files

Each is a few lines and says `BitChord Glass` beside it.

- `build.gradle.kts`: version from `-PglassBuild`.
- `app/build.gradle.kts`, `wearos/build.gradle.kts`: `.glass` package suffix on prod; phosphor icons; update APK prefix.
- `BitChordApplication.kt`: schedules the update check.
- `data/AppUpdateChecker.kt`: reads the fork's release; refuses an APK whose SHA-256 does not match.
- `MainActivity.kt`: provides Square's glass; draws `SquareNavBar` in place of `GlassNavBar`; the glass settings page route; the top bar's field steps aside while the bar holds one.
- `ui/components/GlassNavBar.kt`: `GlassNowPlaying` made `internal` (reused in the bar).
- `ui/components/FrostedTopBar.kt`: floating controls use Square's button glass.
- `ui/screens/DetailScreen.kt`: Play, Shuffle, Download and Add use Square's button glass.
- `ui/screens/SettingsSheet.kt`: the Liquid Glass settings row and choice.
- `playback/PlaybackService.kt`: `GlassEffects.attach` on each player built.
- `ui/player/AndroidPlayerHost.kt`: implements the three glass hooks.
- `sharedUi/.../PlayerHost.kt`: three hooks with no-op defaults (desktop untouched).
- `sharedUi/.../NowPlayingScreen.kt`, `PlayerControls.kt`: the switcher above the action row; effects and credits in the queue's place; the glass seek bar except while BitChord draws its loading, mix or transition marks.

## Taking an official BitChord release

1. `git fetch bitchord --tags`
2. `git checkout bitchord-glass && git merge <tag or bitchord/main>`
3. Conflicts can only be in the files listed above; keep BitChord's change and re-apply the marked lines.
4. Push: CI builds, tests and publishes `bitchord-latest`.

## Updates on the phone

The app checks `releases/tags/bitchord-latest` of this repository at launch and
every 12 hours in the background (notification). The APK is downloaded inside
the app, its SHA-256 compared with the one GitHub publishes for the asset (or
the `.sha256` file beside it), and handed to the system installer.

## Licences

BitChord and Square are both GPL-3.0; the fork is too.
