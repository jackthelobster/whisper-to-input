# Whisper To Input v0.6.0

Whisper To Input v0.6.0 brings the fork's redesigned Android voice keyboard and independent fallback-server support into a stable release.

[Download the signed Android APK](https://github.com/jackthelobster/whisper-to-input/releases/download/v0.6.0/whisper-to-input-v0.6.0.apk) · [Setup guide](https://github.com/jackthelobster/whisper-to-input#install-and-start-dictating)

## Changes since v0.5.0-andrew.1

- Independent URL, model, language, and API key for the main and fallback servers, with concurrent availability checks and preference for a verified main server.
- Model-discovery failures can leave dictation available with an unverified status; unavailable endpoints have backoff, timeout handling, and manual refresh.
- Auxiliary voice-input registration for switching from a typing keyboard, plus a back arrow available throughout dictation states.
- Optional Shift+Enter beside a shorter spacebar, connection-state controls, and light/dark keyboard refinements.
- Credential recovery, recording ownership, cancellation, and editor-operation fixes, with expanded unit and Android instrumentation coverage.
- A rewritten project guide with fresh Android 12 screenshots captured on comet, clear server setup, and a direct APK download.

## Installation

Requires Android 7.0 or newer and a reachable OpenAI-compatible transcription server. This release contains version name **0.6.0** and version code **6**, application ID `com.jackthelobster.whispertoinput`, and uses the fork's existing distribution signing identity. Install it over the earlier signed fork release to retain settings. A debug APK or upstream app has a different identity; do not uninstall to bypass a signature conflict without considering data loss.

Enable Whisper Input and grant microphone permission, configure your server, then select it through Android's keyboard switcher while typing. Your usual typing keyboard should remain the default.

## Assets and verification

The release APK is minified, resource-shrunk, and signed. `SHA256SUMS`, `signature.txt`, `badging.txt`, and `VERIFICATION.md` accompany it. The matching source archive and GPL license are included. Consult `VERIFICATION.md` for exact source identities, build tasks, test counts, and scope.

Screenshots use dummy settings and simulated app-view states on comet's Android 12 AOSP emulator (1080 × 2280, 480 dpi). Debug instrumentation and screenshots do not establish release runtime or physical-handset dictation acceptance. Provider availability and supported languages depend on the configured server and model.

## Credits

Based on [j3soon/whisper-to-input](https://github.com/j3soon/whisper-to-input), with original contributions from Yan-Bin Diau, Johnson Sun, and Ying-Chou Sun. Distributed under GPL-3.0-or-later; see the included license and corresponding source.
