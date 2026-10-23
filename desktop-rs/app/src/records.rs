//! Cut, Copy and Paste of the records of a list, as RecordTransfer.kt, the right-click menu of a record
//! list (Cut, Copy, Paste, Delete; Select All; then the items of the view), and the events of a record list.
//!
//! Cut and Copy keep the records here and put their text on the system clipboard, for other programs. Paste
//! asks FLTK for the clipboard text; when it is the text of the kept records, the records are pasted as
//! copies, else the text makes new records. FLTK cannot read the clipboard at once, so the text arrives as
//! a paste event on the list, which sends it on.

use crate::draw;
use crate::msg::Msg;
use crate::text_edit::{clipboard_has_text, EditState};
use crate::view::{LEFT, RIGHT};
use fltk::app::{self, Sender};
use fltk::enums::{Event, EventState, Key};
use fltk::menu::{MenuButton, MenuButtonType, MenuFlag};
use fltk::prelude::*;
use fltk::table::{TableRow, TableRowSelectFlag};
use palm_core::runner::Record;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

struct Clip {
	kind: &'static str,
	text: String,
	records: Vec<Record>,
}

thread_local! {
	static CLIP: RefCell<Option<Clip>> = const { RefCell::new(None) };
}

/// Keeps `records` of `kind` ("todo", "memo" or "address") and puts `text` on the system clipboard.
pub fn put(kind: &'static str, records: Vec<Record>, text: String) {
	app::copy(&text);
	keep(kind, records, text);
}

/// Keeps the records of a Cut or Copy, without the system clipboard.
fn keep(kind: &'static str, records: Vec<Record>, text: String) {
	CLIP.with(|c| *c.borrow_mut() = Some(Clip { kind, text, records }));
}

/// What a paste of `text` gives in a list of `kind`: the kept records when the text is theirs, else None,
/// and the caller makes records from the text.
pub fn records_for(kind: &str, text: &str) -> Option<Vec<Record>> {
	CLIP.with(|c| {
		c.borrow()
			.as_ref()
			.filter(|clip| clip.kind == kind && clip.text == text)
			.map(|clip| clip.records.clone())
	})
}

/// The Edit items of a record list with `selected` records and `rows` rows.
pub fn list_state(selected: bool, rows: bool) -> EditState {
	EditState {
		undo: false,
		cut: selected,
		copy: selected,
		paste: clipboard_has_text(),
		delete: selected,
		select_all: rows,
	}
}

/// The item that the user picked in a list menu. Extra(i) is the i-th item the view added.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ListAction {
	Cut,
	Copy,
	Paste,
	Delete,
	SelectAll,
	Extra(usize),
}

