//! Accelerator underlines that show only while Alt is held, as Windows and Palm Desktop 3.1.1 draw them.
//!
//! FLTK always draws the underline under the accelerator letter of a label ('&' in the label). While the
//! underlines are hidden, `refresh` strips the '&' from every label, and it puts the '&' back while they
//! are shown. Alt or F10 shows them; the next mouse press hides them.

use fltk::app;
use fltk::button::Button;
use fltk::enums::{Event, Key, Shortcut};
use fltk::group::{Group, Tabs};
use fltk::menu::{MenuBar, MenuButton};
use fltk::prelude::*;
use fltk::widget::Widget;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;

thread_local! {
	// Whether the underlines are shown. They start hidden, so the first `refresh` hides them.
	static SHOWN: Cell<bool> = const { Cell::new(false) };
	// The label each widget and menu item had before it was stripped, keyed by its pointer.
	static ORIGINALS: RefCell<HashMap<usize, String>> = RefCell::new(HashMap::new());
}

/// Installs the handlers that show the underlines on Alt or F10 and hide them on a mouse press.
pub fn install() {
	app::add_handler(on_event);
	install_system_handler();
}

/// Registers the Windows system handler, which sees the mouse presses that widgets handle.
#[cfg(windows)]
fn install_system_handler() {
	// SAFETY: the callback only reads the MSG that FLTK passes it, and FLTK calls it on the UI thread.
	unsafe {
		app::add_system_handler(Some(windows::system_event), std::ptr::null_mut());
	}
}

/// Other platforms rely on the event handler alone.
#[cfg(not(windows))]
fn install_system_handler() {}

/// Applies the current state to every widget of every shown window and to every menu item.
/// Labels that already match the state are left alone, so a call with no change only walks the tree.
pub fn refresh() {
	let shown = SHOWN.with(Cell::get);
	if let Some(windows) = app::windows() {
		for win in windows {
			if let Some(group) = Group::from_dyn_widget_ptr(win.as_widget_ptr()) {
				refresh_group(&group, shown);
			}
		}
	}
}

/// Runs `f` with every label in its original form, '&' included, then puts the state back. Code that finds
/// menu items by their path ("&Edit/&Undo") or adds items to a menu runs inside it, so the paths match.
pub fn with_originals<R>(f: impl FnOnce() -> R) -> R {
	let shown = SHOWN.with(|s| s.replace(true));
	if !shown {
		refresh();
	}
	let r = f();
	SHOWN.with(|s| s.set(shown));
	if !shown {
		refresh();
	}
	r
}

/// Shows or hides the underlines. A change of state applies at once.
fn set_shown(on: bool) {
	if SHOWN.with(|shown| shown.replace(on)) != on {
		refresh();
	}
}

/// The event handler: Alt and F10 show the underlines, and a mouse press hides them.
/// FLTK calls this handler only for events that no widget handled, so a press on a widget
/// does not reach it on every platform; the Windows system handler covers that case.
/// It returns false, so it never eats an event.
fn on_event(event: Event) -> bool {
	match event {
		Event::KeyDown => {
			if matches!(app::event_key(), Key::AltL | Key::AltR | Key::F10) {
				set_shown(true);
			}
		}
		Event::Push => set_shown(false),
		_ => {}
	}
	false
}

#[cfg(windows)]
mod windows {
	use super::set_shown;
	use std::ffi::c_void;

	// Message numbers and virtual-key codes from the Windows SDK.
	const WM_KEYDOWN: u32 = 0x0100;
	const WM_SYSKEYDOWN: u32 = 0x0104;
	const WM_LBUTTONDOWN: u32 = 0x0201;
	const WM_RBUTTONDOWN: u32 = 0x0204;
	const WM_MBUTTONDOWN: u32 = 0x0207;
	const VK_MENU: usize = 0x12;
	const VK_F10: usize = 0x79;

	// The MSG structure of the Windows SDK, laid out as C lays it out on this platform.
	#[repr(C)]
	struct Msg {
		hwnd: *mut c_void,
		message: u32,
		wparam: usize,
		lparam: isize,
		time: u32,
		x: i32,
		y: i32,
	}

