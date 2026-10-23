//! Facts of the system that the app reads once, and today's date.

/// Today as an epoch day, in the time zone of the system.
pub fn today() -> i64 {
	let d = jiff::Zoned::now().date();
	palm_core::days::epoch_day(d.year() as i64, d.month() as u32, d.day() as u32)
}

/// True when the system shows the time in 24-hour form. On Windows the short time format comes from the
/// Regional settings in the registry, as Main.kt reads it.
pub fn uses_24_hours() -> bool {
	#[cfg(windows)]
	{
		use std::os::windows::process::CommandExt;
		const CREATE_NO_WINDOW: u32 = 0x0800_0000;
		let out = std::process::Command::new("reg")
			.args(["query", r"HKCU\Control Panel\International", "/v", "sShortTime"])
			.creation_flags(CREATE_NO_WINDOW)
			.output();
		if let Ok(out) = out {
			let text = String::from_utf8_lossy(&out.stdout);
			if let Some(format) = text.lines().find_map(|l| l.split("REG_SZ").nth(1)).map(str::trim) {
				return format.contains('H');
			}
		}
	}
	// Shortcut: elsewhere the locale name decides, and only the United States English locales use the
	// 12-hour clock. Upgrade path: read LC_TIME's t_fmt through nl_langinfo.
	let locale = ["LC_ALL", "LC_TIME", "LANG"]
		.iter()
		.find_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()));
	match locale {
		Some(l) => !(l.starts_with("en_US") || l == "C" || l == "POSIX"),
		None => false,
	}
}

/// Adds the close (X) box to a window that FLTK gave none. FLTK adds WS_SYSMENU only to a window that is not
/// modal, and every dialog is modal, so a dialog has a title bar without the box. The style bit is set on the
/// live window, and the frame is redrawn. No maximize or minimize box is added.
// The handle is only passed to user32, which checks it; a stale handle makes the calls fail with no effect.
#[cfg(windows)]
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub fn add_close_box(hwnd: *mut std::ffi::c_void) {
	const GWL_STYLE: i32 = -16;
	const WS_SYSMENU: isize = 0x0008_0000;
	const SWP_NOSIZE: u32 = 0x0001;
	const SWP_NOMOVE: u32 = 0x0002;
	const SWP_NOZORDER: u32 = 0x0004;
	const SWP_FRAMECHANGED: u32 = 0x0020;
	#[link(name = "user32")]
	unsafe extern "system" {
		fn GetWindowLongPtrW(hwnd: *mut std::ffi::c_void, index: i32) -> isize;
		fn SetWindowLongPtrW(hwnd: *mut std::ffi::c_void, index: i32, value: isize) -> isize;
		fn SetWindowPos(
			hwnd: *mut std::ffi::c_void,
			after: *mut std::ffi::c_void,
			x: i32,
			y: i32,
			cx: i32,
			cy: i32,
			flags: u32,
		) -> i32;
	}
	if hwnd.is_null() {
		return;
	}
	// SAFETY: `hwnd` is the handle of a window that FLTK has shown, and the calls only read and set its style.
	unsafe {
		let style = GetWindowLongPtrW(hwnd, GWL_STYLE);
		SetWindowLongPtrW(hwnd, GWL_STYLE, style | WS_SYSMENU);
		SetWindowPos(
			hwnd,
			std::ptr::null_mut(),
			0,
			0,
			0,
			0,
			SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED,
		);
	}
}

/// Elsewhere the window manager draws the close box, so there is nothing to add.
#[cfg(not(windows))]
pub fn add_close_box(_hwnd: *mut std::ffi::c_void) {}
