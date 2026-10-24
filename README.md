# Scribe

Michael's dictation app: talk, and it types it in the way you want. A
replacement for Super Whisper on Android (a keyboard) and, next, Windows (a
tray app on Ctrl + Shift + Space).

## Install on the phone

Download the newest build on the phone:
https://github.com/laserbunny-coder/scribe/releases/latest/download/scribe.apk

Open it, allow installing from Chrome when asked, then open Scribe and follow
its four steps (connect, microphone, turn the keyboard on, try it).

## Install on Windows

Download and open
https://github.com/laserbunny-coder/scribe/releases/download/windows/Scribe-setup.exe
(Windows may say it protected your PC: choose More info, then Run anyway).
Scribe opens its Settings: connect it through Inkwell, and it then waits in
the tray. Press Ctrl + Shift + Space anywhere, talk, and press it again.

## How it fits together

- `app/` is the Android app (Kotlin, no layout files: every screen is built
  in code). `ScribeKeyboard` is the keyboard; `Scribe` is its one brain
  (records, sends off, holds the result until it's typed in); `RecordService`
  keeps the microphone going with the screen off; `SetupActivity` is the app's
  own screen; `Api` talks to the server.
- `windows/` is the Windows app (Tauri). `ui/` is everything you see: the
  pill at the bottom of the screen (`pill.js`: records, sends off, offers
  Original, other modes and Undo) and Settings (`settings.js`); `shared.js`
  talks to the server and keeps your modes. `src-tauri/src/main.rs` is the
  tray icon and the keys, and `win.rs` remembers the window you were in and
  types the text in there (clipboard plus Ctrl + V, then puts your clipboard
  back). Every push to main that touches `windows/` builds a new installer
  into the `windows` release (`.github/workflows/windows.yml`).
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
