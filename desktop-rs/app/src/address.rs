//! The Address Book view, as AddressPanel.kt: the Address List with its category filter, Look up and the List
//! by, Edit and New buttons on the left, and the card of the selected address on the right.

use crate::address_dialog::{self, custom_labels};
use crate::category::{menu_label, IdChoice, ALL, UNFILED};
use crate::dialogs::{confirm_delete, delete_question, list_by, run_modal};
use crate::draw::{fit, text_w};
use crate::msg::{AddressMsg, Msg};
use crate::records::{self, list_state, ListAction, ListMsgs};
use crate::settings::Settings;
use crate::split::split;
use crate::text_edit::{EditState, Field};
use crate::theme::{self, PANEL, SELECT};
use crate::view::Ctx;
use fltk::app::{self, Sender};
use fltk::button::Button;
use fltk::dialog;
use fltk::draw;
use fltk::enums::{Align, CallbackTrigger, Color, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::input::Input;
use fltk::menu::Choice;
use fltk::prelude::*;
use fltk::table::{TableContext, TableRow, TableRowSelectFlag, TableRowSelectMode};
use fltk::window::Window;
use palm_core::model::{address_order, is_blank, Address, EMAIL, LABELS};
use palm_core::runner::Record;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

const ROW_H: i32 = 16;
/// The gap after the number column of the Address List, measured in Palm Desktop.
const RIGHT: i32 = 36;
/// The most of a narrow row that the number column takes.
const MAX_SHARE: f64 = 0.55;

/// One row of the Address List: the list name, and the number and its letter. The address pane of the
/// Day view draws the same rows.
pub(crate) struct Row {
	pub(crate) name: String,
	pub(crate) number: String,
	pub(crate) letter: String,
}

/// What the card draws.
struct Card {
	address: Address,
	category: String,
	labels: [String; 4],
}

pub struct AddressView {
	/// The split that holds the view; the view group makes it its resizable widget.
	pub root: Group,
	filter: IdChoice,
	table: TableRow,
	rows: Rc<RefCell<Vec<Row>>>,
	/// The row clicked or moved to last.
	lead: Rc<Cell<i32>>,
	look: Input,
	edit_b: Button,
	card: Frame,
	card_data: Rc<RefCell<Option<Card>>>,
	/// The addresses of the list, in list order.
	shown: Vec<Address>,
	/// The address the user selected, kept over a rebuild.
	selected_id: Option<i64>,
}

impl AddressView {
	/// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group.
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>, settings: &Settings) -> AddressView {
		let fonts = theme::fonts();
		// 302 px: the AddressCard width of Palm Desktop.
		let sp = split(x, y, w, h, settings.get_int("addressSplit", 302), "addressSplit", s);

		// The list side: the title, the filter, the list, Look up, and the List by, Edit and New buttons.
		sp.left.begin();
		let (lx, ly, lw, lh) = (sp.left.x(), sp.left.y(), sp.left.w(), sp.left.h());
		let mut title = Frame::new(lx + 4, ly + 4, 120, 22, "Address List");
		title.set_frame(FrameType::DownBox);
		title.set_label_font(fonts.arial_bold);
		title.set_label_size(14);
		title.set_align(Align::Center | Align::Inside);
		let filter = IdChoice::new(
			Choice::new(lx + lw - 114, ly + 4, 110, 22, None),
			Some(2),
			s,
			Some(|| Msg::Address(AddressMsg::Filter)),
		);

		let by = ly + lh - 29;
		let look_y = by - 5 - 21;
		let rows: Rc<RefCell<Vec<Row>>> = Rc::new(RefCell::new(Vec::new()));
		let lead = Rc::new(Cell::new(-1));
		let mut table = TableRow::new(lx + 4, ly + 30, lw - 8, look_y - 4 - (ly + 30), None);
		table.set_type(TableRowSelectMode::Multi);
		table.set_rows(0);
		table.set_cols(1);
		table.set_row_height_all(ROW_H);
		table.set_col_header(false);
		table.set_row_header(false);
		table.set_frame(FrameType::DownBox);
		table.set_color(Color::White);
		table.end();
		let r = rows.clone();
		// The widest "number L" of the list sets the number column; measured at the start of each draw.
		let widest = Rc::new(Cell::new(0));
		table.draw_cell(move |t, ctx, row, _col, cx, cy, cw, ch| match ctx {
			TableContext::StartPage => {
				records::fit_column(t, ROW_H);
				widest.set(widest_number(&r.borrow()));
			}
			TableContext::Cell => {
				let rows = r.borrow();
				let Some(line) = rows.get(row as usize) else { return };
				draw::push_clip(cx, cy, cw, ch);
				draw_row(line, t.row_selected(row), (cx, cy, cw, ch), widest.get());
				draw::pop_clip();
			}
			_ => {}
		});
		let l = lead.clone();
		table.handle(move |t, ev| records::list_event(t, ev, &l, s, LIST_MSGS));

		let mut look_label = Frame::new(lx + 4, look_y, 46, 21, "Look up:");
		look_label.set_align(Align::Left | Align::Inside);
		let mut look = Input::new(lx + 52, look_y, lw - 56, 21, None);
		look.set_trigger(CallbackTrigger::Changed);
		look.set_callback(move |_| s.send(Msg::Address(AddressMsg::Look)));
		Field::Input(look.clone()).attach_menu();

		let mut list_by_b = Button::new(lx + lw - 247, by, 75, 23, "&List by...");
		list_by_b.set_callback(move |_| s.send(Msg::ListBy));
		let mut edit_b = Button::new(lx + lw - 166, by, 75, 23, "Ed&it...");
		edit_b.set_callback(move |_| s.send(Msg::Address(AddressMsg::Edit)));
		edit_b.deactivate();
		let mut new_b = Button::new(lx + lw - 85, by, 75, 23, "&New...");
		new_b.set_callback(move |_| s.send(Msg::Address(AddressMsg::New)));
		// The title and Look up's label keep their place, the filter and the buttons keep to the right and the
		// bottom, Look up widens and the list grows.
		let grow = Frame::new(lx + 128, ly + 30, lw - 380, look_y - 4 - (ly + 30), None);
		sp.left.resizable(&grow);
		sp.left.end();

		// The card side: the card of the selected address on the panel gray, inside a sunken edge.
		sp.right.begin();
		let (rx, ry, rw, rh) = (sp.right.x(), sp.right.y(), sp.right.w(), sp.right.h());
		let mut card = Frame::new(rx + 8, ry + 8, rw - 16, rh - 12, None);
		card.set_frame(FrameType::DownBox);
		card.set_color(PANEL);
		let card_data: Rc<RefCell<Option<Card>>> = Rc::new(RefCell::new(None));
		let d = card_data.clone();
		card.draw(move |f| {
			if let Some(c) = d.borrow().as_ref() {
				draw::push_clip(f.x() + 2, f.y() + 2, f.w() - 4, f.h() - 4);
				draw_card(c, f.x(), f.y(), f.w());
				draw::pop_clip();
			}
		});
		sp.right.resizable(&card);
		sp.right.end();

		AddressView {
			root: sp.outer,
			filter,
			table,
			rows,
			lead,
			look,
			edit_b,
			card,
			card_data,
			shown: Vec::new(),
			selected_id: None,
		}
	}

	pub fn table(&self) -> &TableRow {
		&self.table
	}

	pub fn look(&self) -> &Input {
		&self.look
	}

	/// The list takes the focus when the view opens.
	pub fn focus(&mut self) {
		let _ = self.table.take_focus();
	}

	fn selected_rows(&self) -> Vec<usize> {
		records::selected_rows(&self.table, self.shown.len())
	}

	fn selected(&self) -> Vec<Address> {
		self.selected_rows()
			.into_iter()
			.map(|i| self.shown[i].clone())
			.collect()
	}

	/// The address of the lead row when it is selected, else the first selected address.
	fn lead_address(&self) -> Option<Address> {
		let mut t = self.table.clone();
		let l = self.lead.get();
		if l >= 0 && (l as usize) < self.shown.len() && t.row_selected(l) {
			return Some(self.shown[l as usize].clone());
		}
		self.selected_rows().first().map(|i| self.shown[*i].clone())
	}

	/// "3 of 12" for the status bar.
	pub fn position(&self) -> String {
		match self.selected_rows().first() {
			Some(i) => format!("{} of {}", i + 1, self.shown.len()),
			None => String::new(),
		}
	}

	pub fn list_edit_state(&self) -> EditState {
		list_state(!self.selected_rows().is_empty(), !self.shown.is_empty())
	}

	/// Reads the store and the settings again: the filter, the list and the card.
	pub fn refresh(&mut self, ctx: &mut Ctx) {
		self.refresh_combos(ctx);
		self.rebuild_list(ctx);
	}

	/// Rebuilds the filter. The setting "addressCategory" picks its item; a deleted category gives All.
	fn refresh_combos(&mut self, ctx: &mut Ctx) {
		let cur = ctx.settings.get_int("addressCategory", ALL);
		let mut items = vec![("All".to_string(), ALL)];
		items.extend(ctx.store.address_categories.iter().map(|c| (c.name.clone(), c.id)));
		items.push(("Unfiled".to_string(), UNFILED));
		if cur != ALL && !items.iter().any(|(_, id)| *id == cur) {
			ctx.set("addressCategory", &ALL.to_string());
		}
		self.filter.set_items(&items, true, cur);
	}

	/// Fills the list with the visible addresses and selects the one of `selected_id`. As in Palm Desktop,
	/// the card always shows a record: the first one when none is selected.
	fn rebuild_list(&mut self, ctx: &Ctx) {
		let by_company = by_company(ctx.settings);
		self.shown = visible(ctx);
		*self.rows.borrow_mut() = self.shown.iter().map(|a| row_of(a, by_company)).collect();
		self.table.set_rows(self.shown.len() as i32);
		// New rows take FLTK's default height.
		self.table.set_row_height_all(ROW_H);
		let row = self
			.selected_id
			.and_then(|id| self.shown.iter().position(|a| a.id == id))
			.or(if self.shown.is_empty() { None } else { Some(0) });
		self.selected_id = row.map(|r| self.shown[r].id);
		records::pick_row(&mut self.table, &self.lead, row);
		self.show_card(ctx);
	}

	fn show_card(&mut self, ctx: &Ctx) {
		let lead = self.lead_address();
		if lead.is_some() {
			self.edit_b.activate();
		} else {
			self.edit_b.deactivate();
		}
		*self.card_data.borrow_mut() = lead.map(|a| Card {
			category: ctx.store.category_name(a.category),
			labels: custom_labels(ctx.settings),
			address: a,
		});
		self.card.redraw();
	}

	pub fn new_record(&mut self, ctx: &mut Ctx) {
		// A new address goes in the category the list shows, else Unfiled.
		let shown = ctx.settings.get_int("addressCategory", ALL);
		address_dialog::edit(ctx, None, shown.max(UNFILED));
	}

	pub fn edit_selected(&mut self, ctx: &mut Ctx) {
		if let Some(a) = self.lead_address() {
			address_dialog::edit(ctx, Some(a), UNFILED);
		}
	}

	fn delete_selected(&mut self, ctx: &mut Ctx) {
		let picked = self.selected();
		if picked.is_empty()
			|| !confirm_delete(
				ctx.settings,
				ctx.no_confirm,
				&delete_question(picked.len(), "Delete the address?", "addresses"),
			) {
			return;
		}
		self.selected_id = None;
		for a in picked {
			ctx.store.delete_address(a.id);
		}
	}

	/// The Address Book List By form: 0 last name first, 1 company first.
	pub fn list_by(&mut self, ctx: &mut Ctx) {
		let now = if by_company(ctx.settings) { 1 } else { 0 };
		let choices = ["&Last name, first name", "&Company, last name"];
		if let Some(order) = list_by("Field Order", &choices, now) {
			ctx.set("addressListBy", &order.to_string());
			self.refresh(ctx);
		}
	}

	pub fn handle(&mut self, msg: AddressMsg, ctx: &mut Ctx) {
		match msg {
			AddressMsg::Picked => {
				self.selected_id = self.lead_address().map(|a| a.id);
				self.show_card(ctx);
			}
			AddressMsg::Filter => {
				ctx.set("addressCategory", &self.filter.id().to_string());
				self.refresh(ctx);
			}
			AddressMsg::Look => self.find_by_name(ctx),
			AddressMsg::Edit => self.edit_selected(ctx),
			AddressMsg::New => self.new_record(ctx),
			AddressMsg::ListMenu => {
				let lead = self.lead_address();
				let dial = lead.as_ref().is_some_and(|a| !phone_slots(a).is_empty());
				let extra = [("&Edit...", lead.is_some()), ("D&ial...", dial)];
				match records::list_menu(self.list_edit_state(), &extra) {
					Some(ListAction::Extra(0)) => self.edit_selected(ctx),
					Some(ListAction::Extra(_)) => {
						if let Some(a) = lead {
							dial_address(&a);
						}
					}
					Some(a) => self.list_action(a, ctx),
					None => {}
				}
			}
			AddressMsg::PasteText(text) => self.paste_text(ctx, &text),
		}
	}

	/// Selects the first address whose list name starts with the Look up text, ignoring case.
	fn find_by_name(&mut self, ctx: &Ctx) {
		let text = self.look.value().to_lowercase();
		if text.is_empty() {
			return;
		}
		let by_company = by_company(ctx.settings);
		let Some(i) = self
			.shown
			.iter()
			.position(|a| a.list_name(by_company).to_lowercase().starts_with(&text))
		else {
			return;
		};
		records::pick_row(&mut self.table, &self.lead, Some(i));
		self.selected_id = Some(self.shown[i].id);
		self.show_card(ctx);
	}

	/// Cut, Copy, Paste, Delete and Select All of the selected addresses. The text of an address is its
	/// list name, then the lines of its card.
	pub fn list_action(&mut self, a: ListAction, ctx: &mut Ctx) {
		match a {
			ListAction::Copy | ListAction::Cut => {
				let picked = self.selected();
				if picked.is_empty() {
					return;
				}
				let (by_company, labels) = (by_company(ctx.settings), custom_labels(ctx.settings));
				let text = picked
					.iter()
					.map(|x| {
						let mut lines = vec![x.list_name(by_company)];
						lines.extend(card_lines(x, &labels));
						lines.join("\n")
					})
					.collect::<Vec<_>>()
					.join("\n");
				records::put("address", picked.iter().cloned().map(Record::Address).collect(), text);
				if a == ListAction::Cut {
					self.selected_id = None;
					for x in picked {
						ctx.store.delete_address(x.id);
					}
				}
			}
			ListAction::Paste => app::paste_text(&self.table),
			ListAction::Delete => self.delete_selected(ctx),
			ListAction::SelectAll => {
				self.table.select_all_rows(TableRowSelectFlag::Select);
				self.table.redraw();
			}
			ListAction::Extra(_) => {}
		}
	}

	/// A paste into the list gives copies of the kept addresses when the text is theirs. Other text makes
	/// no address, as in Palm Desktop.
	fn paste_text(&mut self, ctx: &mut Ctx, text: &str) {
		for r in records::records_for("address", text).unwrap_or_default() {
			if let Record::Address(a) = r {
				ctx.store.save_address(&mut a.dup_key());
			}
		}
	}
}

