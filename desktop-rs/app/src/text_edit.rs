//! The edit actions of a text editor, its right-click menu (Undo; Cut, Copy, Paste, Delete; Select All, as the
//! Windows edit menu of Palm Desktop, TextMenu.kt) and the enabled state of the Edit items.

use fltk::app::{self, ClipboardContent, MouseButton};
use fltk::enums::Event;
use fltk::menu::MenuItem;
use fltk::prelude::*;
use fltk::text::TextEditor;

/// Which Edit items apply to the focus owner, as MainFrame.updateEditState grays them.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct EditState {
	pub undo: bool,
	pub cut: bool,
	pub copy: bool,
	pub paste: bool,
	pub delete: bool,
	pub select_all: bool,
}

pub fn clipboard_has_text() -> bool {
	app::clipboard_contains(ClipboardContent::Text)
}

pub fn state(e: &TextEditor) -> EditState {
	let Some(mut buf) = e.buffer() else {
		return EditState::default();
	};
	let selected = buf.selected();
	let editable = e.active();
	EditState {
		undo: editable && buf.get_can_undo(),
		cut: editable && selected,
		copy: selected,
		paste: editable && clipboard_has_text(),
		delete: editable && selected,
		select_all: buf.length() > 0,
	}
}

pub fn copy(e: &TextEditor) {
	if let Some(buf) = e.buffer() {
		if buf.selected() {
			app::copy(&buf.selection_text());
		}
	}
}

pub fn cut(e: &mut TextEditor) {
	copy(e);
	delete(e);
}

pub fn delete(e: &mut TextEditor) {
	if let Some(mut buf) = e.buffer() {
		buf.remove_selection();
	}
	e.redraw();
}

/// Paste goes through FLTK's paste event, which inserts the clipboard text at the cursor.
pub fn paste(e: &TextEditor) {
	app::paste_text(e);
}

pub fn undo(e: &mut TextEditor) {
	if let Some(mut buf) = e.buffer() {
		let _ = buf.undo();
	}
	e.redraw();
}

pub fn select_all(e: &mut TextEditor) {
	if let Some(mut buf) = e.buffer() {
		let n = buf.length();
		buf.select(0, n);
	}
	e.redraw();
}

/// Shows the right-click menu of the editor at the pointer and runs the chosen item.
fn popup(e: &mut TextEditor) {
	let s = state(e);
	let menu = MenuItem::new(&["&Undo", "Cu&t", "&Copy", "&Paste", "&Delete", "Select &All"]);
	let on = [s.undo, s.cut, s.copy, s.paste, s.delete, s.select_all];
	for (i, enabled) in on.iter().enumerate() {
		if let Some(mut item) = menu.at(i as i32) {
			if !enabled {
				item.deactivate();
			}
			if i == 0 || i == 4 {
				item.set_flag(fltk::menu::MenuFlag::MenuDivider);
			}
		}
	}
	let Some(item) = menu.popup(app::event_x(), app::event_y()) else {
		return;
	};
	match item.label().unwrap_or_default().as_str() {
		"&Undo" => undo(e),
		"Cu&t" => cut(e),
		"&Copy" => copy(e),
		"&Paste" => paste(e),
		"&Delete" => delete(e),
		_ => select_all(e),
	}
}

/// Gives the editor its right-click menu. `after` runs after each event the editor takes, for the views
/// that follow its text.
pub fn attach_menu(e: &mut TextEditor) {
	e.handle(|e, ev| {
		if ev == Event::Push && app::event_mouse_button() == MouseButton::Right {
			let _ = e.take_focus();
			popup(e);
			return true;
		}
		false
	});
}
