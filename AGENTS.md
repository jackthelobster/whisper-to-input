# Agent instructions: building and verifying APKs

## Scope and repository rules

- This is a native Kotlin Android application. Run Gradle from `android/`, not the repository root.
- Inspect `git status --short` before editing. Preserve existing user changes; do not reset, clean, commit, push, or install an APK unless requested.
- Use the checked-in `./gradlew`, not a separately installed Gradle. Do not upgrade the build configuration merely to accommodate the host.
- Keep the checkout, SDK and Gradle cache in Termux private storage, not `/sdcard` (shared storage has unsuitable executable/permission semantics).
- Never put signing keys, passwords or SDK paths into tracked files. Do not print credentials or enable shell tracing when signing.

## Project toolchain

Read these files again if the build configuration changes:

| Requirement | Current repository value | Source |
| --- | --- | --- |
| Java | JDK 17 | `android/app/build.gradle.kts`, CI |
| Gradle | 8.7, checksum-pinned wrapper | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.6.1 | `android/build.gradle.kts` |
| Kotlin plugin | 1.9.24 | `android/build.gradle.kts` |
| Compile / target SDK | 35 / 35 | `android/app/build.gradle.kts` |
| Minimum SDK | 24 | `android/app/build.gradle.kts` |
| Application ID | `com.jackthelobster.whispertoinput` | `android/app/build.gradle.kts` |

## Primary route: verified GitHub Actions build

**The historical `v0.5.0-andrew.1` APK was built on GitHub Actions.** A signed local Termux build has since succeeded; see the local route below. Use `.github/workflows/android.yml` as the executable source of truth. Its build runner is `ubuntu-24.04`, with Temurin JDK 17, the checked-in Gradle wrapper and the runner's Linux Android SDK. Do not add the Termux AAPT2 override or phone-specific worker/compiler flags to this route.

The previously delivered `v0.5.0-andrew.1` APK came from successful push run `37134115716`, with application/test commit `41c139b3f9f076db1ed992f27968fd9c6047a4c5`. The accompanying documentation-only release commit was `37f5a52ecd8e3830989c3d6ef0d76ceda9f21546`. These identify a historical verified build, not verification of later commits or the current dirty working tree.

### 1. Select the exact authorized source

- Inspect the working tree first. CI builds the selected remote revision, not local uncommitted changes. Do not claim that a CI APK includes changes present only on this phone.
- Committing, pushing and dispatching a workflow require the user's authorization. Do not do them merely because this document provides commands.
- The workflow runs only on pull requests targeting `master` and through `workflow_dispatch`. Push builds were intentionally removed in `787586c`; merges to `master` receive no automatic post-merge CI verification. To verify the merged revision, obtain authorization and manually dispatch that exact remote revision.
- A signed distribution APK from this workflow requires a manual dispatch in `jackthelobster/whisper-to-input`. Pull-request builds intentionally do not receive the distribution key and are not the signed delivery path.
- The secret-backed OpenRouter smoke check (`scripts/check-openrouter.py`) also runs only on manual dispatches in `jackthelobster/whisper-to-input`; passing pull-request CI does not verify it.

After authorization, a manual build can be started from the repository root with the remote branch or tag containing the intended changes:

```sh
: "${BUILD_REF:?Set the authorized remote branch or tag to build}"
gh workflow run android.yml --repo jackthelobster/whisper-to-input --ref "$BUILD_REF"
gh run list --repo jackthelobster/whisper-to-input --workflow android.yml \
  --limit 10 --json databaseId,event,headSha,status,conclusion
```

Identify the newly dispatched run by its `workflow_dispatch` event and source SHA, not just its position in the list. Reuse an existing manual run only if it verifies the intended source SHA; there are no automatic push runs.

### 2. Build and verify on the Linux runner

The workflow's exact Gradle command, executed from `android/`, is:

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease assembleDebug assembleDebugAndroidTest
```

The existing Gradle signing configuration reads `ANDROID_KEYSTORE_PATH` and `ANDROID_KEYSTORE_PASSWORD`; its PKCS12 key alias is `whisper-dictation`. The trusted manual CI build restores the existing key from repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`, restricts the temporary file to mode 600, supplies those environment variables and removes the runner key in an `always()` cleanup step. Reuse that identity; do not generate a replacement key or commit/signing-log its contents.

