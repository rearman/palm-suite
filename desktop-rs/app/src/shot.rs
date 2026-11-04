//! The offscreen capture of `--shot`, for the screenshots of each phase of the port, and the steps of
//! `--shot-open`, which open menus, lists and dialogs before the capture.
//!
//! A step posts mouse events to the app's own window, as the OS would deliver a click, so the menu or dialog
//! opens through its real handler. It sends no OS input: the real pointer stays where it is. The posting needs
//! Win32 or X11; on Linux the shot run forces X11 (`FLTK_BACKEND=x11`), since Wayland takes no posted events.
//! The run drops all real keyboard and mouse input, so the user's pointer cannot disturb it.

use fltk::app;
use fltk::draw;
use fltk::enums::{Color, ColorDepth};
use fltk::prelude::*;
use fltk::surface::ImageSurface;
use fltk::window::Window;

/// One step of `--shot-open`: a mouse event at a point in logical pixels from the top left of the main window.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
	/// A left press and release.
	Click(i32, i32),
	/// A right press and release.
	RightClick(i32, i32),
	/// Two left clicks at one point, a double click.
	DoubleClick(i32, i32),
	/// The pointer moves to the point, as a hover.
	Move(i32, i32),
}

/// Reads the steps of `--shot-open`: `click:X,Y`, `rclick:X,Y`, `dclick:X,Y` and `move:X,Y`, separated by `;`.
pub fn parse_steps(text: &str) -> Result<Vec<Step>, String> {
	let mut steps = Vec::new();
	for part in text.split(';').map(str::trim).filter(|p| !p.is_empty()) {
		let bad = || format!("bad step \"{part}\": use click, rclick, dclick or move, then :X,Y");
		let (kind, point) = part.split_once(':').ok_or_else(bad)?;
		let (x, y) = point.split_once(',').ok_or_else(bad)?;
		let x: i32 = x.trim().parse().map_err(|_| bad())?;
		let y: i32 = y.trim().parse().map_err(|_| bad())?;
		steps.push(match kind.trim() {
			"click" => Step::Click(x, y),
			"rclick" => Step::RightClick(x, y),
			"dclick" => Step::DoubleClick(x, y),
			"move" => Step::Move(x, y),
			_ => return Err(bad()),
		});
	}
	Ok(steps)
}

/// The shown, visible top-level windows, back to front: `main` first, then the others from the oldest shown.
fn shown_windows(main: &Window) -> Vec<Window> {
	let mut others: Vec<Window> = app::windows()
		.unwrap_or_default()
		.into_iter()
		.map(|w| unsafe { w.into_widget::<Window>() })
		.filter(|w| w.shown() && w.visible() && !w.is_same(main))
		.collect();
	// FLTK puts a window at the head of its list when it is shown.
	others.reverse();
	let mut all = vec![main.clone()];
	all.extend(others);
	all
}

/// Draws `main` and the windows shown over it (dialogs, menus, lists) offscreen at the display scale, each at
/// its place relative to `main`, on a black ground that covers them all, and writes it to `path` as a binary PPM.
pub fn save(main: &Window, path: &str) -> std::io::Result<()> {
	let wins = shown_windows(main);
	let (mx, my) = (main.x_root(), main.y_root());
	let (mut left, mut top, mut right, mut bottom) = (0, 0, main.w(), main.h());
	for w in &wins {
		let (x, y) = (w.x_root() - mx, w.y_root() - my);
		left = left.min(x);
		top = top.min(y);
		right = right.max(x + w.w());
		bottom = bottom.max(y + w.h());
	}
	let surf = ImageSurface::new(right - left, bottom - top, true);
	ImageSurface::push_current(&surf);
	draw::draw_rect_fill(0, 0, right - left, bottom - top, Color::Black);
	for w in &wins {
		surf.draw(w, w.x_root() - mx - left, w.y_root() - my - top);
	}
	ImageSurface::pop_current();
	let img = surf.image().expect("surface image");
	let (w, h) = (img.data_w() as usize, img.data_h() as usize);
	let channels = match img.depth() {
		ColorDepth::L8 => 1,
		ColorDepth::La8 => 2,
		ColorDepth::Rgb8 => 3,
		ColorDepth::Rgba8 => 4,
	};
	let mut out = format!("P6\n{w} {h}\n255\n").into_bytes();
	for px in img.to_rgb_data().chunks(channels) {
		match channels {
			1 | 2 => out.extend_from_slice(&[px[0], px[0], px[0]]),
			_ => out.extend_from_slice(&px[..3]),
		}
	}
	std::fs::write(path, out)
}

unsafe extern "C" {
	fn _exit(status: std::ffi::c_int) -> !;
}

/// Ends the program at once with `code`, from inside an FLTK callback. It runs no exit handler: FLTK's crash
/// when they run inside its event loop (an access violation on Windows). The OS frees the store lock.
pub fn exit(code: i32) -> ! {
	unsafe { _exit(code) }
}

