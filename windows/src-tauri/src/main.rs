// Scribe for Windows: press Ctrl + Shift + Space anywhere, talk, press it
// again, and the words are typed into the program you were in, rewritten in
// the mode you picked. Everything you see is the web pages in ../ui (the
// pill at the bottom of the screen and the Settings window); this file is
// the part that has to be Windows: the shortcut keys, the tray icon, and
// typing the text in (win.rs).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod win;

use std::sync::Mutex;
use std::{thread, time::Duration};

use serde::Serialize;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::webview::{PermissionKind, PermissionResponse};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt as _};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

const TALK: (Modifiers, Code) = (Modifiers::CONTROL.union(Modifiers::SHIFT), Code::Space);
const MODE: (Modifiers, Code) = (Modifiers::CONTROL.union(Modifiers::SHIFT), Code::KeyS);

fn shortcut((mods, code): (Modifiers, Code)) -> Shortcut {
    Shortcut::new(Some(mods), code)
}
fn escape() -> Shortcut {
    Shortcut::new(None, Code::Escape)
}

#[derive(Default)]
struct Scribe {
    /// The pill's window.
    pill: Mutex<win::Handle>,
    /// Where the words go: the window you were in when you started talking.
    target: Mutex<Option<win::Handle>>,
}

#[derive(Serialize)]
struct Target {
    app: String,
    title: String,
}

/// Remembers the window being typed in, as talking starts.
#[tauri::command]
fn take_target(s: State<Scribe>) -> Option<Target> {
    let pill = *s.pill.lock().unwrap();
    let target = win::foreground().filter(|h| *h != pill);
    *s.target.lock().unwrap() = target;
    target.map(|h| Target { app: win::program(h), title: win::title(h) })
}

#[tauri::command]
fn show_pill(width: f64, height: f64, s: State<Scribe>) {
    win::show_at_bottom(*s.pill.lock().unwrap(), *s.target.lock().unwrap(), width, height);
}

#[tauri::command]
fn hide_pill(s: State<Scribe>) {
    win::hide(*s.pill.lock().unwrap());
}

fn clipboard_text() -> Option<String> {
    arboard::Clipboard::new().ok()?.get_text().ok()
}

fn set_clipboard(text: &str) -> bool {
    arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).is_ok()
}

#[tauri::command]
fn copy_text(text: String) -> bool {
    set_clipboard(&text)
}

#[tauri::command]
fn read_clipboard() -> String {
    clipboard_text().unwrap_or_default()
}

/// Types `text` into the window you were in. `replace` takes back what Scribe
/// just typed there first (with Ctrl + Z). Answers "typed", or "copied" when
/// it couldn't get there (you moved to another program, or it closed): the
/// text is then on the clipboard to paste yourself. `force` brings that
/// window back to the front even from another program.
#[tauri::command]
async fn type_in(text: String, replace: bool, force: bool, s: State<'_, Scribe>) -> Result<String, String> {
    let target = *s.target.lock().unwrap();
    tauri::async_runtime::spawn_blocking(move || {
        let Some(_) = target.filter(|t| win::ready(*t, force)) else {
            set_clipboard(&text);
            return "copied".to_string();
        };
        if replace {
            win::ctrl('z');
            thread::sleep(Duration::from_millis(150));
        }
        // Paste, then put back what was on the clipboard before.
        let before = clipboard_text();
        if !set_clipboard(&text) {
            return "copied".to_string();
        }
        thread::sleep(Duration::from_millis(40));
        win::ctrl('v');
        if let Some(before) = before.filter(|b| *b != text) {
            thread::spawn(move || {
                thread::sleep(Duration::from_millis(1500));
                if clipboard_text().as_deref() == Some(text.as_str()) {
                    set_clipboard(&before);
                }
            });
        }
        "typed".to_string()
    })
    .await
    .map_err(|e| e.to_string())
}

/// Takes back what Scribe just typed in (Ctrl + Z in that window).
#[tauri::command]
async fn undo(s: State<'_, Scribe>) -> Result<bool, String> {
    let target = *s.target.lock().unwrap();
    tauri::async_runtime::spawn_blocking(move || match target {
        Some(t) if win::ready(t, false) => {
            win::ctrl('z');
            true
        }
        _ => false,
    })
    .await
    .map_err(|e| e.to_string())
}