The runner verifies the distribution APK with these SDK tools, also from `android/`:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

The workflow records these results beside the APK as `signature.txt`, `badging.txt` and `SHA256SUMS`. Build Tools 35.0.0 here is the artifact-inspection version; it is not an instruction to change AGP's selected build-tools version. Both `build` and `android12` must succeed before claiming the workflow's full verification passed.

### 3. Android 12 device verification

The `android12` job uses an API 31 `google_apis` / `x86_64` emulator on the Linux runner, configured to 1080 × 2280 pixels and 480 dpi. It runs `./gradlew --no-daemon connectedDebugAndroidTest` from `android/`. This is **debug-build instrumentation**, not minified-release runtime testing or physical-Samsung acceptance.

Keep the test harness's screenshot export and IME selection checks intact. AGP uninstalls the tested APK at teardown, so test screenshots are exported to `/sdcard/whisper-verification` before removal and then pulled into `android/app/build/screenshots`. Only dummy test data belongs there; never disable production `FLAG_SECURE` or add storage permissions to capture credentials.

The emulator action executes script lines separately. A `cd` on one line does not set the next line's directory; preserve the combined `cd android && ./gradlew ...` and the explicit repository-relative artifact paths.

### 4. Monitor and retrieve the exact build

After selecting the run and the expected application source SHA:

```sh
: "${RUN_ID:?Set the selected workflow run ID}"
: "${EXPECTED_SHA:?Set the exact remote application commit expected in this build}"
gh run watch "$RUN_ID" --repo jackthelobster/whisper-to-input --exit-status --interval 30
```

Stop and inspect failed logs if the watch command returns nonzero. Before accepting artifacts, verify the completed run again:

```sh
test "$(gh run view "$RUN_ID" --repo jackthelobster/whisper-to-input --json conclusion --jq .conclusion)" = success
test "$(gh run view "$RUN_ID" --repo jackthelobster/whisper-to-input --json headSha --jq .headSha)" = "$EXPECTED_SHA"
```

Do not continue if either check fails. Download both artifacts into a new run-specific directory:

```sh
VERIFY_DIR="$HOME/projects/whisper-to-input-verification/run-$RUN_ID"
gh run download "$RUN_ID" --repo jackthelobster/whisper-to-input \
  --name android-build-and-reports --dir "$VERIFY_DIR/build"
gh run download "$RUN_ID" --repo jackthelobster/whisper-to-input \
  --name android12-device-verification --dir "$VERIFY_DIR/device"

RELEASE_DIR="$VERIFY_DIR/build/outputs/apk/release"
test -f "$RELEASE_DIR/app-release.apk"
read -r expected_hash recorded_path < "$RELEASE_DIR/SHA256SUMS"
actual_hash=$(sha256sum "$RELEASE_DIR/app-release.apk")
test "${actual_hash%% *}" = "$expected_hash"
```

Stop if the file or hash check fails. The downloaded signed APK is `build/outputs/apk/release/app-release.apk` within that run directory, not the build-time `android/app/build/...` path. CI artifacts are retained for 14 days; do not substitute an older release when a new build's artifacts are unavailable.

Review the signature/manifest reports, parse the actual JUnit XML for totals/failures/skips, inspect device-test results and visually review the screenshots. A build job alone or the presence of cached APKs is insufficient. If publishing a release, obtain authorization, keep the license/corresponding source and evidence with the APK, then download the uploaded APK again and compare its SHA-256. A documentation-only commit after verification must be proved not to change application/tests/build files, with both source identities recorded.

## Taking app screenshots

