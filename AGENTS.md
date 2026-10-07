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

**The delivered APK was built on GitHub Actions, not natively on Termux.** Use `.github/workflows/android.yml` as the executable source of truth. Its build runner is `ubuntu-24.04`, with Temurin JDK 17, the checked-in Gradle wrapper and the runner's Linux Android SDK. Do not add the Termux AAPT2 override or phone-specific worker/compiler flags to this route.

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

## Alternative route: native Termux build (not yet verified)

The remaining numbered sections are a proposed **local Termux alternative**, not the procedure used for the delivered APK. When these instructions were originally written, the host lacked Java, native build tools and a configured SDK. Inspect current prerequisites before trying this route; package candidates and shell-syntax checks are not proof of a working native build. Do not install tools or call this route verified without authorization and an actual successful assembly.

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
./gradlew --version
```

`ANDROID_HOME` avoids needing a `local.properties` file. If one already exists, inspect its non-secret SDK setting for a stale `sdk.dir`; a stale local setting can override the environment. `android/local.properties` is ignored by Git. Never commit it.

## 3. Build a debug APK

From `android/`, run:

```sh
./gradlew --no-daemon --max-workers=2 \
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
./gradlew --no-daemon --max-workers=2 \
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

## 5. Build a signed distribution release

Use the existing distribution key, stored outside the repository. The Gradle configuration requires:

- `ANDROID_KEYSTORE_PATH`: absolute path to the private PKCS12 keystore.
- `ANDROID_KEYSTORE_PASSWORD`: supplied privately through the environment.
- Key alias: `whisper-dictation`.

Do not generate a replacement key for an update. Ask for the existing key if it is unavailable. Protect and back up that key outside the checkout.

Once the credentials are securely supplied:

```sh
: "${ANDROID_KEYSTORE_PATH:?Existing distribution keystore required}"
: "${ANDROID_KEYSTORE_PASSWORD:?Supply the password privately}"
test -f "$ANDROID_KEYSTORE_PATH"

./gradlew --no-daemon --max-workers=2 \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
aapt dump badging app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
unset ANDROID_KEYSTORE_PASSWORD
```

A release without the signing environment is unsigned, normally `app-release-unsigned.apk`; do not deliver it as installable. Check the certificate against the previous distribution APK before calling a release update-compatible. Release builds enable code minification and resource shrinking.

## Troubleshooting and completion requirements

- **`java: command not found` / invalid `JAVA_HOME`:** resolve the installed JDK first.
- **SDK location missing:** check `ANDROID_HOME`, `platforms/android-35/android.jar`, licenses and any existing `local.properties`.
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
