# Security and correctness review

Review date: 3 October 2026. Baseline: upstream commit ca2fcf3b4351e697735e9953e474ef9cfd5980e5.
Target: Samsung Galaxy S10e, Android 12 (API 31).

This is a source review and regression-test effort, not a penetration-test certification. The intended threat model includes accidental recording, exposure of credentials/audio, untrusted endpoints, malformed responses, and text being inserted into the wrong application.

## Findings in the upstream baseline

| Priority | Finding | Location in baseline | Implemented remediation |
|---|---|---|---|
| High | Backup enabled, API key stored as plaintext in preferences; backup/extraction rules are unconfigured templates. | MainActivity.kt API_KEY, manifest and backup XML | Keystore-backed AES-GCM encrypted key; exclude all settings from cloud and device-transfer backup. |
| High | Global HTTP cleartext traffic permitted. Credentials may be sent to user-configured insecure URLs. | AndroidManifest.xml; WhisperTranscriber.kt | Standard TLS for general endpoints; HTTP exception only for exact gx10; explicit local-HTTP switch; do not send API keys over HTTP; no redirects. |
| High | Late transcription uses whatever currentInputConnection happens to be active, not the editor that initiated recording. | WhisperInputService.kt transcriptionCallback | Cancel on editor/window transitions and verify an editor-generation token and captured connection before insertion. |
| High | Recording automatically starts by default, without password-field protections. | MainActivity.kt and WhisperInputService.kt | Opt-in auto-recording, default off; block password and sensitive fields. |
| Medium | Audio stored in an external cache under a fixed filename, retained after failure/cancel, and may collide with another request. | WhisperInputService.kt; WhisperTranscriber.kt | Unique private-cache files; clean up cancelled/hidden/successful sessions and stale recordings; same-editor-only retry. |
| Medium | Cancelling the coroutine does not cancel blocking OkHttp execute; no timeout configuration, and responses are not consistently closed. | WhisperTranscriber.kt | Cancellable asynchronous calls, explicit timeouts, bounded bodies, closed responses and cancellation propagation. |
| Medium | Raw server error bodies are logged and shown to the user. | WhisperTranscriber.kt | Safe status-specific error messages; no raw server-body, key or transcript logging. |
| Medium | Recorder prepare exceptions are logged but start is still invoked; stop throws for extremely short/invalid recordings. | RecorderManager.kt | Handle prepare/start/stop failures, release in finally, remove invalid audio, never upload a failed recording. |
| Medium | Unnecessary notification permission is required for microphone operation, including on Android 12 where this runtime permission does not exist. | RecorderManager.kt; MainActivity.kt | Require only RECORD_AUDIO; remove unused notification and storage permissions. |
| Medium | Null-asserted recorder lifecycle, asynchronous config/record races and unmanaged coroutine scopes can crash or outlive their UI. | WhisperInputService.kt; MainActivity.kt | Owned activity/service lifetimes and null-safe teardown. |
| Medium | OpenAI mode demands an API key, sends language as an ASR query parameter rather than multipart, and cannot parse JSON transcription replies. | WhisperTranscriber.kt | Optional bearer auth, model/language multipart fields, text and JSON response handling for compatible endpoints. |
| Low | Long-press delete does not handle touch cancellation or detach; deleting UTF-16 units can split emoji; Enter sends only key-down. | BackspaceButton.kt; WhisperInputService.kt | Stop repeat on cancel/detach, code-point-aware deletion, appropriate editor actions and paired key events. |
| Low | Several small controls have missing/wrong accessibility descriptions; fixed sizes and raster graphics are outdated. | keyboard_view.xml | Scalable vector icons, Material surfaces, 48dp targets, accessible status/action labels, day/night themes. |

## Endpoint verification

The corrected gx10:8020 server was queried directly, without an API key:

- GET /openapi.json: title "Distil-Whisper speech-to-text API".
- GET /health: ready, CUDA device available.
- GET /v1/models: model distil-whisper/distil-large-v3.
- POST /v1/audio/transcriptions with an actual AAC M4A audio file, multipart model, language=en and response_format=json: returned recognized English text.

The test input was the public JFK fixture from the official OpenAI Whisper repository, transcoded to mono AAC at 16 kHz to match the app's planned upload format. Returned text began "And so, my fellow Americans" and included both "ask not" sentences. No user recording or private text was used.