/// The messages of the Address List. A double click edits the row.
const LIST_MSGS: ListMsgs = ListMsgs {
	picked: || Msg::Address(AddressMsg::Picked),
	menu: || Msg::Address(AddressMsg::ListMenu),
	paste: |t| Msg::Address(AddressMsg::PasteText(t)),
	typed: None,
	open: Some(|| Msg::Address(AddressMsg::Edit)),
	cell: None,
};

/// The row of `a` in an address list.
pub(crate) fn row_of(a: &Address, by_company: bool) -> Row {
	let (number, letter) = a
		.list_phone()
		.map_or((String::new(), String::new()), |(n, l)| (n, l.to_string()));
	Row {
		name: a.list_name(by_company),
		number,
		letter,
	}
}

/// The width of the widest "number L" of `rows`, which sets the number column.
pub(crate) fn widest_number(rows: &[Row]) -> i32 {
	draw::set_font(theme::fonts().sans, 11);
	rows.iter()
		.filter(|x| !x.number.is_empty())
		.map(|x| text_w(&format!("{} {}", x.number, x.letter)))
		.max()
		.unwrap_or(0)
}

/// True when the Address List sorts by company (the setting "addressListBy" 1).
pub(crate) fn by_company(settings: &Settings) -> bool {
	settings.get_int("addressListBy", 0) == 1
}