	/// Sees every message before FLTK does. It returns 0, so FLTK still handles the message.
	///
	/// # Safety
	/// FLTK passes a pointer to the MSG it is about to dispatch, on the UI thread.
	pub unsafe extern "C" fn system_event(e: *mut c_void, _data: *mut c_void) -> i32 {
		if e.is_null() {
			return 0;
		}
		let msg = unsafe { &*(e as *const Msg) };
		match msg.message {
			WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN => set_shown(false),
			WM_KEYDOWN | WM_SYSKEYDOWN if msg.wparam == VK_MENU || msg.wparam == VK_F10 => set_shown(true),
			_ => {}
		}
		0
	}
}

/// Removes the accelerator '&' from a label. Returns None when the label has no accelerator.
/// "&&" is a literal '&' and stays as it is.
fn strip(label: &str) -> Option<String> {
	scan(label).map(|(text, _)| text)
}

/// The accelerator letter of a label, or None when the label has none.
fn accelerator(label: &str) -> Option<char> {
	scan(label).map(|(_, accel)| accel)
}

/// Splits a label into its text without the accelerator '&' and the accelerator letter.
/// A '&' followed by a space is not an accelerator, and it stays in the text.
fn scan(label: &str) -> Option<(String, char)> {
	let mut text = String::with_capacity(label.len());
	let mut accel = None;
	let mut chars = label.chars().peekable();
	while let Some(c) = chars.next() {
		if c != '&' {
			text.push(c);
			continue;
		}
		match chars.peek().copied() {
			Some('&') => {
				chars.next();
				text.push_str("&&");
			}
			Some(next) if !next.is_whitespace() => {
				// FLTK underlines one letter per label, so the first accelerator counts.
				if accel.is_none() {
					accel = Some(next);
				}
			}
			_ => text.push('&'),
		}
	}
	accel.map(|accel| (text, accel))
}

/// The label to show now: the original while the underlines are shown, its stripped form while hidden.
fn target(original: &str, shown: bool) -> String {
	match (shown, strip(original)) {
		(false, Some(stripped)) => stripped,
		_ => original.to_string(),
	}
}

/// Chooses the original label. The stored original stays, unless the current label is neither the
/// original nor its stripped form: code has then set a new label, which becomes the original.
fn pick_original(stored: Option<&str>, current: &str) -> String {
	match stored {
		Some(orig) if orig == current || strip(orig).as_deref() == Some(current) => orig.to_string(),
		_ => current.to_string(),
	}
}

/// Records the original of a widget or menu item under its pointer and returns it. Labels without an
/// accelerator are not kept.
fn remember(key: usize, current: &str) -> String {
	ORIGINALS.with(|map| {
		let mut map = map.borrow_mut();
		let original = pick_original(map.get(&key).map(String::as_str), current);
		if accelerator(&original).is_some() {
			map.insert(key, original.clone());
		} else {
			map.remove(&key);
		}
		original
	})
}

/// Sets the label of a widget to its state. A button also gets Alt+letter as its shortcut when it has
/// none, so the shortcut works while the label has no '&'.
fn refresh_widget(widget: &mut Widget, shown: bool) {
	// FLTK draws '&' as an underline only on buttons and on the tabs of a Tabs; any other label, such as a
	// frame that shows a record, draws '&' as itself and is left alone.
	let is_tab = widget
		.parent()
		.is_some_and(|p| Tabs::from_dyn_widget_ptr(p.as_widget_ptr()).is_some());
	if Button::from_dyn_widget_ptr(widget.as_widget_ptr()).is_none() && !is_tab {
		return;
	}
	let key = widget.as_widget_ptr() as usize;
	let current = widget.label();
	let original = remember(key, &current);
	let wanted = target(&original, shown);
	if wanted != current {
		widget.set_label(&wanted);
	}
	if !shown {
		button_shortcut(widget, &original);
	}
}

/// Gives a button Alt+letter as its shortcut, when the button is a button and has no shortcut yet.
/// A button keeps any shortcut it already has.
// The shortcut is a letter in lower case, because FLTK matches the key as the keyboard sends it.
fn button_shortcut(widget: &Widget, original: &str) {
	let Some(accel) = accelerator(original) else {
		return;
	};
	let Some(mut button) = Button::from_dyn_widget_ptr(widget.as_widget_ptr()) else {
		return;
	};
	if button.shortcut() == Shortcut::None {
		button.set_shortcut(Shortcut::Alt | accel.to_ascii_lowercase());
	}
}

/// The key of a menu item's original label: its menu and its label without the '&'. FLTK moves the items of
/// a menu in memory when items are added or removed, so an item's own address does not last.
fn menu_key(menu: usize, current: &str) -> usize {
	use std::hash::{Hash, Hasher};
	let mut h = std::collections::hash_map::DefaultHasher::new();
	(menu, strip(current).unwrap_or_else(|| current.to_string())).hash(&mut h);
	h.finish() as usize
}