/// One mouse event to post: a press, a release or a move of a button (1 left, 3 right, 0 none).
#[derive(Clone, Copy)]
enum Mouse {
	Press(u32),
	Release(u32),
	Double(u32),
	Move,
}

/// The device pixel that FLTK reads back as logical pixel `v` at scale `s`: FLTK truncates `device / s`.
fn device(v: i32, s: f32) -> i32 {
	(v as f32 * s).ceil() as i32
}

/// Posts the mouse events of `step` to the window that takes them: the shown window that holds the pointer grab
/// (a menu), else the modal one (a dialog), else `main`. The grab of a menu bar's menu is a window that is never
/// shown; FLTK passes events from any window of the app to it. `time` is the event time in ms, for the click
/// counts of X11.
pub fn post(main: &Window, step: Step, time: u32) {
	let grab = app::grab().map(|w| unsafe { w.into_widget::<Window>() });
	let modal = app::modal().map(|w| unsafe { w.into_widget::<Window>() });
	let target = grab
		.into_iter()
		.chain(modal)
		.find(|w| w.shown())
		.unwrap_or_else(|| main.clone());
	let (x, y, events): (i32, i32, &[Mouse]) = match step {
		Step::Click(x, y) => (x, y, &[Mouse::Press(1), Mouse::Release(1)]),
		Step::RightClick(x, y) => (x, y, &[Mouse::Press(3), Mouse::Release(3)]),
		Step::DoubleClick(x, y) => (
			x,
			y,
			&[Mouse::Press(1), Mouse::Release(1), Mouse::Double(1), Mouse::Release(1)],
		),
		Step::Move(x, y) => (x, y, &[Mouse::Move]),
	};
	// The point in the target window, and on the screen, in logical pixels.
	let (root_x, root_y) = (main.x_root() + x, main.y_root() + y);
	let (local_x, local_y) = (root_x - target.x_root(), root_y - target.y_root());
	let s = app::screen_scale(target.screen_num());
	let at = (
		device(local_x, s),
		device(local_y, s),
		device(root_x, s),
		device(root_y, s),
	);
	for &m in events {
		os::post(&target, m, at, time);
	}
}

/// Makes the shot run drop all real keyboard and mouse input, so only the posted steps reach it. A menu holds the
/// pointer, so without this, the user's mouse anywhere on the desktop would move its highlight or close it.
pub fn ignore_real_input() {
	unsafe { app::add_system_handler(Some(os::drop_real_input), std::ptr::null_mut()) }
}

#[cfg(windows)]
mod os {
	use super::Mouse;
	use fltk::prelude::*;
	use fltk::window::Window;
	use std::ffi::c_void;

	#[link(name = "user32")]
	unsafe extern "system" {
		fn PostMessageW(hwnd: *mut c_void, msg: u32, wparam: usize, lparam: isize) -> i32;
	}

	/// The head of MSG, the native event that FLTK hands its system handlers.
	#[repr(C)]
	struct Msg {
		hwnd: *mut c_void,
		message: u32,
		wparam: usize,
	}

	/// The mark in the high word of wParam of each posted message; FLTK reads only the low word's button flags.
	const POSTED: usize = 0x5a5a_0000;
	const POSTED_MASK: usize = 0xffff_0000;

	/// True (1) for keyboard and mouse input that `post` did not send, which FLTK then drops.
	pub unsafe extern "C" fn drop_real_input(event: *mut c_void, _data: *mut c_void) -> i32 {
		let msg = unsafe { &*(event as *const Msg) };
		// WM_KEYFIRST to WM_KEYLAST, WM_MOUSEFIRST to WM_MOUSELAST, and the mouse messages of the window frame.
		let input = matches!(msg.message, 0x0100..=0x0109 | 0x0200..=0x020e | 0x00a0..=0x00ad);
		i32::from(input && msg.wparam & POSTED_MASK != POSTED)
	}

	const WM_MOUSEMOVE: u32 = 0x0200;
	const WM_LBUTTONDOWN: u32 = 0x0201;
	const WM_LBUTTONUP: u32 = 0x0202;
	const WM_LBUTTONDBLCLK: u32 = 0x0203;
	const WM_RBUTTONDOWN: u32 = 0x0204;
	const WM_RBUTTONUP: u32 = 0x0205;
	const WM_RBUTTONDBLCLK: u32 = 0x0206;
	const MK_LBUTTON: usize = 0x0001;
	const MK_RBUTTON: usize = 0x0002;

	/// Posts one mouse message at client point (`x`, `y`) in device pixels; FLTK reads the buttons from wParam.
	pub fn post(win: &Window, m: Mouse, (x, y, _, _): (i32, i32, i32, i32), _time: u32) {
		let (msg, wparam) = match m {
			Mouse::Press(1) => (WM_LBUTTONDOWN, MK_LBUTTON),
			Mouse::Press(_) => (WM_RBUTTONDOWN, MK_RBUTTON),
			Mouse::Double(1) => (WM_LBUTTONDBLCLK, MK_LBUTTON),
			Mouse::Double(_) => (WM_RBUTTONDBLCLK, MK_RBUTTON),
			Mouse::Release(1) => (WM_LBUTTONUP, 0),
			Mouse::Release(_) => (WM_RBUTTONUP, 0),
			Mouse::Move => (WM_MOUSEMOVE, 0),
		};
		let lparam = ((y & 0xffff) << 16 | (x & 0xffff)) as isize;
		unsafe {
			PostMessageW(win.raw_handle(), msg, wparam | POSTED, lparam);
		}
	}
}