/// The addresses the Address List shows: the category of the setting "addressCategory" (-1 is All), the
/// private ones only when they are shown, in list order.
pub fn visible(ctx: &Ctx) -> Vec<Address> {
	let cat = ctx.settings.get_int("addressCategory", ALL);
	let shown: Vec<Address> = ctx
		.store
		.addresses
		.iter()
		.filter(|a| (ctx.show_private || !a.is_private) && (cat == ALL || a.category == cat))
		.cloned()
		.collect();
	address_order(&shown, by_company(ctx.settings))
}

/// The text lines of the card below the name. Empty fields are skipped.
pub fn card_lines(a: &Address, labels: &[String; 4]) -> Vec<String> {
	let mut lines = Vec::new();
	let mut add = |text: &str| {
		if !is_blank(text) {
			lines.push(text.to_string());
		}
	};
	add(&a.title);
	add(&a.company);
	for i in 0..5 {
		let number = a.phones.get(i).map_or("", |s| s.as_str());
		if !is_blank(number) {
			let label = a
				.phone_labels
				.get(i)
				.and_then(|l| LABELS.get(*l as usize))
				.unwrap_or(&"Other");
			add(&format!("{label}:  {number}"));
		}
	}
	a.address.lines().for_each(&mut add);
	let head = [a.city.as_str(), a.state.as_str()]
		.into_iter()
		.filter(|s| !is_blank(s))
		.collect::<Vec<_>>()
		.join(", ");
	add(&[head.as_str(), a.zip.as_str()]
		.into_iter()
		.filter(|s| !is_blank(s))
		.collect::<Vec<_>>()
		.join(" "));
	add(&a.country);
	for (i, label) in labels.iter().enumerate() {
		let value = a.custom.get(i).map_or("", |s| s.as_str());
		if !is_blank(value) {
			add(&format!("{label}: {value}"));
		}
	}
	a.note.lines().for_each(&mut add);
	lines
}