For screenshots or a live app preview without GitHub, use the verified Android 12 emulator on `comet` described below. The GitHub Actions workflow above remains available for CI verification. Check the selected emulator/ADB connection before promising captures; a successful native Termux APK build does not provide an emulator. Comet can build a copy of the current working tree without committing it. CI captures the selected remote commit, so uncommitted UI changes must first reach that revision with authorization for any commit/push/dispatch. For CI, follow sections 1–4 above: reuse a manual dispatch for the intended source SHA if one exists, or obtain authorization to dispatch it. Pushing alone does not start a build.

The screenshot harness is `settingsAndKeyboardRenderLightAndDarkAtS10eWidth` in `android/app/src/androidTest/java/com/example/whispertoinput/ExampleInstrumentedTest.kt`. It renders settings and keyboard views on the API 31 emulator in light and dark mode, using dummy data. Its `capture()` helper calls `view.draw(Canvas(bitmap))`; these PNGs show app views, not full-device screenshots with system bars or proof of a live microphone/server interaction. Keep production `FLAG_SECURE` enabled and do not add storage permissions or capture real credentials.

For additional UI states, extend this test using the keyboard's public state methods, measure/layout views before capture when geometry changes, and give each capture a distinct filename. Retry requires both `showError(...)` and a `shouldShowRetry` callback returning `true` (representing retained audio); assert `btn_retry` is visible before capturing it. An error message alone can leave retry hidden. Preserve the existing IME selection tests and screenshot export.

The helper initially writes PNGs under the app's external-files `screenshots/` directory, then exports them to `/sdcard/whisper-verification` using the instrumentation shell identity **before AGP uninstalls the test app**. Keep that export: files in the app directory disappear at teardown. `UiAutomation.executeShellCommand` runs a process directly, so do not assume shell operators or quoting are interpreted. The workflow pulls the exported directory into `android/app/build/screenshots/whisper-verification` and uploads it in `android12-device-verification`.

After the run/SHA and artifact checks in section 4, locate the downloaded images:

```sh
: "${VERIFY_DIR:?Set the run-specific artifact directory from section 4}"
SCREENSHOT_DIR="$VERIFY_DIR/device/screenshots/whisper-verification"
test -d "$SCREENSHOT_DIR"
find "$SCREENSHOT_DIR" -maxdepth 1 -type f -name '*.png' -print
```

Expected captures are `settings-{light,dark}.png`, `keyboard-{light,dark}.png`, `keyboard-listening-{light,dark}.png`, `keyboard-retry-{light,dark}.png` and `keyboard-shift-enter-{light,dark}.png` (ten PNGs). Open the actual PNGs with the available image-viewing tool and inspect alignment, backgrounds, text clipping and the requested states in both themes. Parse device JUnit XML under `$VERIFY_DIR/device/outputs/androidTest-results/`; do not infer test success from screenshots alone. Missing states or invisible controls require fixing the fixture and rerunning, not accepting the filename as evidence.

When reporting, include the run URL, exact source SHA, emulator configuration, actual test results, and absolute image links (or embedded images). Keep screenshots/reports outside the checkout in the run-specific verification directory. Historical example: run `38631573890`, source `d45d54fd5ecc058c0f25d3559ead5ee6737f2bd1`, produced the light/dark retry captures reviewed on 2026-10-14; this does not verify later working-tree changes. Report debug emulator rendering separately from release runtime or physical-handset acceptance.

## Android 12 emulator on comet: builds, screenshots and live view

Use `ssh andrew@comet`. This is an x86-64 Lubuntu VMware VM with 8 GiB RAM and four virtual CPUs. Nested virtualization is enabled; `andrew` belongs to `kvm`, and `emulator -accel-check` verified usable KVM. If `/dev/kvm` disappears or the emulator reports missing `vmx`/`svm`, shut down the VM and enable VMware's **Virtualize Intel VT-x/EPT or AMD-V/RVI** processor setting on its host. A guest package install cannot expose that CPU feature. Reconnect/log in after changing group membership.

### Installed environment and live desktop

