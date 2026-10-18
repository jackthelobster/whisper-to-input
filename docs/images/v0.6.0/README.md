# v0.6.0 screenshots

Fresh captures from the v0.6.0 source snapshot on comet's `whisper-api31` AVD: Android 12 / API 31, AOSP `default;x86_64` image, 1080 × 2280 pixels, 480 dpi, software graphics.

The full debug instrumentation suite runs `settingsAndKeyboardRenderLightAndDarkAtS10eWidth`, which draws app views into PNGs using dummy settings and simulated keyboard states. Settings use a dummy API key. Production `FLAG_SECURE`, screenshot export, and IME-selection checks remain intact. These images demonstrate layout and themes rather than live recording or successful server transcription.

| View | Light | Dark |
| --- | --- | --- |
| Settings | [Settings](settings-light.png) | [Settings](settings-dark.png) |
| Ready | [Keyboard](keyboard-light.png) | [Keyboard](keyboard-dark.png) |
| Listening | [Listening](keyboard-listening-light.png) | [Listening](keyboard-listening-dark.png) |
| Retry | [Retry](keyboard-retry-light.png) | [Retry](keyboard-retry-dark.png) |
| Shift+Enter | [Shift+Enter](keyboard-shift-enter-light.png) | [Shift+Enter](keyboard-shift-enter-dark.png) |

The APK and corresponding source in the [v0.6.0 release](https://github.com/jackthelobster/whisper-to-input/releases/tag/v0.6.0) include verification evidence identifying the exact tested application source and documentation revision.