/// Shows the right-click menu of a record list at the pointer. `extra` holds the label and the enabled
/// state of each item the view adds below Select All. A divider follows Delete, and one follows Select All
/// when the view adds items.
pub fn list_menu(s: EditState, extra: &[(&'static str, bool)]) -> Option<ListAction> {
	let mut labels = vec!["Cu&t", "&Copy", "&Paste", "&Delete", "Select &All"];
	labels.extend(extra.iter().map(|(l, _)| *l));
	let on: Vec<bool> = [s.cut, s.copy, s.paste, s.delete, s.select_all]
		.into_iter()
		.chain(extra.iter().map(|(_, on)| *on))
		.collect();
	let mut mb = MenuButton::default();
	mb.set_type(MenuButtonType::Popup3);
	for label in &labels {
		mb.add_choice(label);
	}
	for (i, on) in on.iter().enumerate() {
		if let Some(mut item) = mb.at(i as i32) {
			// The divider comes first: set_flag replaces all flags of the item, Inactive included.
			if i == 3 || (i == 4 && !extra.is_empty()) {
				item.set_flag(MenuFlag::MenuDivider);
			}
			if !on {
				item.deactivate();
			}
		}
	}
	draw::style_menu(&mut mb);
	let pick = mb.popup().map(|_| mb.value()).filter(|v| *v >= 0).map(|v| v as usize);
	MenuButton::delete(mb);
	Some(match pick? {
		0 => ListAction::Cut,
		1 => ListAction::Copy,
		2 => ListAction::Paste,
		3 => ListAction::Delete,
		4 => ListAction::SelectAll,
		i => ListAction::Extra(i - 5),
	})
}

/// The messages a record list sends to its view.
#[derive(Clone, Copy)]
pub struct ListMsgs {
	/// The selection moved.
	pub picked: fn() -> Msg,
	/// A right click: the list menu.
	pub menu: fn() -> Msg,
	/// The clipboard text of a Paste into the list.
	pub paste: fn(String) -> Msg,
	/// A printable key, when the view takes one.
	pub typed: Option<fn(char) -> Msg>,
	/// A double click on a row, when the view opens one.
	pub open: Option<fn() -> Msg>,
	/// A left click on a cell: its row, its column, and true for the second click of a double click.
	pub cell: Option<fn(i32, i32, bool) -> Msg>,
}

/// Fits the one column of a record list to the width inside its edge and its scroll bar. Call it at the
/// start of each page draw; `row_h` is the row height.
pub fn fit_column(t: &mut TableRow, row_h: i32) {
	let width = t.w() - 4 - if t.rows() * row_h > t.h() - 4 { 17 } else { 0 };
	if t.col_width(0) != width {
		t.set_col_width(0, width);
	}
}

/// The events of a record list: a right click selects the row under the pointer and opens the list menu,
/// a click or an arrow key moves the lead row, a double click opens the row, a printable key goes to the
/// view, and a paste event brings the clipboard text of Paste.
pub fn list_event(t: &mut TableRow, ev: Event, lead: &Rc<Cell<i32>>, s: Sender<Msg>, m: ListMsgs) -> bool {
	match ev {
		Event::Push => {
			let _ = t.take_focus();
			let row = t
				.cursor2rowcol()
				.map(|(_, r, _, _)| r)
				.filter(|r| *r >= 0 && *r < t.rows());
			if app::event_button() == RIGHT {
				if let Some(r) = row {
					if !t.row_selected(r) {
						t.select_all_rows(TableRowSelectFlag::Deselect);
						let _ = t.select_row(r, TableRowSelectFlag::Select);
						lead.set(r);
						t.redraw();
					}
				}
				s.send((m.picked)());
				s.send((m.menu)());
				return true;
			}
			if let Some(r) = row {
				lead.set(r);
			}
			false
		}
		Event::Released => {
			s.send((m.picked)());
			if let Some(cell) = m.cell {
				let hit = t
					.cursor2rowcol()
					.filter(|(_, r, c, _)| *r >= 0 && *r < t.rows() && *c >= 0);
				if let (Some((_, r, c, _)), true) = (hit, app::event_button() == LEFT) {
					s.send(cell(r, c, app::event_clicks()));
				}
			}
			if let Some(open) = m.open {
				if app::event_clicks() && app::event_button() == LEFT && lead.get() >= 0 {
					s.send(open());
				}
			}
			false
		}
		Event::KeyDown => {
			let key = app::event_key();
			if key == Key::Up || key == Key::Down {
				let n = t.rows();
				if n == 0 {
					return true;
				}
				let next = (lead.get() + if key == Key::Up { -1 } else { 1 }).clamp(0, n - 1);
				t.select_all_rows(TableRowSelectFlag::Deselect);
				let _ = t.select_row(next, TableRowSelectFlag::Select);
				t.set_row_position(next);
				lead.set(next);
				t.redraw();
				s.send((m.picked)());
				return true;
			}
			let Some(typed) = m.typed else { return false };
			let state = app::event_state();
			let text = app::event_text();
			let mut chars = text.chars();
			if let (Some(c), None) = (chars.next(), chars.next()) {
				let ctrl = state.contains(EventState::Ctrl) || state.contains(EventState::Alt);
				if !ctrl && !c.is_control() {
					s.send(typed(c));
					return true;
				}
			}
			false
		}
		Event::Paste => {
			s.send((m.paste)(app::event_text()));
			true
		}
		Event::Focus | Event::Unfocus => true,
		_ => false,
	}
}

/// The selected rows of a list of `n` rows.
pub fn selected_rows(t: &TableRow, n: usize) -> Vec<usize> {
	let mut t = t.clone();
	(0..n).filter(|i| t.row_selected(*i as i32)).collect()
}

/// Selects row `row` alone, or nothing, and makes it the lead row.
pub fn pick_row(t: &mut TableRow, lead: &Cell<i32>, row: Option<usize>) {
	t.select_all_rows(TableRowSelectFlag::Deselect);
	if let Some(r) = row {
		let _ = t.select_row(r as i32, TableRowSelectFlag::Select);
		t.set_row_position(r as i32);
	}
	lead.set(row.map_or(-1, |r| r as i32));
	t.redraw();
}

#[cfg(test)]
mod tests {
	use super::*;
	use palm_core::model::Memo;

	#[test]
	fn paste_takes_the_kept_records_only_for_their_own_text_and_kind() {
		let m = Memo {
			text: "Groceries".into(),
			..Memo::default()
		};
		keep("memo", vec![Record::Memo(m.clone())], "Groceries".into());
		assert_eq!(records_for("memo", "Groceries"), Some(vec![Record::Memo(m)]));
		assert_eq!(records_for("memo", "Groceries\n"), None);
		assert_eq!(records_for("todo", "Groceries"), None);
	}
}
