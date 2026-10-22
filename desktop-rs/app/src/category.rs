//! The category drop-down lists, with "Edit Categories..." at the end as in Palm Desktop, and the Edit
//! Categories dialog of Dialogs.kt.

use crate::dialogs::{prompt, run_modal};
use crate::msg::Msg;
use crate::store::DesktopStore;
use fltk::app::Sender;
use fltk::browser::HoldBrowser;
use fltk::button::{Button, RoundButton};
use fltk::dialog;
use fltk::menu::Choice;
use fltk::prelude::*;
use fltk::window::Window;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// The last item of every category drop-down list, as in Palm Desktop.
pub const EDIT_CATEGORIES: &str = "Edit Categories...";
pub const ALL: i32 = -1;
pub const UNFILED: i32 = 0;

/// A text as one FLTK menu label: "/" would start a submenu, "&" an Alt key and "@" a symbol.
pub fn menu_label(s: &str) -> String {
	s.replace('\\', "\\\\")
		.replace('/', "\\/")
		.replace('&', "&&")
		.replace('@', "@@")
}

/// A drop-down list of (name, id) items. With `edit`, its last item opens Edit Categories for `app` and
/// keeps the selection.
#[derive(Clone)]
pub struct IdChoice {
	pub choice: Choice,
	ids: Rc<RefCell<Vec<i32>>>,
	last: Rc<Cell<i32>>,
}

impl IdChoice {
	/// `changed` is sent when the user picks an item other than Edit Categories.
	pub fn new(choice: Choice, edit_app: Option<i32>, s: Sender<Msg>, changed: Option<fn() -> Msg>) -> IdChoice {
		let c = IdChoice {
			choice,
			ids: Rc::new(RefCell::new(Vec::new())),
			last: Rc::new(Cell::new(0)),
		};
		let (ids, last) = (c.ids.clone(), c.last.clone());
		let mut choice = c.choice.clone();
		choice.set_callback(move |ch| {
			let i = ch.value();
			if i >= 0 && i as usize >= ids.borrow().len() {
				ch.set_value(last.get());
				if let Some(app) = edit_app {
					s.send(Msg::EditCategories(app));
				}
				return;
			}
			last.set(i);
			if let Some(m) = changed {
				s.send(m());
			}
		});
		c
	}

	/// Replaces the items, then selects `select`, or the first item when no item has it.
	pub fn set_items(&mut self, items: &[(String, i32)], with_edit: bool, select: i32) {
		self.choice.clear();
		for (name, _) in items {
			self.choice.add_choice(&menu_label(name));
		}
		if with_edit {
			self.choice.add_choice(EDIT_CATEGORIES);
		}
		*self.ids.borrow_mut() = items.iter().map(|(_, id)| *id).collect();
		self.select(select);
	}

	pub fn select(&mut self, id: i32) {
		let i = self.ids.borrow().iter().position(|x| *x == id).unwrap_or(0) as i32;
		self.choice.set_value(i);
		self.last.set(i);
	}

	/// The id of the selected item, or the first id when nothing is selected.
	pub fn id(&self) -> i32 {
		let ids = self.ids.borrow();
		ids.get(self.choice.value().max(0) as usize)
			.or(ids.first())
			.copied()
			.unwrap_or(UNFILED)
	}
}

/// What the Edit Categories buttons ask for.
#[derive(Clone, Copy)]
enum Action {
	New,
	Rename,
	Delete,
}

fn records_in(store: &DesktopStore, app: i32, id: i32) -> bool {
	match app {
		1 => store.memos.iter().any(|m| m.category == id),
		2 => store.addresses.iter().any(|a| a.category == id),
		_ => store.todos.iter().any(|t| t.category == id),
	}
}

fn delete_records_in(store: &mut DesktopStore, app: i32, id: i32) {
	match app {
		1 => {
			let ids: Vec<i64> = store.memos.iter().filter(|m| m.category == id).map(|m| m.id).collect();
			ids.into_iter().for_each(|i| store.delete_memo(i));
		}
		2 => {
			let ids: Vec<i64> = store
				.addresses
				.iter()
				.filter(|a| a.category == id)
				.map(|a| a.id)
				.collect();
			ids.into_iter().for_each(|i| store.delete_address(i));
		}
		_ => {
			let ids: Vec<i64> = store.todos.iter().filter(|t| t.category == id).map(|t| t.id).collect();
			ids.into_iter().for_each(|i| store.delete_todo(i));
		}
	}
}

