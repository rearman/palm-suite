//! The Edit Address dialog of AddressDialog.kt, after the Palm Desktop 3.1.1 Edit Address dialog, and the
//! Custom Field Labels dialog of Dialogs.kt.

use crate::category::IdChoice;
use crate::settings::Settings;
use crate::text_edit::Field;
use crate::view::Ctx;
use fltk::app;
use fltk::button::{Button, CheckButton, RoundButton};
use fltk::enums::{Align, Event, EventState, FrameType, Key};
use fltk::group::{Group, Tabs};
use fltk::input::Input;
use fltk::menu::Choice;
use fltk::prelude::*;
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use fltk::widget::Widget;
use fltk::window::Window;
use palm_core::model::{Address, LABELS};
use serde_json::Value;
use std::cell::Cell;
use std::rc::Rc;

/// The four custom field labels, from the setting "addressLabels" (a JSON array). A blank or missing label
/// gives "Custom 1" to "Custom 4".
pub fn custom_labels(settings: &Settings) -> [String; 4] {
	let saved = serde_json::from_str::<Value>(&settings.get("addressLabels", "[]")).ok();
	let saved = saved.as_ref().and_then(Value::as_array);
	std::array::from_fn(|i| {
		let text = saved.map_or(String::new(), |a| palm_core::json::at_str(a, i, ""));
		let text = text.trim();
		if text.is_empty() {
			format!("Custom {}", i + 1)
		} else {
			text.to_string()
		}
	})
}

/// The five phone label choices of a new address, from the setting "addressDefaultLabels". Work, Home, Fax,
/// Other and E-mail when none is saved or the value is bad.
pub fn default_labels(settings: &Settings) -> [i32; 5] {
	let first = [0, 1, 2, 3, 4];
	match serde_json::from_str::<Value>(&settings.get("addressDefaultLabels", "")) {
		Ok(Value::Array(a)) => {
			std::array::from_fn(|i| palm_core::json::at_i32(&a, i, i as i32).clamp(0, LABELS.len() as i32 - 1))
		}
		_ => first,
	}
}

/// The keys of a dialog: Alt and a letter moves the focus to the field of that letter, on its tab page when
/// it has one, and Enter outside a multi-line field presses OK.
type Keys = Vec<(char, Option<Group>, Widget)>;

fn handle_keys(win: &mut Window, tabs: Option<Tabs>, keys: Keys, ok: Button) {
	win.handle(move |_, ev| {
		if ev != Event::Shortcut {
			return false;
		}
		let key = app::event_key();
		if key == Key::Enter || key == Key::KPEnter {
			ok.clone().do_callback();
			return true;
		}
		if !app::event_state().contains(EventState::Alt) {
			return false;
		}
		let Some(c) = key.to_char().map(|c| c.to_ascii_lowercase()) else {
			return false;
		};
		let Some((_, page, w)) = keys.iter().find(|(k, _, _)| *k == c) else {
			return false;
		};
		if let (Some(t), Some(p)) = (tabs.clone().as_mut(), page) {
			let _ = t.set_value(p);
			t.redraw();
		}
		let _ = w.clone().take_focus();
		true
	});
}

/// A field label at (x, y, w, h): left-aligned, with `&` before the letter of its Alt key. FLTK underlines
/// that letter on a button only, so the label is a button without edges that takes no focus.
fn label(text: &str, x: i32, y: i32, w: i32, h: i32) -> Button {
	let mut b = Button::new(x, y, w, h, None);
	b.set_label(text);
	b.set_frame(FrameType::NoBox);
	b.set_down_frame(FrameType::NoBox);
	b.set_align(Align::Left | Align::Inside);
	b.clear_visible_focus();
	b
}

fn field(x: i32, y: i32, w: i32, h: i32) -> Input {
	let i = Input::new(x, y, w, h, None);
	Field::Input(i.clone()).attach_menu();
	i
}

