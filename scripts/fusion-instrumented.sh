#!/bin/bash
# Runs Square Fusion's instrumented tests (app/src/androidTest) on the
# emulator, against the debug build. The release APK the smoke test installed
# is removed first: the two may be signed differently.
set -u
DIR="$1"
PKG=dev.lelonio.square.fusion
adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install -r -g "$DIR/app-debug.apk" || { echo "FAIL install debug"; exit 1; }
adb install -r -g "$DIR/app-debug-androidTest.apk" || { echo "FAIL install tests"; exit 1; }
out=$(adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | grep -E "INSTRUMENTATION_STATUS: (class|test)=|INSTRUMENTATION_STATUS_CODE|INSTRUMENTATION_CODE|OK \(|FAILURES|Tests run|stack=" | sed 's/INSTRUMENTATION_STATUS: //'
echo "$out" | grep -A25 "INSTRUMENTATION_STATUS: stack=" | head -n 200
if echo "$out" | grep -q "^OK ("; then echo "instrumented result: PASS"; exit 0; fi
echo "instrumented result: FAIL"
exit 1