/// Ctrl + Shift + S (next mode) and Esc (throw it away) only belong to
/// Scribe while the pill is up; the rest of the time they're the programs'.
#[tauri::command]
fn pill_keys(on: bool, app: AppHandle) -> Result<(), String> {
    let gs = app.global_shortcut();
    for key in [shortcut(MODE), escape()] {
        let has = gs.is_registered(key);
        let r = if on && !has { gs.register(key) } else if !on && has { gs.unregister(key) } else { Ok(()) };
        r.map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn autostart(on: Option<bool>, app: AppHandle) -> Result<bool, String> {
    let launcher = app.autolaunch();
    match on {
        Some(true) => launcher.enable().map_err(|e| e.to_string())?,
        Some(false) => launcher.disable().map_err(|e| e.to_string())?,
        None => {}
    }
    launcher.is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn open_settings(app: AppHandle) {
    settings(&app);
}

fn settings(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("settings") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
        return;
    }
    let built = WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("settings.html".into()))
        .title("Scribe")
        .inner_size(480.0, 760.0)
        .min_inner_size(380.0, 480.0)
        .center()
        .on_permission_request(|_, kind| match kind {
            PermissionKind::Microphone => PermissionResponse::Allow,
            _ => PermissionResponse::Default,
        })
        .build();
    if let Ok(w) = built {
        let _ = w.set_focus();
    }
}

/// Tells the pill a key was pressed: "talk", "mode" or "cancel".
fn press(app: &AppHandle, what: &str) {
    let _ = app.emit_to("pill", "key", what);
}

fn main() {
    tauri::Builder::default()
        // Opening Scribe again (from Start) just shows Settings.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| settings(app)))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, key, event| {
                    if event.state() != ShortcutState::Pressed {
                        return;
                    }
                    if key == &shortcut(TALK) {
                        press(app, "talk");
                    } else if key == &shortcut(MODE) {
                        press(app, "mode");
                    } else if key == &escape() {
                        press(app, "cancel");
                    }
                })
                .build(),
        )
        .manage(Scribe::default())
        .invoke_handler(tauri::generate_handler![
            take_target,
            show_pill,
            hide_pill,
            copy_text,
            read_clipboard,
            type_in,
            undo,
            pill_keys,
            autostart,
            open_settings
        ])
        .setup(|app| {
            let pill = WebviewWindowBuilder::new(app, "pill", WebviewUrl::App("pill.html".into()))
                .title("Scribe")
                .inner_size(560.0, 120.0)
                .visible(false)
                .decorations(false)
                .transparent(true)
                .shadow(false)
                .always_on_top(true)
                .skip_taskbar(true)
                .resizable(false)
                .focused(false)
                .on_permission_request(|_, kind| match kind {
                    PermissionKind::Microphone => PermissionResponse::Allow,
                    _ => PermissionResponse::Default,
                })
                .build()?;
            let handle = pill.hwnd()?.0 as win::Handle;
            win::make_floating(handle);
            *app.state::<Scribe>().pill.lock().unwrap() = handle;

            app.global_shortcut().register(shortcut(TALK))?;

            let talk = MenuItem::with_id(app, "talk", "Talk   (Ctrl + Shift + Space)", true, None::<&str>)?;
            let open = MenuItem::with_id(app, "settings", "Settings and modes", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit Scribe", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&talk, &open, &PredefinedMenuItem::separator(app)?, &quit])?;
            TrayIconBuilder::with_id("scribe")
                .icon(app.default_window_icon().cloned().expect("app icon"))
                .tooltip("Scribe: press Ctrl + Shift + Space and talk")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "talk" => press(app, "talk"),
                    "settings" => settings(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        settings(tray.app_handle());
                    }
                })
                .build(app)?;

            // Started with Windows: stay in the tray. Opened by hand: show Settings.
            if !std::env::args().any(|a| a == "--hidden") {
                settings(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing Settings keeps Scribe running in the tray.
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "settings" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("Scribe couldn't start");
}
