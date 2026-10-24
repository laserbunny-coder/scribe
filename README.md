# Scribe

Michael's dictation app: talk, and it types it in the way you want. A
replacement for Super Whisper on Android (a keyboard) and, next, Windows (a
tray app on Ctrl + Shift + Space).

## Install on the phone

Download the newest build on the phone:
https://github.com/laserbunny-coder/scribe/releases/latest/download/scribe.apk

Open it, allow installing from Chrome when asked, then open Scribe and follow
its four steps (connect, microphone, turn the keyboard on, try it).

## How it fits together

- `app/` is the Android app (Kotlin, no layout files: every screen is built
  in code). `ScribeKeyboard` is the keyboard; `Scribe` is its one brain
  (records, sends off, holds the result until it's typed in); `RecordService`
  keeps the microphone going with the screen off; `SetupActivity` is the app's
  own screen; `Api` talks to the server.
- The server step is the `scribe` function in the **daybook** repo
  (`supabase/functions/scribe`), beside Inkwell's. It writes recordings out
  with the same speech service as Inkwell's Capture (OpenAI) and has Claude
  rewrite them in the chosen mode. Modes live per person in the `scribe_modes`
  table, starting from `supabase/functions/scribe/modes.ts`.
- The phone connects through Inkwell: Scribe opens Inkwell at
  `?connect=scribe`, Inkwell makes a device secret (`scribe_tokens`) and hands
  it back as `scribe://connect?token=…&url=…`.
- Every push to main builds a new APK (`.github/workflows/android.yml`) and
  publishes it as the latest release. `app/scribe.keystore` signs every build
  with the same key so a new one installs over the old.