/// A multi-line field that wraps its lines.
fn area(x: i32, y: i32, w: i32, h: i32) -> (TextEditor, TextBuffer) {
	let buf = TextBuffer::default();
	let mut e = TextEditor::new(x, y, w, h, None);
	e.set_buffer(buf.clone());
	e.wrap_mode(WrapMode::AtBounds, 0);
	e.set_scrollbar_align(Align::Right);
	Field::Editor(e.clone()).attach_menu();
	(e, buf)
}

/// What the buttons ask for.
#[derive(Clone, Copy)]
enum Act {
	Ok,
	New,
}

/// The widgets of the form. The fields hold the working copy.
struct Form {
	last: Input,
	first: Input,
	title: Input,
	company: Input,
	show: Vec<RoundButton>,
	labels: Vec<Choice>,
	phones: Vec<Input>,
	default_box: CheckButton,
	address: TextBuffer,
	city: Input,
	state: Input,
	zip: Input,
	country: Input,
	custom: Vec<Input>,
	note: TextBuffer,
	category: IdChoice,
	private_box: CheckButton,
}

impl Form {
	fn fill(&mut self, a: &Address) {
		self.last.set_value(&a.last_name);
		self.first.set_value(&a.first_name);
		self.title.set_value(&a.title);
		self.company.set_value(&a.company);
		for i in 0..5 {
			self.labels[i].set_value(a.phone_labels.get(i).copied().unwrap_or(i as i32));
			self.phones[i].set_value(a.phones.get(i).map_or("", |s| s.as_str()));
			self.show[i].set_value(i as i32 == a.show_phone.clamp(0, 4));
		}
		self.address.set_text(&a.address);
		self.city.set_value(&a.city);
		self.state.set_value(&a.state);
		self.zip.set_value(&a.zip);
		self.country.set_value(&a.country);
		for i in 0..4 {
			self.custom[i].set_value(a.custom.get(i).map_or("", |s| s.as_str()));
		}
		self.note.set_text(&a.note);
		self.category.select(a.category);
		self.private_box.set_value(a.is_private);
	}

	/// The form as a record. An old record keeps its id and uid.
	fn collect(&self, orig: Option<&Address>) -> Address {
		let t = |i: &Input| i.value().trim().to_string();
		Address {
			last_name: t(&self.last),
			first_name: t(&self.first),
			title: t(&self.title),
			company: t(&self.company),
			phone_labels: self.label_choices().to_vec(),
			phones: self.phones.iter().map(t).collect(),
			address: self.address.text(),
			city: t(&self.city),
			state: t(&self.state),
			zip: t(&self.zip),
			country: t(&self.country),
			custom: self.custom.iter().map(t).collect(),
			note: self.note.text(),
			show_phone: self.show.iter().position(|r| r.value()).unwrap_or(0) as i32,
			category: self.category.id(),
			is_private: self.private_box.value(),
			..orig.cloned().unwrap_or_default()
		}
	}

	fn label_choices(&self) -> [i32; 5] {
		std::array::from_fn(|i| self.labels[i].value().max(0))
	}
}

