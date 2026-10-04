# Install on Andrew's Samsung Galaxy S10e

Target handset: Samsung Galaxy S10e, Android 12. This fork is a speech-to-text keyboard, not a text-to-speech player.

## Before installing

The APK is built from the jackthelobster/whisper-to-input fork. It uses the separate application ID com.jackthelobster.whispertoinput and can coexist with the upstream app. Check the supplied SHA256SUMS against the exact APK if you transfer it between devices. Future updates must use the same signing certificate; a different certificate cannot update this installation in place.

The current transcription server requires the phone to resolve gx10 and reach TCP port 8020. Use your trusted local network or the VPN that provides that access. A successful request from the development host is not evidence that the S10e has the same network route.

## Installation and setup

1. Download the supplied signed release APK onto the S10e and open it from Samsung My Files. If Android requests permission for that installer app to install unknown apps, grant it only for this installation and turn it off afterward. Do not disable Play Protect globally. If a warning identifies a concrete threat rather than merely an unknown source, stop and investigate.
2. Open Whisper Input and use its microphone-permission button. It does not need notification or storage permission.
3. Tap Enable keyboard. On Samsung, this setting is usually under Settings → General management → Keyboard list and default. Enable Whisper Input. Android shows a general keyboard warning; this fork deliberately disables dictation in password/private fields, but no keyboard can detect a sensitive field that another app labels incorrectly.
4. Use Choose keyboard, or switch keyboards from a text field using Samsung's keyboard selector.
5. The app is preconfigured with these values:

| Setting | Value |
|---|---|
| Transcription endpoint | http://gx10:8020/v1/audio/transcriptions |
| Model | distil-whisper/distil-large-v3 |
| Language | en |
| API key | Empty; the current server does not require one |
| Allow local HTTP | On, restricted to the exact host gx10 |
| Auto-record | Off |
| Auto-switch keyboard | Off |

6. Tap Test connection. This checks the server's model-list route; it does not record or upload audio and does not prove transcription works. Save if you changed any settings.
7. Open a non-sensitive text field in a notes app. Select Whisper Input, tap the microphone, speak English, and tap Stop. The app uploads that recording to your server and inserts the recognized text. A recording is limited to two minutes or 20 MiB.

## Privacy and expected behavior

- HTTP itself does not encrypt audio. The gx10 exception is suitable only for a trusted private network or an encrypted VPN. Other servers require HTTPS. API keys are never allowed with HTTP.
- There is no silent fallback to OpenAI or another transcription service.
- API keys, when used with HTTPS endpoints, are encrypted with Android Keystore-backed AES-GCM. App settings and credentials are excluded from cloud and device-transfer backup. The settings screen blocks ordinary screenshots because it can contain secrets.
- Audio uses unique internal-cache files. Cancel, hiding the keyboard or changing editor clears the current recording and cancels the network call. Successful transcription removes the audio. Failed uploads can be retried only while the originating editor remains active.
- Pressing Enter while recording finishes dictation without implicitly submitting a message. Pressing Enter while idle respects the target app's editor action and may submit if that app specifies Send.
- This particular server accepts English only. The upstream project's multilingual capabilities do not change the server's language support.

## Physical-device acceptance checklist

These checks cannot be certified by an emulator. Run them on the S10e after installation:

- [ ] Test connection succeeds with the actual phone's network/VPN configuration.
- [ ] A spoken English sentence is inserted correctly in Samsung Notes or another ordinary text field.
- [ ] Cancel while recording releases the microphone and inserts nothing.
- [ ] Cancel during transcription inserts nothing afterward.
- [ ] Changing fields or apps during an upload does not insert the late result into either new field or app.
- [ ] Dictation is disabled in a password field; ordinary typing can continue by switching keyboards.
- [ ] A short accidental microphone tap/stop does not crash the keyboard.
- [ ] Backspace deletes an emoji as a whole code point, and repeat-delete stops when the gesture is cancelled or the keyboard is hidden.
- [ ] Switching back to Samsung Keyboard works.
- [ ] Light/dark mode, a larger system font and landscape remain usable.

If the phone cannot reach gx10, check its DNS/VPN route first. Do not solve that by enabling global cleartext, bypassing TLS validation or adding credentials to an HTTP URL. The current exception intentionally does not accept arbitrary HTTP hosts or IP addresses; a different hostname should be introduced through an explicit policy change or HTTPS.
