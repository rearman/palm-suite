//! The small modal dialogs of Dialogs.kt: the delete question, a name prompt and List By. Each one opens,
//! runs its own event loop until it closes, and returns the answer, as a Swing modal dialog does.

use crate::settings::Settings;
use fltk::app;
use fltk::button::{Button, RoundButton};
use fltk::dialog;
use fltk::enums::{Align, FrameType, Key};
use fltk::group::Group;
use fltk::prelude::*;
use fltk::window::Window;
use std::cell::Cell;
use std::rc::Rc;

/// The question before a delete: `one` for a single record, else the count and the plural `many`.
pub fn delete_question(count: usize, one: &str, many: &str) -> String {
	if count == 1 {
		one.to_string()
	} else {
		format!("Delete the {count} selected {many}?")
	}
}

/// Asks before a record is deleted. True at once when `no_confirm` is set (Shift+Delete) or the
/// "confirmDelete" setting is off.
pub fn confirm_delete(settings: &Settings, no_confirm: bool, question: &str) -> bool {
	if no_confirm || !settings.get_bool("confirmDelete", true) {
		return true;
	}
	dialog::choice2_default(question, "Yes", "No", "") == Some(0)
}

/// Asks for a name. None on Cancel or a blank name; else the name, trimmed.
pub fn prompt(label: &str, initial: &str) -> Option<String> {
	dialog::input_default(label, initial)
		.map(|s| s.trim().to_string())
		.filter(|s| !s.is_empty())
}

/// Runs a modal window until it closes.
pub fn run_modal(win: &mut Window) {
	win.make_modal(true);
	win.show();
	while win.shown() {
		app::wait();
	}
}

/// The List By form of the Address Book and the Memo Pad: one radio for each choice in a titled group, then
/// OK and Cancel. The index of the chosen radio on OK, None on Cancel.
pub fn list_by(group_title: &str, choices: &[&str], selected: usize) -> Option<usize> {
	let (w, h) = (194, 70 + 22 * choices.len() as i32 + 34);
	let mut win = Window::default().with_size(w, h).with_label("List By");
	let mut group = Group::new(10, 16, w - 20, 22 * choices.len() as i32 + 20, None);
	group.set_frame(FrameType::EngravedBox);
	group.set_label(group_title);
	group.set_align(Align::TopLeft);
	let mut radios = Vec::new();
	for (i, c) in choices.iter().enumerate() {
		let mut r = RoundButton::new(20, 26 + 22 * i as i32, w - 40, 20, None);
		r.set_label(c);
		r.set_type(fltk::button::ButtonType::Radio);
		r.set_value(i == selected);
		radios.push(r);
	}
	group.end();
	let answer = Rc::new(Cell::new(None));
	let by = h - 32;
	let mut ok = Button::new(10, by, (w - 26) / 2, 23, "OK");
	let mut cancel = Button::new(16 + (w - 26) / 2, by, (w - 26) / 2, 23, "Cancel");
	ok.set_shortcut(fltk::enums::Shortcut::None | Key::Enter);
	{
		let (answer, radios, mut win) = (answer.clone(), radios.clone(), win.clone());
		ok.set_callback(move |_| {
			answer.set(radios.iter().position(|r| r.value()));
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	run_modal(&mut win);
	answer.get()
}

#[cfg(test)]
mod tests {
	#[test]
	fn delete_question_names_one_record_or_the_count() {
		assert_eq!(
			super::delete_question(1, "Delete the memo?", "memos"),
			"Delete the memo?"
		);
		assert_eq!(
			super::delete_question(3, "Delete the memo?", "memos"),
			"Delete the 3 selected memos?"
		);
	}
}