/// Opens Edit Address for `orig`, or for a new address in `new_category` when `orig` is None. OK and New save
/// the form: an old record always, a new record only when it has text. "Save as default" keeps the five
/// label choices for the next new address.
pub fn edit(ctx: &mut Ctx, orig: Option<Address>, new_category: i32) {
	let mut orig = orig;
	let blank = |cat: i32, settings: &Settings| Address {
		category: cat,
		phone_labels: default_labels(settings).to_vec(),
		..Address::default()
	};
	let mut win = Window::default().with_size(454, 365).with_label("Edit Address");
	let mut keys: Keys = Vec::new();
	let tabs = Tabs::new(9, 28, 326, 328, None);
	// The pages start below the tab row; (px, py) is the origin of the page layouts of AddressDialog.kt.
	let (px, py) = (11, 51);
	let page = |label: &str| {
		let mut g = Group::new(9, 49, 326, 307, None);
		g.set_label(label);
		g.set_frame(FrameType::UpBox);
		g
	};

	let name_page = page("&Name");
	let row = |text: &str, key: char, y: i32, keys: &mut Keys| {
		label(text, px + 14, py + y, 66, 21);
		let f = field(px + 83, py + y, 221, 21);
		keys.push((key, Some(name_page.clone()), f.as_base_widget()));
		f
	};
	let last = row("&Last name:", 'l', 10, &mut keys);
	let first = row("&First name:", 'f', 33, &mut keys);
	let title = row("&Title:", 't', 55, &mut keys);
	let company = row("Compan&y:", 'y', 78, &mut keys);
	label("S&how in list:", px + 14, py + 107, 90, 21);
	let (mut show, mut labels, mut phones) = (Vec::new(), Vec::new(), Vec::new());
	for y in [127, 151, 177, 203, 229] {
		let mut r = RoundButton::new(px + 60, py + y, 18, 21, None);
		r.set_type(fltk::button::ButtonType::Radio);
		show.push(r);
		let mut c = Choice::new(px + 83, py + y, 72, 21, None);
		for l in LABELS {
			c.add_choice(&format!("{l}:"));
		}
		labels.push(c);
		phones.push(field(px + 158, py + y, 144, 21));
	}
	keys.push(('h', Some(name_page.clone()), show[0].as_base_widget()));
	let default_box = CheckButton::new(px + 60, py + 260, 130, 18, "Sa&ve as default");
	name_page.end();

	let address_page = page("&Address");
	label("A&ddress:", px + 14, py + 10, 66, 21);
	let (address_e, address) = area(px + 83, py + 10, 221, 88);
	keys.push(('d', Some(address_page.clone()), address_e.as_base_widget()));
	let row = |text: &str, key: char, y: i32, keys: &mut Keys| {
		label(text, px + 14, py + y, 66, 21);
		let f = field(px + 83, py + y, 221, 21);
		keys.push((key, Some(address_page.clone()), f.as_base_widget()));
		f
	};
	let city = row("C&ity:", 'i', 101, &mut keys);
	let state = row("&State:", 's', 122, &mut keys);
	let zip = row("&Zip Code:", 'z', 143, &mut keys);
	let country = row("Co&untry:", 'u', 164, &mut keys);
	let names = custom_labels(ctx.settings);
	let mut custom = Vec::new();
	for (i, y) in [198, 219, 241, 262].into_iter().enumerate() {
		label(
			&format!("{}:", names[i]).replace('&', "&&").replace('@', "@@"),
			px + 14,
			py + y,
			66,
			21,
		);
		custom.push(field(px + 84, py + y, 221, 21));
	}
	address_page.end();

	// Alt+O is OK, so the Note tab has no Alt key.
	let note_page = page("Note");
	let (_, note) = area(px + 11, py + 37, 290, 250);
	note_page.end();
	tabs.end();

	let act: Rc<Cell<Option<Act>>> = Rc::new(Cell::new(None));
	let button = |text: &str, y: i32, a: Option<Act>| {
		let mut b = Button::new(342, y, 105, 24, None);
		b.set_label(text);
		let (act, mut win) = (act.clone(), win.clone());
		b.set_callback(move |_| match a {
			Some(a) => act.set(Some(a)),
			None => win.hide(),
		});
		b
	};
	let ok = button("&OK", 47, Some(Act::Ok));
	button("&Cancel", 77, None);
	button("Ne&w", 107, Some(Act::New));
	let mut category = IdChoice::new(Choice::new(342, 311, 105, 22, None), None, ctx.s, None);
	let mut items = vec![("Unfiled".to_string(), 0)];
	items.extend(ctx.store.address_categories.iter().map(|c| (c.name.clone(), c.id)));
	category.set_items(&items, false, 0);
	let private_box = CheckButton::new(342, 341, 105, 21, "&Private");
	win.end();
	handle_keys(&mut win, Some(tabs.clone()), keys, ok);

	let mut form = Form {
		last,
		first,
		title,
		company,
		show,
		labels,
		phones,
		default_box,
		address,
		city,
		state,
		zip,
		country,
		custom,
		note,
		category,
		private_box,
	};
	form.fill(&orig.clone().unwrap_or_else(|| blank(new_category, ctx.settings)));

	win.make_modal(true);
	win.show();
	while win.shown() {
		app::wait();
		let Some(a) = act.take() else { continue };
		if form.default_box.value() {
			let text = serde_json::to_string(&form.label_choices()).expect("a list of numbers is JSON");
			ctx.set("addressDefaultLabels", &text);
		}
		let mut rec = form.collect(orig.as_ref());
		if orig.is_some() || !rec.is_empty() {
			ctx.store.save_address(&mut rec);
		}
		match a {
			Act::Ok => win.hide(),
			// New clears the form for another new address; the category stays.
			Act::New => {
				orig = None;
				let cat = form.category.id();
				form.fill(&blank(cat, ctx.settings));
				let _ = form.last.take_focus();
			}
		}
	}
}