- Build JDK: OpenJDK 17. Source `~/Android/Sdk/env.sh` in each remote build shell; it sets the SDK environment and Java 17.
- SDK management JDK: OpenJDK 21, selected by `~/android-setup/sdkmanager.sh`. Use that wrapper for SDK queries/installation; the current command-line tools require the newer Java runtime. Review any new licenses interactively.
- Installed SDK: Platform 35, Build Tools 34.0.0 and 35.0.0, Platform Tools 37.0.1, Emulator 37.2.12, command-line tools 22.0. These are recorded versions, not instructions to upgrade them on every run.
- AVD: `whisper-api31`, using `system-images;android-31;default;x86_64` revision 5 (standard AOSP Android 12). It has 1080 × 2280 pixels, 480 dpi, 2048 MiB guest RAM, two guest CPUs, software graphics and a 2048M data partition. It does **not** use CI's `google_apis` image. No additional Preview license was accepted for this setup.
- Initial source copy: `~/projects/whisper-to-input`; Gradle cache: `~/.gradle`. Inspect free space before large downloads/builds: the VM's 30 GiB disk had about 6.5 GiB free after initial verification.
- Host-only helpers and logs: `~/android-setup/`. These scripts are outside the repository; inspect them before reuse. `latest-verification.md` records the initial successful build. Initial `sdk.log` contains a superseded Google APIs license attempt; `sdk-packages.log` and `avd.log` record the completed setup.

For a live view, log into the Lubuntu desktop and open VMware's console (or separately configured remote desktop). SSH by itself does not display the emulator. The existing **Whisper Android 12 Emulator** application-menu entry runs `~/android-setup/start-emulator.sh`. From SSH, first check for an existing emulator so a second instance is not launched:

```sh
ssh andrew@comet 'source "$HOME/Android/Sdk/env.sh"; emulator -accel-check; adb devices -l'
# Only if whisper-api31 is not already running:
ssh andrew@comet 'nohup "$HOME/android-setup/start-emulator.sh" > "$HOME/android-setup/emulator.log" 2>&1 < /dev/null &'
```

The launcher resolves the active LXQt session's Xauthority and opens a visible window on `DISPLAY=:0`. Its emulator options include `-avd whisper-api31 -gpu software -memory 2048 -cores 2 -no-boot-anim -no-snapshot -camera-back none -camera-front none -no-metrics`. Do not replace the desktop with Xvfb or use `-no-window` when the user wants a live view. No network VNC service or remotely exposed ADB listener was configured. The initial ADB serial was `emulator-5554`; verify it each time.

### Copy the intended working tree without GitHub

Inspect `git status --short` on the phone and review the files to transfer. Include intended uncommitted/untracked source, but exclude ignored caches, `local.properties`, APKs and credentials. Do not copy `.git`, signing backups or the phone's Gradle cache. Use a fresh remote source directory for each run to avoid stale files from earlier snapshots; preserve existing remote work.

From the phone's repository root, in Bash:

```sh
set -o pipefail
COMET_RUN_DIR=$(ssh andrew@comet 'mkdir -p "$HOME/projects/whisper-to-input-runs"; mktemp -d "$HOME/projects/whisper-to-input-runs/run.XXXXXX"')
test -n "$COMET_RUN_DIR"
git ls-files -z --cached --others --exclude-standard |
  tar --null -T - -czf - |
  ssh andrew@comet "tar -xzf - -C '$COMET_RUN_DIR'"
```

Stop if copying fails. Record the local base commit, dirty status and file SHA-256 manifest outside the checkout, and verify the copied files against that manifest. A dirty snapshot is not identified by its base commit alone. The initial source copy does not automatically receive later phone edits. Do not commit/push just to use comet.

### Build and capture on the selected emulator

The following runs from the phone, using `COMET_RUN_DIR` from the copy step. Confirm `emulator-5554` is the intended disposable AVD first. Instrumentation resets app settings to dummy values and Gradle uninstalls the tested app at teardown; never target the user's physical phone or an emulator containing settings that must be preserved. This command installs test APKs, so use it within the user's authorized emulator testing scope.

