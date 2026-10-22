//! Cut, Copy and Paste of the records of a list, as RecordTransfer.kt, and the right-click menu of a record
//! list (Cut, Copy, Paste, Delete; Select All).
//!
//! Cut and Copy keep the records here and put their text on the system clipboard, for other programs. Paste
//! asks FLTK for the clipboard text; when it is the text of the kept records, the records are pasted as
//! copies, else the text makes new records. FLTK cannot read the clipboard at once, so the text arrives as
//! a paste event on the list, which sends it on.

use crate::text_edit::{clipboard_has_text, EditState};
use fltk::app;
use fltk::menu::{MenuFlag, MenuItem};
use palm_core::runner::Record;
use std::cell::RefCell;

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

/// The item that the user picked in a list menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ListAction {
	Cut,
	Copy,
	Paste,
	Delete,
	SelectAll,
}

/// Shows the right-click menu of a record list at the pointer.
pub fn list_menu(s: EditState) -> Option<ListAction> {
	let menu = MenuItem::new(&["Cu&t", "&Copy", "&Paste", "&Delete", "Select &All"]);
	for (i, on) in [s.cut, s.copy, s.paste, s.delete, s.select_all].iter().enumerate() {
		if let Some(mut item) = menu.at(i as i32) {
			if !on {
				item.deactivate();
			}
			if i == 3 {
				item.set_flag(MenuFlag::MenuDivider);
			}
		}
	}
	let item = menu.popup(app::event_x(), app::event_y())?;
	Some(match item.label().unwrap_or_default().as_str() {
		"Cu&t" => ListAction::Cut,
		"&Copy" => ListAction::Copy,
		"&Paste" => ListAction::Paste,
		"&Delete" => ListAction::Delete,
		_ => ListAction::SelectAll,
	})
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