/// Sets the labels of the items of a menu to their state.
fn refresh_menu<M: MenuExt>(menu: &M, shown: bool) {
	let menu_ptr = menu.as_widget_ptr() as usize;
	for i in 0..menu.size() {
		let Some(item) = menu.at(i) else {
			continue;
		};
		let Some(current) = item.label() else {
			continue;
		};
		// SAFETY: the pointer names the item for set_item_label.
		let ptr = unsafe { item.as_ptr() };
		let original = remember(menu_key(menu_ptr, &current), &current);
		let wanted = target(&original, shown);
		if wanted != current {
			set_item_label(ptr.cast(), &wanted);
		}
	}
}

/// Sets the label of a menu item in C memory. The menus are built with add(), so FLTK owns their labels and
/// frees them with free() when it removes an item; MenuItem::set_label would hand it Rust memory instead,
/// which corrupts the heap on Windows. The old label is freed here for the same reason.
fn set_item_label(item: *mut std::ffi::c_void, label: &str) {
	use std::ffi::{c_char, c_void, CString};
	unsafe extern "C" {
		fn Fl_Menu_Item_label(item: *mut c_void) -> *const c_char;
		fn Fl_Menu_Item_set_label(item: *mut c_void, label: *const c_char);
		fn strdup(s: *const c_char) -> *mut c_char;
		fn free(p: *mut c_void);
	}
	let Ok(text) = CString::new(label) else {
		return;
	};
	// SAFETY: `item` is a live item of a menu built with add(), whose labels FLTK allocated with the C
	// allocator; the new label comes from the same allocator, and the old one is freed once.
	unsafe {
		let old = Fl_Menu_Item_label(item);
		Fl_Menu_Item_set_label(item, strdup(text.as_ptr()));
		if !old.is_null() {
			free(old as *mut c_void);
		}
	}
}

/// Walks a group and everything in it: its widgets, its menus and the groups inside it.
fn refresh_group(group: &Group, shown: bool) {
	for i in 0..group.children() {
		let Some(mut child) = group.child(i) else {
			continue;
		};
		let ptr = child.as_widget_ptr();
		refresh_widget(&mut child, shown);
		if let Some(menu) = MenuBar::from_dyn_widget_ptr(ptr) {
			refresh_menu(&menu, shown);
		}
		// The items of a Choice are data, such as category names, and have no accelerators to hide.
		if let Some(menu) = MenuButton::from_dyn_widget_ptr(ptr) {
			refresh_menu(&menu, shown);
		}
		if let Some(sub) = Group::from_dyn_widget_ptr(ptr) {
			refresh_group(&sub, shown);
		}
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn strip_removes_the_accelerator() {
		assert_eq!(strip("Cu&t"), Some("Cut".to_string()));
		assert_eq!(strip("&Yes"), Some("Yes".to_string()));
	}

	#[test]
	fn strip_keeps_a_double_ampersand() {
		assert_eq!(strip("A && B"), None);
		assert_eq!(strip("&&x &Yes"), Some("&&x Yes".to_string()));
	}

	#[test]
	fn strip_ignores_an_ampersand_before_a_space() {
		assert_eq!(strip("A & B"), None);
		assert_eq!(strip("Ok&"), None);
	}

	#[test]
	fn accelerator_is_the_first_letter_after_an_ampersand() {
		assert_eq!(accelerator("&Yes"), Some('Y'));
		assert_eq!(accelerator("&&x &Yes"), Some('Y'));
		assert_eq!(accelerator("&&x"), None);
		assert_eq!(accelerator("Plain"), None);
	}

	#[test]
	fn target_depends_on_the_state() {
		assert_eq!(target("Cu&t", false), "Cut");
		assert_eq!(target("Cu&t", true), "Cu&t");
		assert_eq!(target("Cut", false), "Cut");
	}

	#[test]
	fn original_follows_label_changes_by_code() {
		assert_eq!(pick_original(Some("Cu&t"), "Cu&t"), "Cu&t");
		assert_eq!(pick_original(Some("Cu&t"), "Cut"), "Cu&t");
		assert_eq!(pick_original(Some("Cu&t"), "Paste&x"), "Paste&x");
		assert_eq!(pick_original(None, "Ok&"), "Ok&");
	}
}