```sh
ssh andrew@comet bash -s -- "$COMET_RUN_DIR" <<'REMOTE'
set -euo pipefail
source "$HOME/Android/Sdk/env.sh"
export ANDROID_SERIAL=emulator-5554
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
cd "$1/android"
bash ./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb shell wm size 1080x2280
adb shell wm density 480
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# This fixed export directory contains only the harness's dummy PNGs.
adb shell rm -f '/sdcard/whisper-verification/*.png'
bash ./gradlew --no-daemon --max-workers=2 \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.example.whispertoinput.ExampleInstrumentedTest#settingsAndKeyboardRenderLightAndDarkAtS10eWidth' \
  :app:connectedDebugAndroidTest
REMOTE
```

Stop on any failed build/test. Use Linux SDK binaries on comet: **no Termux AAPT2 override or phone-specific Kotlin compiler flags**. Use the checked-in wrapper and JDK 17; do not change build configuration for this host. The filtered command runs only the screenshot fixture, not the full instrumentation suite. To run the full suite, omit the runner-arguments property on a separate authorized run.

After success, pull `/sdcard/whisper-verification` through ADB into a new host evidence directory outside the source tree. Retrieve that directory over SSH/rsync into a new `~/projects/whisper-to-input-verification/comet-<run-id>/` directory on the phone. Also retrieve:

- `app/build/test-results/testDebugUnitTest/` and `app/build/reports/`;
- `app/build/outputs/androidTest-results/` (parse its actual JUnit XML);
- `app/build/outputs/apk/debug/app-debug.apk`, build/test logs, source identity and signature/hash reports.

For example, on comet, with `COMET_VERIFY_DIR` set to a new absolute evidence directory and from the selected snapshot's `android/` directory:

```sh
: "${COMET_VERIFY_DIR:?Set a new directory outside the source tree}"
set -euo pipefail
source "$HOME/Android/Sdk/env.sh"
mkdir -p "$COMET_VERIFY_DIR"
adb -s emulator-5554 pull /sdcard/whisper-verification "$COMET_VERIFY_DIR/"
java -jar "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" verify \
  --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk > "$COMET_VERIFY_DIR/signature.txt"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging \
  app/build/outputs/apk/debug/app-debug.apk > "$COMET_VERIFY_DIR/badging.txt"
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$COMET_VERIFY_DIR/SHA256SUMS"
```

Verify the downloaded APK against the host hash and inspect the manifest/signature. Comet's debug signing identity is distinct from both the distribution key and another host's debug key. Do not install this APK over the phone's release or transfer distribution signing keys to comet for screenshot tests. Visually inspect all ten PNGs in both themes; preserve the harness's export, `FLAG_SECURE`, and IME checks. These are dummy-data app-view captures, not full-device screenshots or live dictation evidence.

After instrumentation teardown, reinstall the just-built debug APK **on the emulator** to restore the live app, then open its settings:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n \
  com.jackthelobster.whispertoinput/com.example.whispertoinput.MainActivity
adb -s emulator-5554 shell dumpsys activity activities |
  grep -E 'mResumedActivity|topResumedActivity'