/// The Custom Field Labels dialog. Returns true on OK, which saves the four labels; a blank label keeps
/// the default name "Custom n".
pub fn custom_field_labels(ctx: &mut Ctx) -> bool {
	let labels = custom_labels(ctx.settings);
	// Template 2043 is 129 x 113 dlu, so the dialog is about 194 x 184 px.
	let mut win = Window::default().with_size(194, 150).with_label("Custom Field Labels");
	let mut fields = Vec::new();
	for (i, text) in labels.iter().enumerate() {
		let y = 12 + 26 * i as i32;
		label(&format!("Label {}:", i + 1), 10, y, 52, 21);
		let mut f = field(64, y, 120, 21);
		f.set_value(text);
		fields.push(f);
	}
	let saved = Rc::new(Cell::new(false));
	let mut ok = Button::new(22, 117, 72, 23, "OK");
	let mut cancel = Button::new(100, 117, 72, 23, "Cancel");
	{
		let (saved, mut win) = (saved.clone(), win.clone());
		ok.set_callback(move |_| {
			saved.set(true);
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	handle_keys(&mut win, None, Vec::new(), ok);
	crate::dialogs::run_modal(&mut win);
	if !saved.get() {
		return false;
	}
	let values: Vec<String> = fields
		.iter()
		.enumerate()
		.map(|(i, f)| {
			let t = f.value().trim().to_string();
			if t.is_empty() {
				format!("Custom {}", i + 1)
			} else {
				t
			}
		})
		.collect();
	ctx.set(
		"addressLabels",
		&serde_json::to_string(&values).expect("a list of strings is JSON"),
	);
	true
}

#[cfg(test)]
mod tests {
	use super::*;

	fn settings_with(pairs: &[(&str, &str)]) -> (Settings, std::path::PathBuf) {
		let dir = std::env::temp_dir().join(format!("palm-labels-{}", crate::store::new_uid()));
		std::fs::create_dir_all(&dir).unwrap();
		let mut s = Settings::open(&dir);
		for (k, v) in pairs {
			s.set(k, v).unwrap();
		}
		(s, dir)
	}

	#[test]
	fn custom_labels_fill_blank_and_missing_names() {
		let (s, dir) = settings_with(&[("addressLabels", r#"["Birthday", " ", 7]"#)]);
		assert_eq!(custom_labels(&s), ["Birthday", "Custom 2", "7", "Custom 4"]);
		let (bad, dir2) = settings_with(&[("addressLabels", "not json")]);
		assert_eq!(custom_labels(&bad), ["Custom 1", "Custom 2", "Custom 3", "Custom 4"]);
		std::fs::remove_dir_all(dir).unwrap();
		std::fs::remove_dir_all(dir2).unwrap();
	}

	#[test]
	fn default_labels_clamp_and_fall_back() {
		let (s, dir) = settings_with(&[("addressDefaultLabels", "[7, 9, -1]")]);
		assert_eq!(default_labels(&s), [7, 7, 0, 3, 4]);
		let (none, dir2) = settings_with(&[]);
		assert_eq!(default_labels(&none), [0, 1, 2, 3, 4]);
		std::fs::remove_dir_all(dir).unwrap();
		std::fs::remove_dir_all(dir2).unwrap();
	}
}
