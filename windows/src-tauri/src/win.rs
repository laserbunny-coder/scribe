//! The Windows parts: which window you were typing in, putting the pill on top
//! without taking the keyboard away from it, and typing the text in there.

use std::{thread::sleep, time::Duration};

use windows::Win32::Foundation::{CloseHandle, HWND, RECT};
use windows::Win32::Graphics::Gdi::{GetMonitorInfoW, MonitorFromWindow, MONITORINFO, MONITOR_DEFAULTTOPRIMARY};
use windows::Win32::System::Threading::{
    AttachThreadInput, GetCurrentProcessId, GetCurrentThreadId, OpenProcess, QueryFullProcessImageNameW,
    PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows::Win32::UI::HiDpi::{GetDpiForMonitor, MDT_EFFECTIVE_DPI};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP,
    VIRTUAL_KEY, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
};
use windows::Win32::UI::WindowsAndMessaging::{
    GetForegroundWindow, GetWindowLongPtrW, GetWindowTextW, GetWindowThreadProcessId, IsIconic, IsWindow,
    SetForegroundWindow, SetWindowLongPtrW, SetWindowPos, ShowWindow, GWL_EXSTYLE, HWND_TOPMOST, SWP_NOACTIVATE,
    SWP_SHOWWINDOW, SW_HIDE, SW_RESTORE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
};

/// A window handle kept between calls (HWNDs are just numbers).
pub type Handle = isize;

fn hwnd(h: Handle) -> HWND {
    HWND(h as *mut _)
}

/// The window being typed in (Scribe's own Settings counts: it has a box to try it in).
pub fn foreground() -> Option<Handle> {
    let w = unsafe { GetForegroundWindow() };
    (!w.0.is_null()).then(|| w.0 as isize)
}

/// The program's file name, like "outlook.exe".
pub fn program(h: Handle) -> String {
    unsafe {
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd(h), Some(&mut pid));
        let Ok(process) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) else { return String::new() };
        let mut buf = [0u16; 1024];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(process, PROCESS_NAME_WIN32, windows::core::PWSTR(buf.as_mut_ptr()), &mut len).is_ok();
        let _ = CloseHandle(process);
        if !ok {
            return String::new();
        }
        let path = String::from_utf16_lossy(&buf[..len as usize]);
        path.rsplit('\\').next().unwrap_or("").to_lowercase()
    }
}

/// The window's title, like "Inbox - Gmail - Google Chrome".
pub fn title(h: Handle) -> String {
    let mut buf = [0u16; 512];
    let n = unsafe { GetWindowTextW(hwnd(h), &mut buf) };
    String::from_utf16_lossy(&buf[..n.max(0) as usize])
}

/// Makes a window one that floats on top and never takes the keyboard.
pub fn make_floating(h: Handle) {
    unsafe {
        let style = GetWindowLongPtrW(hwnd(h), GWL_EXSTYLE);
        SetWindowLongPtrW(
            hwnd(h),
            GWL_EXSTYLE,
            style | (WS_EX_NOACTIVATE.0 | WS_EX_TOOLWINDOW.0 | WS_EX_TOPMOST.0) as isize,
        );
    }
}

/// Shows the pill at the bottom middle of the screen the target is on, `width` × `height`
/// in screen points (scaled for the screen's text size), without taking focus.
pub fn show_at_bottom(pill: Handle, near: Option<Handle>, width: f64, height: f64) {
    unsafe {
        let monitor = MonitorFromWindow(near.map(hwnd).unwrap_or(GetForegroundWindow()), MONITOR_DEFAULTTOPRIMARY);
        let mut info = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        if !GetMonitorInfoW(monitor, &mut info).as_bool() {
            info.rcWork = RECT { left: 0, top: 0, right: 1920, bottom: 1040 };
        }
        let (mut dx, mut dy) = (96u32, 96u32);
        let _ = GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dx, &mut dy);
        let scale = dx as f64 / 96.0;
        let w = (width * scale).round() as i32;
        let h = (height * scale).round() as i32;
        let work = info.rcWork;
        let x = work.left + ((work.right - work.left) - w) / 2;
        let y = work.bottom - h - (16.0 * scale) as i32;
        let _ = SetWindowPos(hwnd(pill), Some(HWND_TOPMOST), x, y, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
    }
}

pub fn hide(h: Handle) {
    unsafe {
        let _ = ShowWindow(hwnd(h), SW_HIDE);
    }
}

fn key(vk: VIRTUAL_KEY, up: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: 0,
                dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

const MODIFIERS: [VIRTUAL_KEY; 5] = [VK_CONTROL, VK_SHIFT, VK_MENU, VK_LWIN, VK_RWIN];

/// Waits for Ctrl, Shift, Alt and the Windows key to be let go (they'd turn
/// Ctrl + V into something else), and lets them go itself after a moment.
fn wait_for_hands_off() {
    for _ in 0..60 {
        let held = MODIFIERS.iter().any(|k| unsafe { GetAsyncKeyState(k.0 as i32) } as u16 & 0x8000 != 0);
        if !held {
            return;
        }
        sleep(Duration::from_millis(25));
    }
    let ups: Vec<INPUT> = MODIFIERS.iter().map(|k| key(*k, true)).collect();
    unsafe { SendInput(&ups, std::mem::size_of::<INPUT>() as i32) };
}

/// Ctrl plus a letter, like Ctrl + V.
pub fn ctrl(letter: char) {
    let vk = VIRTUAL_KEY(letter.to_ascii_uppercase() as u16);
    let keys = [key(VK_CONTROL, false), key(vk, false), key(vk, true), key(VK_CONTROL, true)];
    unsafe { SendInput(&keys, std::mem::size_of::<INPUT>() as i32) };
}

/// Puts the keyboard back in the target window. Only steals it back from
/// Scribe itself (after a click on the pill), never from another program
/// unless `force` (the person asked for it to go there).
pub fn focus(target: Handle, force: bool) -> bool {
    unsafe {
        if !IsWindow(Some(hwnd(target))).as_bool() {
            return false;
        }
        let now = GetForegroundWindow();
        if now.0 as isize == target {
            return true;
        }
        let ours = {
            let mut pid = 0u32;
            GetWindowThreadProcessId(now, Some(&mut pid));
            pid == GetCurrentProcessId() || now.0.is_null()
        };
        if !ours && !force {
            return false;
        }
        if IsIconic(hwnd(target)).as_bool() {
            let _ = ShowWindow(hwnd(target), SW_RESTORE);
        }
        // Windows only lets the program in front hand over the keyboard, so
        // borrow its input queue for a moment.
        let front_thread = GetWindowThreadProcessId(now, None);
        let me = GetCurrentThreadId();
        let attached = front_thread != 0 && front_thread != me && AttachThreadInput(me, front_thread, true).as_bool();
        let _ = SetForegroundWindow(hwnd(target));
        if attached {
            let _ = AttachThreadInput(me, front_thread, false);
        }
        for _ in 0..20 {
            if GetForegroundWindow().0 as isize == target {
                return true;
            }
            sleep(Duration::from_millis(15));
        }
        false
    }
}

/// Gets ready to type into the target: hands off the keys, then the keyboard back to it.
pub fn ready(target: Handle, force: bool) -> bool {
    wait_for_hands_off();
    let ok = focus(target, force);
    if ok {
        sleep(Duration::from_millis(60));
    }
    ok
}