```

The reusable `~/android-setup/run-screenshots.sh` targets the **initial** `~/projects/whisper-to-input` copy and checks its original `build.log`; it does not select or synchronize a new snapshot. Prefer the explicit commands above for new changes rather than treating that helper or old logs as evidence of a fresh build.

### Initial verified result (2026-10-15)

The initial dirty source snapshot was based on `380a1a45fe0b01abb5b5bc0dba93cb34212679b6`. Its exact copied files, dirty status, reports and reviewed screenshots are recorded on this phone under `~/projects/whisper-to-input-verification/comet-20261015T222314Z-Dgp0bD/`, including `REPORT.md`, `source-SHA256SUMS` and `source-copy-check.txt`.

The build above succeeded (77 tasks executed); all 80 unit tests passed with zero failures/errors/skips. Debug lint had zero errors and 104 warnings. The selected screenshot instrumentation test passed (one test, zero failures/errors/skips; two Gradle tasks executed and 62 up-to-date), producing ten reviewed PNGs. The debug APK verified with APK Signature Scheme v2 and SHA-256 `c8e855d4fd15eb62af80f73379fda73044419a8240268549001ed205c5ace448`. Its original host path is `~/projects/whisper-to-input/android/app/build/outputs/apk/debug/app-debug.apk`; a copy is in the phone's evidence directory. The app was reinstalled and its settings activity confirmed in the foreground of the visible emulator.

This records that snapshot/artifact only. It does not certify later changes, the full instrumentation suite, release runtime behavior, physical-handset acceptance, or live microphone/transcription-server interaction. Report comet evidence with its snapshot identity and absolute image/report links; there is no GitHub run URL for this route.

## Local route: verified native Termux build

Use this route when the user requests a local build. It requires no GitHub commit, push or workflow dispatch and includes the current working tree. On 2026-10-15, `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` succeeded locally with the original distribution key (unit tests were up-to-date). The resulting APK's certificate matched the historical GitHub APK. Its SHA-256 was `c07818530b0fb1882af7cf2d3546d458361992254f5baf95c886fe4a3fd5fefc`. This records that artifact only, not verification of future changes; installation and release runtime tests were not performed.

Java 17, the SDK and Termux AAPT2 were available for that build. Inspect current prerequisites before installing anything; provisioning instructions below are for missing prerequisites. Use `bash ./gradlew` on Termux: direct execution of the checked-in wrapper can fail because its `/usr/bin/env` shebang is unavailable. Do not edit the wrapper to fix the host.

## 1. Install native Termux prerequisites

Review the proposed installation before changing the host:

```sh
pkg install openjdk-17 aapt aapt2 apksigner curl unzip
```

Set the environment in the shell that will run Gradle:

```sh
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$HOME/.gradle"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$ANDROID_HOME" "$GRADLE_USER_HOME" "$TMPDIR"
java -version
command -v aapt2 apksigner
file "$PREFIX/bin/aapt2"
aapt2 version
```

If the JDK package uses a different directory, inspect its installed files with `dpkg -L openjdk-17` and set `JAVA_HOME` to the directory containing `bin/java`. Do not guess another JDK path.

**Important:** Google's downloaded Linux SDK native binaries and Gradle's Maven AAPT2 binary must not be assumed compatible with Android/ARM64. Use the Termux-native AAPT2 override in every Gradle invocation below. Installing a proot Linux distribution alone does not resolve a CPU architecture mismatch.

## 2. Provision the Android SDK

Use Google's official Android SDK command-line tools **Linux ZIP**, choosing a revision compatible with JDK 17. Obtain its URL and published checksum from the official Android Studio download page; verify the download before extracting. Do not blindly reuse an old ZIP name or download an arbitrary third-party SDK bundle. If newer command-line tools require a newer Java runtime, use a compatible tools revision or a separate JDK for SDK provisioning; keep the application build on its documented JDK 17 toolchain.

After downloading the verified archive to a local path, set `CMDLINE_TOOLS_ZIP` to that path and run:

```sh
: "${CMDLINE_TOOLS_ZIP:?Set this to the verified downloaded Linux command-line-tools ZIP}"
staging=$(mktemp -d "$TMPDIR/android-sdk-tools.XXXXXX")
unzip -q "$CMDLINE_TOOLS_ZIP" -d "$staging"
mkdir -p "$ANDROID_HOME/cmdline-tools"
# Stop rather than overwrite an existing installation.
test ! -e "$ANDROID_HOME/cmdline-tools/latest" && \
  mv "$staging/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