/// The phone slots of `a` that Dial offers: a number that is not blank, with a label other than E-mail.
fn phone_slots(a: &Address) -> Vec<usize> {
	(0..5)
		.filter(|i| a.phones.get(*i).is_some_and(|p| !is_blank(p)) && a.phone_labels.get(*i).copied() != Some(EMAIL))
		.collect()
}

/// The Dial dialog: the phones of `a` that have a number, the list number first. Palm Desktop dialed
/// through a modem; a tel: link goes to the calling app of the system.
fn dial_address(a: &Address) {
	let slots = phone_slots(a);
	if slots.is_empty() {
		return;
	}
	let mut win = Window::default().with_size(280, 84).with_label("Dial");
	let mut pick = Choice::new(14, 14, 252, 22, None);
	for i in &slots {
		let label = a
			.phone_labels
			.get(*i)
			.and_then(|l| LABELS.get(*l as usize))
			.unwrap_or(&"Other");
		pick.add_choice(&menu_label(&format!("{label}: {}", a.phones[*i])));
	}
	pick.set_value(slots.iter().position(|i| *i as i32 == a.show_phone).unwrap_or(0) as i32);
	let go = Rc::new(Cell::new(false));
	let mut dial_b = Button::new(64, 48, 72, 23, "&Dial");
	let mut cancel = Button::new(144, 48, 72, 23, "Cancel");
	{
		let (go, mut win) = (go.clone(), win.clone());
		dial_b.set_callback(move |_| {
			go.set(true);
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	run_modal(&mut win);
	if !go.get() {
		return;
	}
	let number = &a.phones[slots[pick.value().max(0) as usize]];
	if fltk::utils::open_uri(&format!("tel:{}", tel_digits(number))).is_err() {
		dialog::message_default("This computer has no app that makes phone calls.");
	}
}

/// The digits and the plus sign of a phone number, for a tel: link.
fn tel_digits(number: &str) -> String {
	number.chars().filter(|c| c.is_ascii_digit() || *c == '+').collect()
}

/// The list name cut to `w` px. "Last, First" is cut part by part, so both parts keep their start.
fn fit_name(name: &str, w: i32, measure: &dyn Fn(&str) -> i32) -> String {
	if measure(name) <= w {
		return name.to_string();
	}
	let Some((a, b)) = name.split_once(", ") else {
		return fit(name, w, measure);
	};
	let each = (w - measure(", ")) / 2;
	format!("{}, {}", fit(a, each, measure), fit(b, each, measure))
}

/// One row of the Address List, laid out as Palm Desktop 3.1.1 does. The number column is as wide as the
/// widest number of the list and ends RIGHT px from the right edge; the name gets the rest. When the list
/// is too narrow, the number column takes at most MAX_SHARE of the room and both columns end in "...". The
/// letter after the number always shows. A selected row is filled from the left edge to the end of the
/// number.
pub(crate) fn draw_row(r: &Row, selected: bool, (x, y, w, h): (i32, i32, i32, i32), widest: i32) {
	draw::set_font(theme::fonts().sans, 11);
	let room = (w - RIGHT).max(0);
	let number_w = widest.min((room as f64 * MAX_SHARE) as i32);
	let number_x = room - number_w;
	draw::draw_rect_fill(x, y, w, h, Color::White);
	let base = y + (h - draw::height()) / 2 + draw::height() - draw::descent();
	let letter_w = if r.letter.is_empty() {
		0
	} else {
		text_w(&format!(" {}", r.letter))
	};
	let number = if r.number.is_empty() {
		String::new()
	} else {
		format!("{} {}", fit(&r.number, number_w - letter_w, &text_w), r.letter)
	};
	if selected {
		let end = if number.is_empty() {
			number_x
		} else {
			number_x + text_w(&number) + 4
		};
		draw::draw_rect_fill(x, y, end, h, SELECT);
	}
	draw::set_draw_color(if selected { Color::White } else { Color::Black });
	// The name stops 6 px short of the number column.
	draw::draw_text(&fit_name(&r.name, number_x - 9, &text_w), x + 3, base);
	draw::draw_text(&number, x + number_x, base);
}

/// The card of an address: the name and the category on the first line, then the filled fields.
fn draw_card(c: &Card, x: i32, y: i32, w: i32) {
	let fonts = theme::fonts();
	let a = &c.address;
	let pad = 6;
	let mut name = [a.first_name.as_str(), a.last_name.as_str()]
		.into_iter()
		.filter(|s| !is_blank(s))
		.collect::<Vec<_>>()
		.join(" ");
	if is_blank(&name) {
		name = a.company.clone();
	}
	draw::set_draw_color(Color::Black);
	draw::set_font(fonts.sans_bold, 16);
	let name_descent = draw::descent();
	let mut base = y + pad + draw::height() - name_descent;
	draw::draw_text(&name, x + pad, base);
	// The category sits at the right end of the name line.
	draw::set_font(fonts.sans, 13);
	draw::draw_text(&c.category, x + w - pad - text_w(&c.category), base);
	base += name_descent + 4 + draw::height() - draw::descent();
	for line in card_lines(a, &c.labels) {
		draw::draw_text(&line, x + pad, base);
		base += draw::height();
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn labels() -> [String; 4] {
		["Birthday", "Custom 2", "Custom 3", "Custom 4"].map(String::from)
	}

	#[test]
	fn card_lines_skip_empty_fields_and_join_the_city_line() {
		let a = Address {
			first_name: "Ann".into(),
			company: "Acme".into(),
			phones: vec![
				"555-0100".into(),
				"".into(),
				" ".into(),
				"".into(),
				"ann@acme.com".into(),
			],
			address: "1 Main St\nSuite 2".into(),
			city: "Springfield".into(),
			zip: "12345".into(),
			custom: vec!["May 1".into(), "".into(), "".into(), "".into()],
			note: "Gate code 9".into(),
			..Address::default()
		};
		assert_eq!(
			card_lines(&a, &labels()),
			[
				"Acme",
				"Work:  555-0100",
				"E-mail:  ann@acme.com",
				"1 Main St",
				"Suite 2",
				"Springfield 12345",
				"Birthday: May 1",
				"Gate code 9",
			]
		);
		let b = Address {
			city: "Austin".into(),
			state: "TX".into(),
			..Address::default()
		};
		assert_eq!(card_lines(&b, &labels()), ["Austin, TX"]);
	}

	#[test]
	fn dial_skips_email_and_blank_phones() {
		let a = Address {
			phones: vec!["1".into(), "".into(), "3".into(), "4".into(), "a@b.c".into()],
			..Address::default()
		};
		assert_eq!(phone_slots(&a), [0, 2, 3]);
		assert_eq!(tel_digits("+1 (555) 010-0199 x2"), "+155501001992");
	}

	#[test]
	fn fit_name_cuts_a_list_name_part_by_part() {
		// One pixel per character.
		let m = |s: &str| s.chars().count() as i32;
		assert_eq!(fit_name("Fitzgerald, Alexandra", 21, &m), "Fitzgerald, Alexandra");
		assert_eq!(fit_name("Fitzgerald, Alexandra", 20, &m), "Fitzge..., Alexandra");
		assert_eq!(fit_name("Fitzgerald, Alexandra", 16, &m), "Fitz..., Alex...");
		assert_eq!(fit_name("Acme Corporation", 10, &m), "Acme Co...");
	}
}