The server schema accepts English only (en/english), despite the upstream keyboard's general multilingual description. The fork's default language is en. The server advertises no authentication requirement. Endpoint settings are configuration, not embedded secrets.

## Residual risks and verification boundary

- HTTP to gx10 is deliberately permitted for this user-approved private server. HTTP itself provides no transport encryption. Use only on a trusted network or through an encrypted VPN such as the user's own Tailscale connection; prefer HTTPS when available.
- The device must resolve gx10 and reach port 8020. Reachability from this development host does not prove reachability from the S10e.
- A transcription server receives submitted recordings. This code cannot establish that server's retention policy or secure its host.
- Blocking password input types reduces risk but cannot detect a sensitive field that another app incorrectly labels as ordinary text.
- Keystore protection does not protect a rooted/compromised device while the app is using a decrypted key in memory.
- The separately identified fork installs alongside the upstream app. Signing keys are private and backed up outside the repository. Future updates must use the same signing key.
- Selected runtime dependencies were checked against OSV on the review date; see dependency-advisories.json. No advisory matched those selected versions. This is not an exhaustive transitive dependency audit.
- Physical Samsung microphone, One UI input-method switching, network/VPN DNS and power-management behavior require a real-device acceptance test.

## Verification status

The application and test changes were verified at commit 41c139b3f9f076db1ed992f27968fd9c6047a4c5 in jackthelobster/whisper-to-input. GitHub Actions push run 37134115716 and pull-request run 37134118406 both completed successfully on 3 October 2026.

| Check | Executed result |
|---|---|
| JVM regression tests | 45 tests across seven suites; zero failures, errors or skipped tests |
| Android lint | lintRelease passed with abortOnError enabled |
| APK assembly | Release, debug and instrumentation APKs built successfully |
| Android 12 instrumentation | Four tests passed; zero failures, errors or skipped tests |
| Actual IME service | Selected through Android's input-method manager; microphone enabled in an ordinary editor and disabled in a password editor |
| Credential protection | Actual Android Keystore encryption/read-back passed; app preference files checked for the dummy plaintext key |
| Network/permission policy | Exact gx10 cleartext exception and absence of unused storage/notification permissions verified on Android 12 |
| Interface rendering | Six screenshots exported successfully at 1080 × 2280 / 480 dpi; light/dark settings and ready/listening keyboard images visually reviewed |
| Release signature | apksigner verification passed, APK Signature Scheme v2, one 4096-bit RSA signer |
| APK manifest | com.jackthelobster.whispertoinput; version 0.5.0 / code 5; minimum API 24, target API 35 |
| Live custom endpoint | Actual AAC M4A transcription against gx10:8020 passed using the public JFK fixture |

Exact signed APK SHA-256: 2e9cc39570921b085334548b739e32ed20e0c1fc090fcde01834a2115a8ca5fe.

Signing certificate SHA-256: bd068398a89597db9009b1350b013ac7aa8bbc5f205396f8b4513ce11f988853.

Instrumentation exercised the debug build. The minified release APK was assembled and its signature/manifest/hash verified; it has not been exercised on a physical Samsung device. The live server request was issued from the development host, not the S10e. This distinction is intentional: emulator checks and a server fixture are not proof of Samsung microphone behavior or the handset's private-network connectivity.

The initial emulator workflow failures were traced to screenshot teardown and direct-process command execution in the test harness. These were corrected rather than suppressing the failures. The successful runs include an assertion that the expected IME really was selected and that every exported screenshot existed.

Physical-device acceptance remains pending. Follow INSTALL_ANDROID12.md to install and test microphone capture, a full handset-to-server dictation round-trip, cancellation/editor switching, Unicode deletion, Samsung keyboard switching, large fonts and landscape. No claim of penetration-test certification, comprehensive transitive dependency clearance or physical-device verification is made.

## Additional integration safeguards

- API-key ciphertext is stored with an endpoint binding in the same committed preference write. A torn settings save cannot silently send a new key to the previous endpoint; mismatched settings require explicit re-entry.
- An unreadable or invalidated Keystore credential causes a sanitized error, not a silent anonymous fallback. The settings screen has an explicit recovery path, and only a completed Save restores usable settings.
- If switching to a previous keyboard fails, this keyboard remains usable and opens the system picker rather than staying disabled.
- Source, GPL license and upstream attribution are preserved. Upstream ASR-specific protocols, NIM-specific OGG mode and Chinese conversion options are intentionally outside this fork's OpenAI-compatible multipart focus and are described as such in the README.
