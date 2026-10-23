//! The Find dialog of Dialogs.kt: Find what, Match whole word only and Match case, then a list of the records
//! found in every app. Go To closes it and shows the record in its view.

use crate::address::by_company;
use crate::dialogs::key_label;
use crate::view::Ctx;
use fltk::app;
use fltk::browser::HoldBrowser;
use fltk::button::{Button, CheckButton};
use fltk::enums::{Align, CallbackTrigger, Event, Key};
use fltk::frame::Frame;
use fltk::input::Input;
use fltk::prelude::*;
use fltk::window::Window;
use palm_core::days::ymd;
use palm_core::runner::Record;
use std::cell::Cell;
use std::rc::Rc;

/// The text Find searches in a record: the title and note of an event or a To Do, the text of a memo, or
/// the fields of an address.
fn searchable(r: &Record) -> String {
	match r {
		Record::Event(e) => format!("{}\n{}", e.title, e.note),
		Record::Todo(t) => format!("{}\n{}", t.title, t.note),
		Record::Memo(m) => m.text.clone(),
		Record::Address(a) => [
			&a.last_name,
			&a.first_name,
			&a.title,
			&a.company,
			&a.address,
			&a.city,
			&a.state,
			&a.zip,
			&a.country,
			&a.note,
		]
		.into_iter()
		.chain(a.phones.iter())
		.chain(a.custom.iter())
		.cloned()
		.collect::<Vec<_>>()
		.join("\n"),
		// The store finds no categories.
		Record::Category(_) => String::new(),
	}
}

fn is_private(r: &Record) -> bool {
	match r {
		Record::Event(e) => e.is_private,
		Record::Todo(t) => t.is_private,
		Record::Memo(m) => m.is_private,
		Record::Address(a) => a.is_private,
		Record::Category(_) => false,
	}
}

/// A character of a word, as \w of a Java regular expression.
fn word_char(c: char) -> bool {
	c.is_ascii_alphanumeric() || c == '_'
}

/// True when `text` holds `word` with no word character just before or after it. Case counts only with
/// `case`.
fn has_whole_word(text: &str, word: &str, case: bool) -> bool {
	let (hay, needle) = if case {
		(text.to_string(), word.to_string())
	} else {
		(text.to_lowercase(), word.to_lowercase())
	};
	if needle.is_empty() {
		return false;
	}
	let mut from = 0;
	while let Some(i) = hay[from..].find(&needle) {
		let at = from + i;
		let end = at + needle.len();
		let before = hay[..at].chars().next_back();
		let after = hay[end..].chars().next();
		// \b sits between a word character and another character, so it needs a word character at the edge.
		let starts = needle.chars().next().is_some_and(word_char) != before.is_some_and(word_char);
		let ends = needle.chars().next_back().is_some_and(word_char) != after.is_some_and(word_char);
		if starts && ends {
			return true;
		}
		from = at + hay[at..].chars().next().map_or(1, char::len_utf8);
	}
	false
}

/// The records that match `text`: the store finds it ignoring case, then Match case and Match whole word
/// filter it. Private records show only when `show_private`.
pub fn matches(ctx: &Ctx, text: &str, whole: bool, case: bool) -> Vec<Record> {
	ctx.store
		.find(text)
		.into_iter()
		.filter(|r| ctx.show_private || !is_private(r))
		.filter(|r| !case || searchable(r).contains(text))
		.filter(|r| !whole || has_whole_word(&searchable(r), text, case))
		.collect()
}

/// "10/19/2026": the month, the day and the four-digit year.
fn day_text(day: i64) -> String {
	let d = ymd(day);
	format!("{}/{}/{}", d.month, d.day, d.year)
}

/// The app name, the text and the right-hand column of a result row, as the official Find dialog shows them.
fn row_parts(r: &Record, by_company: bool) -> (&'static str, String, String) {
	match r {
		Record::Event(e) => {
			let when = if e.untimed {
				day_text(e.date)
			} else {
				format!("{} {:02}:{:02}", day_text(e.date), e.start / 60, e.start % 60)
			};
			("Date Book", e.title.clone(), when)
		}
		Record::Todo(t) => (
			"To Do List",
			t.title.clone(),
			if t.due >= 0 { day_text(t.due) } else { String::new() },
		),
		Record::Memo(m) => ("Memo Pad", m.title(), String::new()),
		Record::Address(a) => (
			"Address Book",
			a.list_name(by_company),
			a.list_phone().map_or(String::new(), |(n, l)| format!("{n} {l}")),
		),
		Record::Category(_) => ("", String::new(), String::new()),
	}
}

/// What the buttons ask for.
#[derive(Clone, Copy)]
enum Act {
	Find,
	Go,
}

