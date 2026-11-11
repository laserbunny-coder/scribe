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
  with the same speech service as Inkwell's Capture (ElevenLabs, listening
  for the names in your Inkwell worlds; OpenAI as backup) and has Claude
  rewrite them in the chosen mode. Modes live per person in the `scribe_modes`
  table, starting from `supabase/functions/scribe/modes.ts`.
- The phone connects through Inkwell: Scribe opens Inkwell at
  `?connect=scribe`, Inkwell makes a device secret (`scribe_tokens`) and hands
  it back as `scribe://connect?token=…&url=…`.
- Every push to main builds a new APK (`.github/workflows/android.yml`) and
  publishes it as the latest release. `app/scribe.keystore` signs every build
  with the same key so a new one installs over the old.
- Fixing by voice: under the mode chips, hold **Hold to fix** and say what to
  change ("make it Tuesday", "make that friendlier", "Nate, N A T E"), then
  let go (or tap it once and tap the square when done). It changes the words
  you selected, else what Scribe just typed (if it's still right before the
  cursor), else the line before the cursor (`fixTargetOf` in `Fixing.kt`).
  Saying "the whole thing" (or "all of it", "throughout") changes everything
  in the box instead, when the box holds 4,000 characters or fewer: with
  nothing selected the whole box goes along, and the server answers with
  scope "whole".
  The server's `fix` action hears it and has Claude make only that change;
  the new words are typed over the old ones, never added on, and Undo puts
  the old ones back. When a fix corrects a name, **Remember** keeps that
  spelling (`scribe_spellings` in daybook) and the speech service listens for
  it from then on; the Scribe app lists them under "Fixing by voice", each
  with Remove. The , . ? ! keys beside Fix put punctuation right after the
  last word.
- Floating button (`FloatButton.kt`, an accessibility service): while
  another keyboard such as SwiftKey is open, an orange Scribe button sits
  just above it. A tap switches straight to the Scribe keyboard
  (`SoftKeyboardController.switchToInputMethod`, Android 11+), and Scribe's
  keyboard key switches back. Drag it to move it; it hides in password
  boxes. It only looks at whether a keyboard is open and whose it is. Turned
  on from the Scribe app (Accessibility, then "Allow restricted settings"
  once for a sideloaded app; on Samsung set battery to Unrestricted).
- Keep in Inkwell: a mode with this on (Idea, Journal and Scene to start)
  files each recording, with what was heard and what was typed in, on that
  day's Inkwell page (`scribe/keep.ts` in daybook). Inkwell writes it up like
  a Capture the next time it syncs: the recording, the mode's chip, the words
  word for word one tap away. The phone and the computer still delete their
  own copy once the words are back; the kept one is in Inkwell.
- Record tile: "Record" in the phone's pull-down quick settings (add it with
  the pencil there). Tap it, talk with the screen off, tap it again (or Stop
  in the notification). Nothing is typed: the server writes it up the way
  Inkwell's Capture would (Claude picks the mode, adds a title) and files it
  on today's Inkwell page (`action=keep`). The phone keeps the recording until
  Inkwell has it; a notification says it's kept, or offers another try.
  `RecordTile`, `RecordActivity` (see-through, starts the mic), `Kept`.
