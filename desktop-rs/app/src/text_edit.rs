//! The edit actions of a text field, its right-click menu (Undo; Cut, Copy, Paste, Delete; Select All, as the
//! Windows edit menu of Palm Desktop, TextMenu.kt) and the enabled state of the Edit items. A field is a
//! multi-line TextEditor or a one-line Input.

use fltk::app::{self, ClipboardContent, MouseButton};
use fltk::enums::Event;
use fltk::input::Input;
use fltk::menu::{MenuFlag, MenuItem};
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

/// The text commands of the Edit menu and the right-click menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextAction {
	Undo,
	Cut,
	Copy,
	Paste,
	Delete,
	SelectAll,
}

pub fn clipboard_has_text() -> bool {
	app::clipboard_contains(ClipboardContent::Text)
}

/// A text field that takes the Edit commands.
#[derive(Clone)]
pub enum Field {
	Editor(TextEditor),
	Input(Input),
}

impl Field {
	pub fn state(&self) -> EditState {
		match self {
			Field::Editor(e) => {
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
			Field::Input(i) => {
				let selected = i.position() != i.mark();
				let editable = i.active() && !i.readonly();
				EditState {
					// FLTK keeps no undo state of an Input; a field the user changed has something to undo.
					undo: editable && i.changed(),
					cut: editable && selected,
					copy: selected,
					paste: editable && clipboard_has_text(),
					delete: editable && selected,
					select_all: !i.value().is_empty(),
				}
			}
		}
	}

	pub fn run(&mut self, a: TextAction) {
		match self {
			Field::Editor(e) => {
				let Some(mut buf) = e.buffer() else { return };
				match a {
					TextAction::Undo => {
						let _ = buf.undo();
					}
					TextAction::Copy | TextAction::Cut => {
						if buf.selected() {
							app::copy(&buf.selection_text());
							if a == TextAction::Cut {
								buf.remove_selection();
							}
						}
					}
					// Paste goes through FLTK's paste event, which inserts the clipboard text at the cursor.
					TextAction::Paste => app::paste_text(e),
					TextAction::Delete => buf.remove_selection(),
					TextAction::SelectAll => {
						let n = buf.length();
						buf.select(0, n);
					}
				}
				e.redraw();
			}
			Field::Input(i) => {
				match a {
					TextAction::Undo => {
						let _ = i.undo();
					}
					TextAction::Copy => {
						let _ = i.copy();
					}
					TextAction::Cut => {
						let _ = i.cut();
					}
					TextAction::Paste => app::paste_text(i),
					TextAction::Delete => {
						let (p, m) = (i.position(), i.mark());
						let _ = i.replace(p.min(m), p.max(m), "");
					}
					TextAction::SelectAll => {
						let n = i.value().len() as i32;
						let _ = i.set_position(n);
						let _ = i.set_mark(0);
					}
				}
				i.redraw();
			}
		}
	}

	/// Gives the field its right-click menu.
	pub fn attach_menu(&self) {
		fn on_push(mut f: Field) -> bool {
			if app::event_mouse_button() != MouseButton::Right {
				return false;
			}
			let _ = match &mut f {
				Field::Editor(e) => e.take_focus(),
				Field::Input(i) => i.take_focus(),
			};
			if let Some(a) = menu(f.state()) {
				f.run(a);
			}
			true
		}
		match self.clone() {
			Field::Editor(mut e) => e.handle(|e, ev| ev == Event::Push && on_push(Field::Editor(e.clone()))),
			Field::Input(mut i) => i.handle(|i, ev| ev == Event::Push && on_push(Field::Input(i.clone()))),
		}
	}
}

/// Shows the right-click menu of a text field at the pointer, and returns the chosen item.
fn menu(s: EditState) -> Option<TextAction> {
	let menu = MenuItem::new(&["&Undo", "Cu&t", "&Copy", "&Paste", "&Delete", "Select &All"]);
	let on = [s.undo, s.cut, s.copy, s.paste, s.delete, s.select_all];
	for (i, enabled) in on.iter().enumerate() {
		if let Some(mut item) = menu.at(i as i32) {
			if !enabled {
				item.deactivate();
			}
			if i == 0 || i == 4 {
				item.set_flag(MenuFlag::MenuDivider);
			}
		}
	}
	let item = menu.popup(app::event_x(), app::event_y())?;
	Some(match item.label().unwrap_or_default().as_str() {
		"&Undo" => TextAction::Undo,
		"Cu&t" => TextAction::Cut,
		"&Copy" => TextAction::Copy,
		"&Paste" => TextAction::Paste,
		"&Delete" => TextAction::Delete,
		_ => TextAction::SelectAll,
	})
}