/// The Find dialog. Returns the record that Go To picked, or None on Cancel.
pub fn show(ctx: &Ctx) -> Option<Record> {
	let (w, short_h, tall_h) = (420, 100, 334);
	let mut win = Window::default().with_size(w, short_h).with_label("Find");
	let act: Rc<Cell<Option<Act>>> = Rc::new(Cell::new(None));
	let mut field = Input::new(78, 10, 240, 22, None);
	let mut find_l = key_label("Find &what:", 10, 10, 66, 22, Align::Left);
	{
		let f = field.clone();
		find_l.set_callback(move |_| drop(f.clone().take_focus()));
	}
	let whole = CheckButton::new(10, 38, 220, 20, "&Match whole word only");
	let case = CheckButton::new(10, 58, 220, 20, "Match &case");
	let mut find_b = Button::new(330, 10, 80, 23, "&Find");
	find_b.deactivate();
	let mut go_b = Button::new(330, 39, 80, 23, "&Go To");
	go_b.deactivate();
	let mut cancel = Button::new(330, 68, 80, 23, "Cancel");
	let mut list = HoldBrowser::new(10, 104, w - 20, 200, None);
	list.hide();
	let mut status = Frame::new(10, 308, w - 20, 18, None);
	status.set_align(Align::Left | Align::Inside);
	status.hide();
	for (b, a) in [(&mut find_b, Act::Find), (&mut go_b, Act::Go)] {
		let act = act.clone();
		b.set_callback(move |_| act.set(Some(a)));
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	// Find stays gray until Find what has text; Enter in the field finds.
	{
		let (mut fb, act) = (find_b.clone(), act.clone());
		field.set_trigger(CallbackTrigger::Changed | CallbackTrigger::EnterKeyAlways);
		field.set_callback(move |f| {
			if f.value().trim().is_empty() {
				fb.deactivate();
			} else {
				fb.activate();
			}
			if app::event() == Event::KeyDown && matches!(app::event_key(), Key::Enter | Key::KPEnter) {
				act.set(Some(Act::Find));
			}
		});
	}
	{
		let (act, mut go) = (act.clone(), go_b.clone());
		list.set_callback(move |l| {
			if l.value() > 0 {
				go.activate();
				if app::event_clicks() {
					act.set(Some(Act::Go));
				}
			}
		});
	}
	win.end();
	// The name column is as wide as "Address Book", so the text columns line up.
	fltk::draw::set_font(crate::theme::fonts().sans, 11);
	let name_w = crate::draw::text_w("Address Book") + 12;
	list.set_column_widths(&[name_w, w - 20 - name_w - 130, 120]);
	list.set_column_char('\t');

	let mut found: Vec<Record> = Vec::new();
	win.make_modal(true);
	win.show();
	let _ = field.take_focus();
	while win.shown() {
		app::wait();
		let Some(a) = act.take() else { continue };
		match a {
			Act::Find => {
				let text = field.value().trim().to_string();
				if text.is_empty() {
					continue;
				}
				// The first search adds the list and its status line, and the dialog grows to show them.
				if !list.visible() {
					win.resize(win.x(), win.y(), w, tall_h);
					list.show();
					status.show();
				}
				found = matches(ctx, &text, whole.value(), case.value());
				list.clear();
				let by = by_company(ctx.settings);
				for r in &found {
					let (name, text, right) = row_parts(r, by);
					// "@." stops the format codes of a column, so a title may start with "@".
					let line = format!("@.{name}\t@.{}\t@r@.{right}", text.replace(['\t', '\n'], " "));
					list.add(&line);
				}
				let n = found.len();
				status.set_label(&if n == 1 {
					"1 item found.".to_string()
				} else {
					format!("{n} items found.")
				});
				// The first row is selected, so Go To is on.
				if n > 0 {
					list.select(1);
					go_b.activate();
				} else {
					go_b.deactivate();
				}
				win.redraw();
			}
			Act::Go => {
				let i = list.value();
				if i > 0 {
					win.hide();
					return found.get(i as usize - 1).cloned();
				}
			}
		}
	}
	None
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn a_whole_word_needs_no_word_character_at_its_edges() {
		assert!(has_whole_word("Call Bob today", "bob", false));
		assert!(!has_whole_word("Call Bobby today", "bob", false));
		assert!(!has_whole_word("Call Bob today", "bob", true));
		assert!(!has_whole_word("bob_x snake_bob", "bob", false));
		assert!(has_whole_word("bob_x, bob.", "bob", false));
		// Java's  also sits between a word character and a sign that starts or ends the word.
		assert!(has_whole_word("1+1=2", "+", false));
	}
}