rmdir "$staging"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager --sdk_root="$ANDROID_HOME" --version
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0"
```

Review and accept SDK licenses interactively. AGP 8.6 uses Build Tools 34.0.0 by default; the existing CI also uses 35.0.0 for artifact inspection. Installing both avoids assuming that the inspection version is the version selected by Gradle. Do not execute downloaded x86 Linux binaries on this ARM64 host. `sdkmanager` is Java-based; verify it actually starts with the installed JDK.

Check the SDK platform and wrapper before assembly:

```sh
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
cd "$HOME/projects/whisper-to-input/android"
bash ./gradlew --version
```

`ANDROID_HOME` avoids needing a `local.properties` file. If one already exists, inspect its non-secret SDK setting for a stale `sdk.dir`; a stale local setting can override the environment. `android/local.properties` is ignored by Git. Never commit it.

## 3. Build a debug APK

From `android/`, run:

```sh
bash ./gradlew --no-daemon --max-workers=2 \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug
```

Output, relative to the repository root:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use the debug signing identity. They are not a substitute for a distribution release signed with the existing private key. Do not uninstall an existing release just to bypass a signature mismatch: that can destroy app data.

Avoid `clean` for routine incremental builds. The project already limits the Gradle heap to 2 GiB; keep worker concurrency low on the phone. If a build is killed, inspect the actual failure and available resources before increasing the heap or launching more parallel builds.

## 4. Run local verification

```sh
bash ./gradlew --no-daemon --max-workers=2 \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug

apksigner verify --verbose --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Review `app/build/reports/tests/testDebugUnitTest/`, `app/build/test-results/testDebugUnitTest/` and the lint report under `app/build/reports/`. Report actual task outcomes, not merely the existence of cached output files.

Instrumentation requires an actual connected Android test device or a compatible emulator; building its APK is not running its tests. Use the Android 12 emulator on comet above, or the Linux emulator job in `.github/workflows/android.yml`, when there is no usable device connection on the phone. Do not claim handset dictation, microphone or keyboard acceptance from Gradle assembly alone.

## 5. Build a signed distribution release with the original key

For an update that preserves installed app data, use the original distribution signing identity. A default debug APK uses a different certificate and can produce “app conflicts with another package” when installed over the GitHub release. Building locally is compatible with updates when the application ID and signing identity match and the version code is not lower than the installed version. Never uninstall the release to work around this conflict.

The existing Gradle release configuration reads `ANDROID_KEYSTORE_PATH` and `ANDROID_KEYSTORE_PASSWORD` and uses PKCS12 alias `whisper-dictation`. The local backup found on this phone is outside the checkout:

- Keystore: `$HOME/Synced/Backups/S21/whisper-to-input/signing/whisper-dictation.p12`
- Password file: `$HOME/Synced/Backups/S21/whisper-to-input/signing/keystore-password`
- Public certificate: `$HOME/Synced/Backups/S21/whisper-to-input/signing/signing-certificate.pem`
- Expected certificate SHA-256: `bd068398a89597db9009b1350b013ac7aa8bbc5f205396f8b4513ce11f988853`

Do not generate a replacement key. If these files are unavailable, ask for the existing key and password through a private local mechanism. Keep backups protected outside the checkout. Never print or copy the password/key contents into chat, logs, commands, tracked files or shell history; do not enable shell tracing. The paths above identify files, not permission to disclose their contents.

After setting the JDK/SDK environment from section 1, run from `android/`. This reads the password directly into a child-process environment and does not persist it in the calling shell:

```sh
uv run --no-project python - <<'PYTHON'
import os
import subprocess
from pathlib import Path

signing = Path.home() / "Synced/Backups/S21/whisper-to-input/signing"
keystore = signing / "whisper-dictation.p12"
password_file = signing / "keystore-password"
assert keystore.is_file() and password_file.is_file(), "Original signing files required"
env = os.environ.copy()
env["ANDROID_KEYSTORE_PATH"] = str(keystore)
env["ANDROID_KEYSTORE_PASSWORD"] = password_file.read_text().rstrip("\r\n")
assert env["ANDROID_KEYSTORE_PASSWORD"], "Signing password is empty"
result = subprocess.run([
    "bash", "./gradlew", "--no-daemon", "--max-workers=2",
    "-Pandroid.aapt2FromMavenOverride=" + str(Path(env["PREFIX"]) / "bin/aapt2"),
    "-Pkotlin.compiler.execution.strategy=in-process",
    ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease",
], env=env)
raise SystemExit(result.returncode)
PYTHON
```

Stop if Gradle fails. Do not accept an older cached APK as the new build. The output is `android/app/build/outputs/apk/release/app-release.apk`, relative to the repository root. A release without signing credentials is normally `app-release-unsigned.apk`; do not deliver it as installable. Release builds enable minification and resource shrinking.