/// Asks what to do with the records of a category in use: Some(true) deletes them, Some(false) moves
/// them to Unfiled, None is Cancel.
fn ask_delete_records() -> Option<bool> {
	let mut win = Window::default().with_size(260, 120).with_label("Delete Category");
	let mut mv = RoundButton::new(20, 14, 220, 22, "&Move all items to Unfiled");
	mv.set_type(fltk::button::ButtonType::Radio);
	mv.set_value(true);
	let mut del = RoundButton::new(20, 40, 220, 22, "&Delete all items");
	del.set_type(fltk::button::ButtonType::Radio);
	let answer = Rc::new(Cell::new(None));
	let mut ok = Button::new(54, 82, 72, 23, "OK");
	let mut cancel = Button::new(134, 82, 72, 23, "Cancel");
	{
		let (answer, del, mut win) = (answer.clone(), del.clone(), win.clone());
		ok.set_callback(move |_| {
			answer.set(Some(del.value()));
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

/// The Edit Categories dialog of one app (0 To Do, 1 Memo Pad, 2 Address Book). It writes the store as
/// the user works; the app reads the store again when the dialog closes.
pub fn edit_categories(store: &mut DesktopStore, app: i32) {
	let title = match app {
		1 => "Edit Memo Categories",
		2 => "Edit Address Categories",
		_ => "Edit To Do Categories",
	};
	let mut win = Window::default().with_size(352, 206).with_label(title);
	let mut list = HoldBrowser::new(10, 10, 240, 186, None);
	let action: Rc<Cell<Option<Action>>> = Rc::new(Cell::new(None));
	let mut ok = Button::new(262, 10, 80, 23, "OK");
	let mut buttons = Vec::new();
	for (i, (label, a)) in [
		("&New...", Action::New),
		("&Delete...", Action::Delete),
		("&Rename...", Action::Rename),
	]
	.into_iter()
	.enumerate()
	{
		let mut b = Button::new(262, 39 + 29 * i as i32, 80, 23, None);
		b.set_label(label);
		let action = action.clone();
		b.set_callback(move |_| action.set(Some(a)));
		buttons.push(b);
	}
	{
		let mut win = win.clone();
		ok.set_callback(move |_| win.hide());
	}
	win.end();

	// The rows follow the store's sorted list; `select` picks the row of a category id.
	let reload = |list: &mut HoldBrowser, store: &DesktopStore, select: Option<i32>| {
		let keep = list.value();
		list.clear();
		for c in store.categories_for(app) {
			list.add(&c.name.replace('@', "@@"));
		}
		let row = select.and_then(|id| store.categories_for(app).iter().position(|c| c.id == id));
		match row {
			Some(r) => list.select(r as i32 + 1),
			None if keep >= 1 && keep <= list.size() => list.select(keep),
			None if list.size() > 0 => list.select(1),
			None => {}
		}
	};
	reload(&mut list, store, None);
	win.make_modal(true);
	win.show();
	while win.shown() {
		fltk::app::wait();
		let has = list.value() >= 1;
		for b in buttons.iter_mut().skip(1) {
			if has {
				b.activate();
			} else {
				b.deactivate();
			}
		}
		let Some(a) = action.take() else { continue };
		let selected = store
			.categories_for(app)
			.get((list.value() - 1).max(0) as usize)
			.cloned()
			.filter(|_| has);
		match a {
			Action::New => {
				if let Some(name) = prompt("Name:", "") {
					let c = store.add_category(&name, app);
					reload(&mut list, store, Some(c.id));
				}
			}
			Action::Rename => {
				let Some(c) = selected else { continue };
				if let Some(name) = prompt("Name:", &c.name) {
					store.rename_category(app, c.id, &name);
					reload(&mut list, store, Some(c.id));
				}
			}
			Action::Delete => {
				let Some(c) = selected else { continue };
				if records_in(store, app, c.id) {
					match ask_delete_records() {
						None => continue,
						Some(true) => delete_records_in(store, app, c.id),
						Some(false) => {}
					}
				} else if dialog::choice2_default("Delete the category? Its records move to Unfiled.", "Yes", "No", "")
					!= Some(0)
				{
					continue;
				}
				store.delete_category(app, c.id);
				reload(&mut list, store, None);
			}
		}
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn menu_labels_keep_slashes_ampersands_and_at_signs() {
		assert_eq!(menu_label(r"A/B & C @ D \ E"), r"A\/B && C @@ D \\ E");
		assert_eq!(menu_label("Work"), "Work");
	}
}
