#!/usr/bin/env bash
# Runs inside the CI emulator: instrumented tests (offline sample mode and real backend sign-in via
# 10.0.2.2), then pulls device screenshots and generates a Baseline Profile. Screenshots are pulled
# even when tests fail so failures can be inspected.
set -uo pipefail

PORT="${SAMFORD_BACKEND_PORT:-4321}"
status=0

./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.itBase=http://10.0.2.2:${PORT}" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=$?

mkdir -p device-screenshots
adb pull /sdcard/Android/data/com.samford.patient.debug/files/screenshots device-screenshots/ || true
adb shell dumpsys gfxinfo com.samford.patient.debug > device-screenshots/gfxinfo.txt || true

# Baseline Profile for the release build (startup, records, a handwritten page with pinch-zoom).
./gradlew --no-daemon :app:generateReleaseBaselineProfile || echo "Baseline Profile generation failed (non-blocking)"

exit $status