### Verify the signed local APK

From `android/`, run the following after a successful build. Use the SDK's Java-based `apksigner.jar` and Termux-native AAPT2: the downloaded SDK's Linux `aapt` binary is x86-64 and cannot run natively on this ARM64 phone. A standalone `apksigner` command is not required.

```sh
uv run --no-project python - <<'PYTHON'
import hashlib
import os
import subprocess
from pathlib import Path

apk = Path("app/build/outputs/apk/release/app-release.apk")
jar = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/lib/apksigner.jar"
report = subprocess.check_output([
    "java", "-jar", str(jar), "verify", "--verbose", "--print-certs", str(apk)
], text=True)
certificates = [line.split(": ", 1)[1] for line in report.splitlines()
                if "certificate SHA-256 digest:" in line]
assert certificates == ["bd068398a89597db9009b1350b013ac7aa8bbc5f205396f8b4513ce11f988853"], "Distribution certificate mismatch"
(apk.parent / "signature.txt").write_text(report)
badging = subprocess.check_output([
    str(Path(os.environ["PREFIX"]) / "bin/aapt2"), "dump", "badging", str(apk)
], text=True)
(apk.parent / "badging.txt").write_text(badging)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(apk.parent / "SHA256SUMS").write_text(digest + "  app-release.apk\n")
print(report)
print(badging)
print("SHA-256:", digest)
print("APK:", apk.resolve())
PYTHON
```

Stop on any verification or certificate mismatch. The expected certificate above was also checked against the actual APK from GitHub run `37134115716`, locally retained under `$HOME/projects/whisper-to-input-verification/run-37134115716/build/outputs/apk/release/app-release.apk`. When available, verify the previous installed/distributed APK with the same `apksigner.jar` and compare certificates directly. Inspect `badging.txt` for application ID `com.jackthelobster.whispertoinput` and the version code before claiming update compatibility. Parse the unit-test XML and review release lint results as described above; report cached tasks honestly. Keep generated reports beside the untracked APK. Installing the APK still requires the user's request; successful assembly and signature verification do not establish runtime acceptance.

## Troubleshooting and completion requirements

- **`java: command not found` / invalid `JAVA_HOME`:** resolve the installed JDK first.
- **SDK location missing:** explicitly export `ANDROID_HOME` and `ANDROID_SDK_ROOT` in the shell running Gradle; verify `platforms/android-35/android.jar`, licenses and any existing `local.properties`. If needed, write `sdk.dir` to ignored `android/local.properties` using the verified local SDK path. Never write an empty value or commit this file.
- **AAPT2 daemon failure / executable format error:** run `$PREFIX/bin/aapt2 version` directly and confirm the Gradle command includes the override. Do not disable resource validation or lower `compileSdk` to hide the failure.
- **Another SDK executable fails:** identify its exact path and architecture using `file`. Use a corresponding native Termux tool if available; do not replace unrelated SDK files or fabricate tools to satisfy existence checks.
- **Download failure:** verify connectivity and the configured Google/Maven Central repositories; do not remove wrapper checksum validation or TLS verification. Offline mode only works after all dependencies are cached.
- **Installation rejected:** compare application ID and signing certificate; do not silently uninstall the installed app.
- **Local native build remains blocked:** report the exact blocker and use the existing GitHub Actions build only with permission for any commit/push/dispatch. Clearly distinguish a CI-produced APK from a locally built APK.

A completed build report must include the executed Gradle tasks, actual success/failure, exact APK path, signature verification result and SHA-256. Do not claim runtime testing unless it was performed. Preserve the user's working tree and do not commit artifacts or credentials.

## Primary references

- Google Android SDK download page: `https://developer.android.com/studio#command-tools`
- Google sdkmanager documentation: `https://developer.android.com/tools/sdkmanager`
- Termux native Android build tools: `https://github.com/termux/android-build-tools`
- Repository CI: `.github/workflows/android.yml`
- Handset installation and acceptance: `docs/INSTALL_ANDROID12.md`
