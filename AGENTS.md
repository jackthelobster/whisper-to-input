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
- The workflow runs on pushes to `master`, `feat/**` and `fix/**`, on pull requests targeting `master`, or through `workflow_dispatch`.
- A signed distribution APK requires a trusted push or manual dispatch in `jackthelobster/whisper-to-input`. Pull-request builds intentionally do not receive the distribution key and are not the signed delivery path.

After authorization, a manual build can be started from the repository root with the remote branch or tag containing the intended changes:

```sh
: "${BUILD_REF:?Set the authorized remote branch or tag to build}"
gh workflow run android.yml --repo jackthelobster/whisper-to-input --ref "$BUILD_REF"
gh run list --repo jackthelobster/whisper-to-input --workflow android.yml \
  --limit 10 --json databaseId,event,headSha,status,conclusion
```

Identify the newly dispatched run by its event and source SHA, not just its position in the list. For an automatic push build, select that push run instead of dispatching a duplicate.

### 2. Build and verify on the Linux runner

The workflow's exact Gradle command, executed from `android/`, is:

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease assembleDebug assembleDebugAndroidTest
```

The existing Gradle signing configuration reads `ANDROID_KEYSTORE_PATH` and `ANDROID_KEYSTORE_PASSWORD`; its PKCS12 key alias is `whisper-dictation`. The trusted CI build restores the existing key from repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`, restricts the temporary file to mode 600, supplies those environment variables and removes the runner key in an `always()` cleanup step. Reuse that identity; do not generate a replacement key or commit/signing-log its contents.

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

Use the Android 12 emulator workflow above for reproducible screenshots. Check for an available emulator/ADB connection before promising a local capture; a successful native Termux APK build does not provide an emulator. CI captures the selected remote commit, so local uncommitted UI changes must first reach that revision with authorization for any commit/push/dispatch. Follow sections 1–4 above to select, run and verify the exact source and download its artifacts; reuse an automatic push run rather than dispatching a duplicate.

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

Expected captures are `settings-{light,dark}.png`, `keyboard-{light,dark}.png`, `keyboard-listening-{light,dark}.png` and `keyboard-retry-{light,dark}.png`. Open the actual PNGs with the available image-viewing tool and inspect alignment, backgrounds, text clipping and the requested states in both themes. Parse device JUnit XML under `$VERIFY_DIR/device/outputs/androidTest-results/`; do not infer test success from screenshots alone. Missing states or invisible controls require fixing the fixture and rerunning, not accepting the filename as evidence.

When reporting, include the run URL, exact source SHA, emulator configuration, actual test results, and absolute image links (or embedded images). Keep screenshots/reports outside the checkout in the run-specific verification directory. Historical example: run `38631573890`, source `d45d54fd5ecc058c0f25d3559ead5ee6737f2bd1`, produced the light/dark retry captures reviewed on 2026-10-14; this does not verify later working-tree changes. Report debug emulator rendering separately from release runtime or physical-handset acceptance.

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

Instrumentation requires an actual connected Android test device or a compatible emulator; building its APK is not running its tests. Use the Linux Android 12 emulator job in `.github/workflows/android.yml` when there is no usable device connection. Do not claim handset dictation, microphone or keyboard acceptance from Gradle assembly alone.

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