#[cfg(target_os = "linux")]
mod os {
	use super::Mouse;
	use fltk::app;
	use fltk::prelude::*;
	use fltk::window::Window;
	use std::ffi::{c_int, c_long, c_uint, c_ulong, c_void};

	/// XButtonEvent, which XMotionEvent matches field for field (`button` is `is_hint` there, 0 for a plain move).
	#[repr(C)]
	struct XButtonEvent {
		kind: c_int,
		serial: c_ulong,
		send_event: c_int,
		display: *mut c_void,
		window: c_ulong,
		root: c_ulong,
		subwindow: c_ulong,
		time: c_ulong,
		x: c_int,
		y: c_int,
		x_root: c_int,
		y_root: c_int,
		state: c_uint,
		button: c_uint,
		same_screen: c_int,
	}

	/// XEvent, the union of 24 longs that XSendEvent copies.
	#[repr(C)]
	union XEvent {
		button: std::mem::ManuallyDrop<XButtonEvent>,
		pad: [c_long; 24],
	}

	#[link(name = "X11")]
	unsafe extern "C" {
		fn XSendEvent(display: *mut c_void, w: c_ulong, propagate: c_int, mask: c_long, event: *mut XEvent) -> c_int;
		fn XDefaultRootWindow(display: *mut c_void) -> c_ulong;
		fn XFlush(display: *mut c_void) -> c_int;
	}

	const KEY_PRESS: c_int = 2;
	const BUTTON_PRESS: c_int = 4;
	const BUTTON_RELEASE: c_int = 5;
	const MOTION_NOTIFY: c_int = 6;
	const LEAVE_NOTIFY: c_int = 8;
	const BUTTON1_MASK: c_uint = 1 << 8;

	/// True (1) for keyboard and pointer input from the server itself, which FLTK then drops. The server marks each
	/// event that XSendEvent sent with `send_event`.
	pub unsafe extern "C" fn drop_real_input(event: *mut c_void, _data: *mut c_void) -> i32 {
		// Every XEvent starts as XButtonEvent does: type, serial, send_event.
		let ev = unsafe { &*(event as *const XButtonEvent) };
		i32::from((KEY_PRESS..=LEAVE_NOTIFY).contains(&ev.kind) && ev.send_event == 0)
	}

	/// Sends one mouse event to the window at (`x`, `y`) in device pixels, (`root_x`, `root_y`) on the screen.
	pub fn post(win: &Window, m: Mouse, (x, y, root_x, root_y): (i32, i32, i32, i32), time: u32) {
		let (kind, button) = match m {
			Mouse::Press(b) | Mouse::Double(b) => (BUTTON_PRESS, b),
			Mouse::Release(b) => (BUTTON_RELEASE, b),
			Mouse::Move => (MOTION_NOTIFY, 0),
		};
		let display = app::display();
		let xid = win.raw_handle() as usize as c_ulong;
		let state = if kind == BUTTON_RELEASE {
			BUTTON1_MASK << (button - 1)
		} else {
			0
		};
		let mut ev = XEvent { pad: [0; 24] };
		ev.button = std::mem::ManuallyDrop::new(XButtonEvent {
			kind,
			serial: 0,
			send_event: 1,
			display,
			window: xid,
			root: unsafe { XDefaultRootWindow(display) },
			subwindow: 0,
			time: time as c_ulong,
			x,
			y,
			x_root: root_x,
			y_root: root_y,
			state,
			button,
			same_screen: 1,
		});
		unsafe {
			XSendEvent(display, xid, 0, 0, &mut ev);
			XFlush(display);
		}
	}
}

#[cfg(test)]
mod tests {
	use super::{device, parse_steps, Step};

	#[test]
	fn parse_steps_reads_each_kind() {
		assert_eq!(
			parse_steps("click:40,10; rclick:5,6;dclick: 7 , 8 ;move:1,2;"),
			Ok(vec![
				Step::Click(40, 10),
				Step::RightClick(5, 6),
				Step::DoubleClick(7, 8),
				Step::Move(1, 2)
			])
		);
		assert_eq!(parse_steps(""), Ok(vec![]));
		assert!(parse_steps("tap:1,2").is_err());
		assert!(parse_steps("click:1").is_err());
		assert!(parse_steps("click:a,2").is_err());
		assert!(parse_steps("click").is_err());
	}

	#[test]
	fn device_reads_back_as_the_logical_pixel() {
		for s in [1.0f32, 1.25, 1.5, 1.75, 2.0] {
			for v in 0..400 {
				assert_eq!((device(v, s) as f32 / s) as i32, v, "v {v} at scale {s}");
			}
		}
	}
}
