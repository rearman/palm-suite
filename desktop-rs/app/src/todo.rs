//! The To Do List view, as TodoPanel.kt and TodoMenus.kt: the table of items with its category filter and
//! the Show and New buttons on the left, and the details of the selected item on the right. The rules at the
//! end (shown, order, due label, completion) are the ones the To Do part of the Day view follows too.

use crate::category::{menu_label, IdChoice, ALL, EDIT_CATEGORIES, UNFILED};
use crate::date_picker::{self, Mode};
use crate::dialogs::{confirm_delete, delete_question, key_label, run_modal};
use crate::draw::{fit, text_w, Dev};
use crate::msg::{Msg, TodoMsg};
use crate::note_dialog;
use crate::platform::today;
use crate::records::{self, list_state, ListAction, ListMsgs};
use crate::settings::Settings;
use crate::split::split;
use crate::store::DesktopStore;
use crate::text_edit::{EditState, Field};
use crate::theme::{self, icon, SELECT};
use crate::view::Ctx;
use fltk::app::{self, Sender};
use fltk::button::{Button, CheckButton, RoundButton};
use fltk::draw;
use fltk::enums::{Align, Color, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::image::PngImage;
use fltk::menu::{Choice, MenuButton, MenuButtonType};
use fltk::output::Output;
use fltk::prelude::*;
use fltk::table::{TableContext, TableRow, TableRowSelectFlag, TableRowSelectMode};
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use fltk::window::Window;
use palm_core::days::{dow, ymd};
use palm_core::model::{Todo, DOW_LONG};
use palm_core::runner::Record;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

const ROW_H: i32 = 16;

/// The Due menu choices of a To Do, in menu order.
pub const DUE_NAMES: [&str; 5] = ["Today", "Tomorrow", "One week later", "No date", "Choose date..."];

const SORT_NAMES: [&str; 4] = [
	"Priority, Due Date",
	"Due Date, Priority",
	"Category, Priority",
	"Category, Due Date",
];

/// The kinds of the table columns.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Col {
	Done,
	Priority,
	Title,
	Due,
	Category,
	/// The note and private icons.
	Marks,
}

impl Col {
	/// The fixed width; the To Do text takes the rest.
	fn width(self) -> i32 {
		match self {
			Col::Done => 20,
			// With the 4 px pad, 10 px from the priority to the To Do text, as in Palm Desktop.
			Col::Priority => 18,
			Col::Due => 45,
			Col::Category => 50,
			Col::Marks => 34,
			Col::Title => 0,
		}
	}
}

/// The columns the Show Options ask for.
fn columns(settings: &Settings) -> Vec<Col> {
	let mut c = vec![Col::Done];
	if settings.get_bool("todoShowPri", false) {
		c.push(Col::Priority);
	}
	c.push(Col::Title);
	if settings.get_bool("todoShowDue", true) {
		c.push(Col::Due);
	}
	if settings.get_bool("todoShowCat", true) {
		c.push(Col::Category);
	}
	c.push(Col::Marks);
	c
}

/// One row of the table as it draws.
struct Row {
	todo: Todo,
	category: String,
	due: String,
	overdue: bool,
}

pub struct TodoView {
	/// The split that holds the view; the view group makes it its resizable widget.
	pub root: Group,
	filter: IdChoice,
	table: TableRow,
	rows: Rc<RefCell<Vec<Row>>>,
	cols: Rc<RefCell<Vec<Col>>>,
	lead: Rc<Cell<i32>>,
	text: TextEditor,
	text_buf: TextBuffer,
	radios: Vec<RoundButton>,
	due_box: Choice,
	due_day: Output,
	category: IdChoice,
	note_field: Output,
	note_b: Button,
	private_box: CheckButton,
	complete_box: CheckButton,
	apply: Button,
	/// The due date that the Due box shows, saved by Apply; shared with the box's drawing.
	pending_due: Rc<Cell<i64>>,
	/// The note chosen in the details, saved by Apply.
	pending_note: String,
	/// The items the table shows, in table order.
	shown: Vec<Todo>,
	/// The item selected in the table.
	selected_id: Option<i64>,
	/// The item whose values are in the details, and a copy of it as they show it.
	loaded_id: Option<i64>,
	loaded: Option<Todo>,
}

impl TodoView {
	/// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group.
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>, settings: &Settings) -> TodoView {
		let fonts = theme::fonts();
		let sp = split(x, y, w, h, settings.get_int("todoSplit", 315), "todoSplit", s);

		// The list side: the title, the filter, the table, and the Show and New buttons.
		sp.left.begin();
		let (lx, ly, lw, lh) = (sp.left.x(), sp.left.y(), sp.left.w(), sp.left.h());
		let mut title = Frame::new(lx + 4, ly + 4, 112, 22, "To Do List");
		title.set_frame(FrameType::DownBox);
		title.set_label_font(fonts.arial_bold);
		title.set_label_size(14);
		title.set_align(Align::Center | Align::Inside);
		let filter = IdChoice::new(
			Choice::new(lx + lw - 114, ly + 4, 110, 22, None),
			Some(0),
			s,
			Some(|| Msg::Todo(TodoMsg::Filter)),
		);
		let rows: Rc<RefCell<Vec<Row>>> = Rc::new(RefCell::new(Vec::new()));
		let cols = Rc::new(RefCell::new(columns(settings)));
		let lead = Rc::new(Cell::new(-1));
		let mut table = TableRow::new(lx + 4, ly + 30, lw - 8, lh - 30 - 34, None);
		table.set_type(TableRowSelectMode::Multi);
		table.set_rows(0);
		table.set_cols(cols.borrow().len() as i32);
		table.set_row_height_all(ROW_H);
		table.set_col_header(false);
		table.set_row_header(false);
		table.set_frame(FrameType::DownBox);
		table.set_color(Color::White);
		table.end();
		let (r, c) = (rows.clone(), cols.clone());
		let (note, key) = (icon("note"), icon("private"));
		table.draw_cell(move |t, ctx, row, col, cx, cy, cw, ch| match ctx {
			TableContext::StartPage => fit_columns(t, &c.borrow()),
			TableContext::Cell => {
				let rows = r.borrow();
				let (Some(line), Some(kind)) = (rows.get(row as usize), c.borrow().get(col as usize).copied()) else {
					return;
				};
				let _clip = crate::draw::Clip::cell(t, cx, cy, cw, ch);
				draw_cell(line, kind, t.row_selected(row), (cx, cy, cw, ch), (&note, &key));
			}
			_ => {}
		});
		let l = lead.clone();
		table.handle(move |t, ev| records::list_event(t, ev, &l, s, LIST_MSGS));
		let mut show_b = Button::new(lx + lw - 166, ly + lh - 29, 75, 23, "Sh&ow...");
		show_b.set_callback(move |_| s.send(Msg::Todo(TodoMsg::Show)));
		let mut new_b = Button::new(lx + lw - 85, ly + lh - 29, 75, 23, "&New");
		new_b.set_callback(move |_| s.send(Msg::Todo(TodoMsg::New)));
		let grow = Frame::new(lx + 120, ly + 30, lw - 290, lh - 64, None);
		sp.left.resizable(&grow);
		sp.left.end();

		// The details: a label column at the left, the fields at the right.
		sp.right.begin();
		let (rx, ry, rw, rh) = (sp.right.x(), sp.right.y(), sp.right.w(), sp.right.h());
		let (fx, fw) = (rx + 66, rw - 70);
		let mut y = ry + 3;
		let label = |text: &str, h: i32, target: Option<fltk::widget::Widget>, y: i32| {
			let mut b = key_label(text, rx + 4, y, 58, h.min(22), Align::Right);
			if let Some(t) = target {
				b.set_callback(move |_| drop(t.clone().take_focus()));
			}
		};

		let text_buf = TextBuffer::default();
		let mut text = TextEditor::new(fx, y, fw, 48, None);
		text.set_buffer(text_buf.clone());
		text.wrap_mode(WrapMode::AtBounds, 0);
		text.set_scrollbar_align(Align::Right);
		Field::Editor(text.clone()).attach_menu();
		label("&To Do:", 48, Some(text.as_base_widget()), y);
		y += 48 + 6;

		let mut prio = Group::new(fx, y, fw, 23, None);
		prio.set_frame(FrameType::DownBox);
		let mut radios = Vec::new();
		for n in 0..5 {
			let cell = (fw - 4) / 5;
			let mut r = RoundButton::new(fx + 2 + n * cell + cell / 2 - 14, y + 1, 30, 21, None);
			r.set_label(&format!("&{}", n + 1));
			r.set_type(fltk::button::ButtonType::Radio);
			radios.push(r);
		}
		prio.end();
		label("P&riority:", 23, Some(radios[0].as_base_widget()), y);
		y += 23 + 6;

		let pending_due = Rc::new(Cell::new(-1i64));
		let mut due_box = Choice::new(fx, y, 130, 22, None);
		for n in DUE_NAMES {
			due_box.add_choice(n);
		}
		due_box.set_value(-1);
		// The closed box shows the due date, not a menu item.
		{
			let due = pending_due.clone();
			due_box.draw(move |b| {
				if due.get() >= 0 {
					draw::set_font(fonts.sans, 11);
					draw::set_draw_color(if b.active() {
						Color::Black
					} else {
						Color::from_rgb(128, 128, 128)
					});
					draw::draw_text2(&date_text(due.get()), b.x() + 4, b.y(), b.w() - 24, b.h(), Align::Left);
				}
			});
		}
		due_box.set_callback(move |_| s.send(Msg::Todo(TodoMsg::DuePick)));
		let mut due_day = Output::new(fx + 134, y, fw - 134, 22, None);
		due_day.set_frame(FrameType::DownBox);
		due_day.set_color(theme::PANEL);
		label("D&ue:", 22, Some(due_box.as_base_widget()), y);
		y += 22 + 6;

		let category = IdChoice::new(Choice::new(fx, y, fw, 22, None), Some(0), s, None);
		label("Cate&gory:", 22, Some(category.choice.as_base_widget()), y);
		y += 22 + 6;

		let mut note_field = Output::new(fx, y + 2, fw - 30, 22, None);
		note_field.set_color(theme::PANEL);
		let mut note_b = Button::new(fx + fw - 26, y, 26, 26, None);
		note_b.set_image(Some(icon("note")));
		note_b.set_deimage(Some(theme::gray_icon("note")));
		note_b.set_callback(move |_| s.send(Msg::Todo(TodoMsg::Note)));
		label("Not&e:", 26, Some(note_b.as_base_widget()), y + 2);
		y += 26 + 6;

		let private_box = CheckButton::new(fx, y, 18, 21, None);
		label("Pri&vate:", 21, Some(private_box.as_base_widget()), y);
		y += 21 + 6;

		let complete_box = CheckButton::new(fx, y + 1, 18, 21, None);
		let mut apply = Button::new(fx + fw - 75, y, 75, 23, "&Apply");
		apply.set_callback(move |_| s.send(Msg::Todo(TodoMsg::Apply)));
		label("Co&mplete:", 23, Some(complete_box.as_base_widget()), y);
		y += 23 + 6;
		// The fields widen with the pane; the buttons at the right keep their size.
		let grow = Frame::new(fx + 134, y, (fw - 134 - 80).max(1), (ry + rh - y).max(1), None);
		sp.right.resizable(&grow);
		sp.right.end();

		let mut v = TodoView {
			root: sp.outer,
			filter,
			table,
			rows,
			cols,
			lead,
			text,
			text_buf,
			radios,
			due_box,
			due_day,
			category,
			note_field,
			note_b,
			private_box,
			complete_box,
			apply,
			pending_due,
			pending_note: String::new(),
			shown: Vec::new(),
			selected_id: None,
			loaded_id: None,
			loaded: None,
		};
		v.clear_details();
		v
	}

	pub fn table(&self) -> &TableRow {
		&self.table
	}

	pub fn editor(&self) -> &TextEditor {
		&self.text
	}

	/// The list takes the focus when the view opens.
	pub fn focus(&mut self) {
		let _ = self.table.take_focus();
	}

	fn selected_rows(&self) -> Vec<usize> {
		records::selected_rows(&self.table, self.shown.len())
	}

	fn selected(&self) -> Vec<Todo> {
		self.selected_rows()
			.into_iter()
			.map(|i| self.shown[i].clone())
			.collect()
	}

	/// The lead row of the selection, the row clicked last, else the first selected row.
	fn lead_row(&self) -> Option<usize> {
		let mut t = self.table.clone();
		let l = self.lead.get();
		if l >= 0 && (l as usize) < self.shown.len() && t.row_selected(l) {
			return Some(l as usize);
		}
		self.selected_rows().first().copied()
	}

	/// "3 of 12" for the status bar.
	pub fn position(&self) -> String {
		match self.lead_row() {
			Some(i) => format!("{} of {}", i + 1, self.shown.len()),
			None => String::new(),
		}
	}

	pub fn list_edit_state(&self) -> EditState {
		list_state(!self.selected_rows().is_empty(), !self.shown.is_empty())
	}

	/// Reads the store and the settings again: the drop-down lists, then the table.
	pub fn refresh(&mut self, ctx: &mut Ctx) {
		let cur = ctx.settings.get_int("todoCategory", ALL);
		let mut filters = vec![("All".to_string(), ALL)];
		filters.extend(ctx.store.categories.iter().map(|c| (c.name.clone(), c.id)));
		filters.push(("Unfiled".to_string(), UNFILED));
		if !filters.iter().any(|(_, id)| *id == cur) {
			ctx.set("todoCategory", &ALL.to_string());
		}
		self.filter.set_items(&filters, true, cur);
		let keep = self.category.id();
		let mut items: Vec<(String, i32)> = ctx.store.categories.iter().map(|c| (c.name.clone(), c.id)).collect();
		items.push(("Unfiled".to_string(), UNFILED));
		self.category.set_items(&items, true, keep);
		self.rebuild(ctx);
	}

	/// Rebuilds the columns and the table, and keeps the selection by id.
	fn rebuild(&mut self, ctx: &Ctx) {
		let today = today();
		self.shown = visible(ctx, today);
		let keep = self.selected_id;
		let idx = keep.and_then(|id| self.shown.iter().position(|t| t.id == id));
		*self.rows.borrow_mut() = self
			.shown
			.iter()
			.map(|t| Row {
				todo: t.clone(),
				category: ctx.store.category_name(t.category),
				due: due_label(t, today),
				overdue: is_overdue(t, today),
			})
			.collect();
		let cols = columns(ctx.settings);
		if *self.cols.borrow() != cols {
			self.table.set_cols(cols.len() as i32);
			*self.cols.borrow_mut() = cols;
		}
		self.table.set_rows(self.shown.len() as i32);
		self.table.set_row_height_all(ROW_H);
		records::pick_row(&mut self.table, &self.lead, idx);
		match idx {
			None => self.clear_details(),
			Some(i) if self.loaded_id != keep => self.load_details(&self.shown[i].clone()),
			// A sync changed the item: show the new copy. Edits that are not applied yet stay, and Apply saves them.
			Some(i) if self.loaded.as_ref() != Some(&self.shown[i]) && !self.has_edits() => {
				self.load_details(&self.shown[i].clone())
			}
			_ => {}
		}
	}

	fn set_controls(&mut self, on: bool) {
		let mut ws: Vec<fltk::widget::Widget> = vec![
			self.text.as_base_widget(),
			self.due_box.as_base_widget(),
			self.category.choice.as_base_widget(),
			self.note_b.as_base_widget(),
			self.private_box.as_base_widget(),
			self.complete_box.as_base_widget(),
			self.apply.as_base_widget(),
		];
		ws.extend(self.radios.iter().map(|r| r.as_base_widget()));
		for mut w in ws {
			if on {
				w.activate();
			} else {
				w.deactivate();
			}
		}
	}

	fn load_details(&mut self, t: &Todo) {
		self.selected_id = Some(t.id);
		self.loaded_id = Some(t.id);
		self.loaded = Some(t.clone());
		self.text_buf.set_text(&t.title);
		self.text.set_insert_position(0);
		let p = t.priority.clamp(1, 5) as usize;
		for (i, r) in self.radios.iter_mut().enumerate() {
			r.set_value(i + 1 == p);
		}
		self.pending_due.set(t.due);
		self.show_due();
		self.category.select(t.category);
		self.pending_note = t.note.clone();
		self.note_field.set_value(first_line(&t.note));
		self.private_box.set_value(t.is_private);
		self.complete_box.set_value(t.done);
		self.set_controls(true);
	}

	fn clear_details(&mut self) {
		self.selected_id = None;
		self.loaded_id = None;
		self.loaded = None;
		self.text_buf.set_text("");
		for r in self.radios.iter_mut() {
			r.set_value(false);
		}
		self.pending_due.set(-1);
		self.show_due();
		self.category.select(UNFILED);
		self.pending_note.clear();
		self.note_field.set_value("");
		self.private_box.set_value(false);
		self.complete_box.set_value(false);
		self.set_controls(false);
	}

	/// Shows the due date in the Due box and the day name in the field next to it.
	fn show_due(&mut self) {
		let due = self.pending_due.get();
		self.due_box.set_value(-1);
		self.due_box.redraw();
		self.due_day
			.set_value(if due < 0 { "" } else { DOW_LONG[dow(due) as usize] });
	}

	fn priority(&self) -> i32 {
		self.radios.iter().position(|r| r.value()).map_or(0, |i| i as i32 + 1)
	}

	/// True when a field of the details differs from the item as it was loaded (an edit not applied yet).
	fn has_edits(&self) -> bool {
		let Some(t) = &self.loaded else { return false };
		self.text_buf.text() != t.title
			|| self.priority() != t.priority
			|| self.pending_due.get() != t.due
			|| self.category.id() != t.category
			|| self.pending_note != t.note
			|| self.private_box.value() != t.is_private
			|| self.complete_box.value() != t.done
	}

	/// A new item with `title`, in the category the filter shows, as the New button makes it.
	fn new_todo(settings: &Settings, title: &str) -> Todo {
		Todo {
			title: title.to_string(),
			priority: 1,
			category: settings.get_int("todoCategory", ALL).max(UNFILED),
			..Todo::default()
		}
	}

	/// Shows the item `id` in the table, changing the filters when it is hidden, and selects it.
	pub fn select(&mut self, ctx: &mut Ctx, id: i64) {
		let Some(t) = ctx.store.todos.iter().find(|t| t.id == id).cloned() else {
			return;
		};
		reveal(&t, ctx.settings, today());
		self.selected_id = Some(id);
		self.loaded_id = None;
		self.refresh(ctx);
		let _ = self.table.take_focus();
	}

	/// The title and the lines that Print prints: each row of the table.
	pub fn print_lines(&self, ctx: &Ctx) -> (String, Vec<String>) {
		let lines = self
			.shown
			.iter()
			.map(|t| {
				let due = if t.due < 0 {
					"-".to_string()
				} else {
					palm_core::model::short_date(t.due)
				};
				format!(
					"{} {} {} {due} {}",
					if t.done { "[x]" } else { "[ ]" },
					t.priority,
					t.title.replace('\n', " "),
					ctx.store.category_name(t.category)
				)
			})
			.collect();
		("To Do List".to_string(), lines)
	}

	pub fn new_record(&mut self, ctx: &mut Ctx) {
		let mut t = Self::new_todo(ctx.settings, "");
		ctx.store.save_todo(&mut t);
		reveal(&t, ctx.settings, today());
		self.selected_id = Some(t.id);
		self.loaded_id = None;
		self.refresh(ctx);
		let _ = self.text.take_focus();
	}

	fn delete_selected(&mut self, ctx: &mut Ctx) {
		let list = self.selected();
		if list.is_empty()
			|| !confirm_delete(
				ctx.settings,
				ctx.no_confirm,
				&delete_question(list.len(), "Delete the To Do item?", "To Do items"),
			) {
			return;
		}
		self.selected_id = None;
		for t in list {
			ctx.store.delete_todo(t.id);
		}
	}

	/// Saves the details to the selected item.
	fn apply_fields(&mut self, ctx: &mut Ctx) {
		let Some(mut t) = ctx.store.todos.iter().find(|t| Some(t.id) == self.selected_id).cloned() else {
			return;
		};
		t.title = self.text_buf.text().trim().to_string();
		t.priority = self.priority().max(1);
		t.due = self.pending_due.get();
		t.category = self.category.id();
		t.note = self.pending_note.clone();
		t.is_private = self.private_box.value();
		mark_done(&mut t, self.complete_box.value(), ctx.settings, today());
		ctx.store.save_todo(&mut t);
		// Reload the details from the saved item.
		self.loaded_id = None;
	}

	/// Saves an item changed from a cell, and reloads the details when they show it.
	fn save_from_cell(&mut self, ctx: &mut Ctx, mut t: Todo) {
		ctx.store.save_todo(&mut t);
		if self.selected_id == Some(t.id) {
			self.loaded_id = None;
		}
	}

	pub fn handle(&mut self, msg: TodoMsg, ctx: &mut Ctx) {
		match msg {
			TodoMsg::Picked => {
				let Some(i) = self.lead_row() else { return };
				if self.loaded_id != Some(self.shown[i].id) {
					self.load_details(&self.shown[i].clone());
				}
			}
			TodoMsg::Filter => {
				ctx.set("todoCategory", &self.filter.id().to_string());
				self.rebuild(ctx);
			}
			TodoMsg::TypeNew(c) => {
				self.new_record(ctx);
				self.text_buf.set_text(&c.to_string());
				self.text.set_insert_position(self.text_buf.length());
			}
			TodoMsg::New => self.new_record(ctx),
			TodoMsg::Apply => self.apply_fields(ctx),
			TodoMsg::Show => {
				if show_options(ctx.settings) {
					self.refresh(ctx);
				}
			}
			TodoMsg::DuePick => {
				let pick = self.due_box.value();
				self.due_box.set_value(-1);
				if pick >= 0 {
					if let Some(d) = due_for(pick as usize, self.pending_due.get(), today(), ctx.settings) {
						self.pending_due.set(d);
					}
				}
				self.show_due();
			}
			TodoMsg::Note => {
				if let Some(text) = note_dialog::edit("To Do Note", &self.pending_note) {
					self.note_field.set_value(first_line(&text));
					self.pending_note = text;
				}
			}
			TodoMsg::Cell(row, col, double) => self.cell_click(ctx, row, col, double),
			TodoMsg::ListMenu => {
				if let Some(a) = records::list_menu(self.list_edit_state(), &[]) {
					self.list_action(a, ctx);
				}
			}
			TodoMsg::PasteText(text) => self.paste_text(ctx, &text),
		}
	}

	/// A click on a cell: the check box marks the item done or not done (each click of a double click too, as
	/// in Palm Desktop), and a single click on the priority, the due date or the category opens its menu.
	fn cell_click(&mut self, ctx: &mut Ctx, row: i32, col: i32, double: bool) {
		let Some(mut t) = self.shown.get(row as usize).cloned() else {
			return;
		};
		let Some(kind) = self.cols.borrow().get(col as usize).copied() else {
			return;
		};
		if double && kind != Col::Done {
			return;
		}
		match kind {
			Col::Done => {
				let done = !t.done;
				mark_done(&mut t, done, ctx.settings, today());
				self.save_from_cell(ctx, t);
			}
			Col::Priority => {
				let labels = ["1", "2", "3", "4", "5"];
				if let Some(i) = popup(&labels) {
					t.priority = i as i32 + 1;
					self.save_from_cell(ctx, t);
				}
			}
			Col::Due => {
				if let Some(pick) = popup(&DUE_NAMES) {
					if let Some(d) = due_for(pick, t.due, today(), ctx.settings) {
						t.due = d;
						self.save_from_cell(ctx, t);
					}
				}
			}
			Col::Category => {
				let mut items: Vec<(String, i32)> =
					ctx.store.categories.iter().map(|c| (c.name.clone(), c.id)).collect();
				items.push(("Unfiled".to_string(), UNFILED));
				let mut labels: Vec<String> = items.iter().map(|(n, _)| menu_label(n)).collect();
				labels.push(EDIT_CATEGORIES.to_string());
				let refs: Vec<&str> = labels.iter().map(String::as_str).collect();
				match popup(&refs) {
					Some(i) if i < items.len() => {
						t.category = items[i].1;
						self.save_from_cell(ctx, t);
					}
					Some(_) => ctx.s.send(Msg::EditCategories(0)),
					None => {}
				}
			}
			Col::Title | Col::Marks => {}
		}
	}

	/// Cut, Copy, Paste, Delete and Select All of the selected items. The text of an item is its title.
	pub fn list_action(&mut self, a: ListAction, ctx: &mut Ctx) {
		match a {
			ListAction::Copy | ListAction::Cut => {
				let list = self.selected();
				if list.is_empty() {
					return;
				}
				let text = list.iter().map(|t| t.title.as_str()).collect::<Vec<_>>().join("\n");
				records::put("todo", list.iter().cloned().map(Record::Todo).collect(), text);
				if a == ListAction::Cut {
					self.selected_id = None;
					for t in list {
						ctx.store.delete_todo(t.id);
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

	/// A paste into the list: copies of the kept items when the text is theirs, else one new item for each
	/// line that is not blank.
	fn paste_text(&mut self, ctx: &mut Ctx, text: &str) {
		match records::records_for("todo", text) {
			Some(recs) => {
				for r in recs {
					if let Record::Todo(t) = r {
						ctx.store.save_todo(&mut Todo {
							id: 0,
							uid: String::new(),
							..t
						});
					}
				}
			}
			None => {
				for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
					ctx.store.save_todo(&mut Self::new_todo(ctx.settings, line));
				}
			}
		}
	}
}

/// The messages of the To Do table. A printable key starts a new item that holds it.
const LIST_MSGS: ListMsgs = ListMsgs {
	picked: || Msg::Todo(TodoMsg::Picked),
	menu: || Msg::Todo(TodoMsg::ListMenu),
	paste: |t| Msg::Todo(TodoMsg::PasteText(t)),
	typed: Some(|c| Msg::Todo(TodoMsg::TypeNew(c))),
	open: None,
	cell: Some(|r, c, double| Msg::Todo(TodoMsg::Cell(r, c, double))),
};

/// Shows a menu of `labels` at the pointer; the index of the pick.
fn popup(labels: &[&str]) -> Option<usize> {
	let mut mb = MenuButton::default();
	mb.set_type(MenuButtonType::Popup3);
	for l in labels {
		mb.add_choice(l);
	}
	let pick = mb.popup().map(|_| mb.value()).filter(|v| *v >= 0).map(|v| v as usize);
	MenuButton::delete(mb);
	pick
}

/// Fits the fixed columns and gives the To Do text the rest of the width inside the edge and the scroll bar.
fn fit_columns(t: &mut TableRow, cols: &[Col]) {
	let room = t.w() - 4 - if t.rows() * ROW_H > t.h() - 4 { 17 } else { 0 };
	let fixed: i32 = cols.iter().map(|c| c.width()).sum();
	for (i, c) in cols.iter().enumerate() {
		let w = if *c == Col::Title {
			(room - fixed).max(20)
		} else {
			c.width()
		};
		if t.col_width(i as i32) != w {
			t.set_col_width(i as i32, w);
		}
	}
}

/// One cell of the table. A selected row is filled; an overdue item is red, yellow when selected, as in
/// Palm Desktop.
fn draw_cell(r: &Row, kind: Col, selected: bool, (x, y, w, h): (i32, i32, i32, i32), icons: (&PngImage, &PngImage)) {
	let fonts = theme::fonts();
	draw::draw_rect_fill(x, y, w, h, if selected { SELECT } else { Color::White });
	let color = match (selected, r.overdue) {
		(true, true) => Color::Yellow,
		(true, false) => Color::White,
		(false, true) => Color::Red,
		(false, false) => Color::Black,
	};
	draw::set_font(fonts.sans, 11);
	draw::set_draw_color(color);
	let base = y + (h - draw::height()) / 2 + draw::height() - draw::descent();
	match kind {
		Col::Done => draw_check(x + (w - 13) / 2, y + (h - 13) / 2, r.todo.done),
		Col::Priority => draw::draw_text(&r.todo.priority.to_string(), x + 5, base),
		Col::Title => draw::draw_text(&fit(&r.todo.title.replace('\n', " "), w - 4, &text_w), x + 2, base),
		Col::Due => {
			// The overdue "!" is bold, as Palm Desktop draws it.
			let plain = r.due.trim_end_matches('!');
			draw::draw_text(plain, x + 2, base);
			if plain.len() < r.due.len() {
				let at = x + 2 + text_w(plain);
				draw::set_font(fonts.sans_bold, 11);
				draw::draw_text("!", at, base);
			}
		}
		Col::Category => draw::draw_text(&fit(&r.category, w - 4, &text_w), x + 2, base),
		Col::Marks => {
			let mut ix = x + 2;
			for (on, img) in [(!r.todo.note.is_empty(), icons.0), (r.todo.is_private, icons.1)] {
				if on {
					let mut i = img.clone();
					i.draw(ix, y + (h - i.h()) / 2, i.w(), i.h());
					ix += i.w() + 2;
				}
			}
		}
	}
}

/// The plain black To Do check box of Palm Desktop, 13 px, at (x, y): a white square in a black edge, and
/// the check mark when `done`. Drawn in device pixels.
pub(crate) fn draw_check(x: i32, y: i32, done: bool) {
	let d = Dev::begin();
	let (bx, by, bw, bh) = d.rect(x + 1, y + 1, 11, 11);
	draw::draw_rect_fill(bx, by, bw, bh, Color::White);
	let k = d.lw();
	draw::set_draw_color(Color::Black);
	for (rx, ry, rw, rh) in [
		(bx, by, bw, k),
		(bx, by + bh - k, bw, k),
		(bx, by, k, bh),
		(bx + bw - k, by, k, bh),
	] {
		draw::draw_rectf(rx, ry, rw, rh);
	}
	if done {
		draw::set_line_style(draw::LineStyle::Solid, k);
		for (x1, y1, x2, y2) in [(3, 6, 5, 8), (5, 8, 9, 4), (3, 7, 5, 9), (5, 9, 9, 5)] {
			draw::draw_line(d.p(x + x1), d.p(y + y1), d.p(x + x2), d.p(y + y2));
		}
		draw::set_line_style(draw::LineStyle::Solid, 0);
	}
}

fn first_line(s: &str) -> &str {
	s.lines().next().unwrap_or("")
}

/// The text of the Due box: M/d/yyyy.
fn date_text(due: i64) -> String {
	let d = ymd(due);
	format!("{}/{}/{}", d.month, d.day, d.year)
}

/// The items the table shows: the filter's category, the items the Show Options let show, in their order.
fn visible(ctx: &Ctx, today: i64) -> Vec<Todo> {
	let cat = ctx.settings.get_int("todoCategory", ALL);
	let list: Vec<Todo> = ctx
		.store
		.todos
		.iter()
		.filter(|t| (cat == ALL || t.category == cat) && todo_shown(t, ctx.settings, ctx.show_private, today))
		.cloned()
		.collect();
	todo_order(list, ctx.settings, ctx.store)
}

/// Makes `t` show in the table by changing the filters when needed.
fn reveal(t: &Todo, settings: &mut Settings, today: i64) {
	let cat = settings.get_int("todoCategory", ALL);
	if cat != ALL && t.category != cat {
		let _ = settings.set_int("todoCategory", ALL);
	}
	if t.done && !settings.get_bool("todoShowDone", false) {
		let _ = settings.set_bool("todoShowDone", true);
	}
	if settings.get_bool("todoOnlyDue", false) && !is_due_by_today(t, today) {
		let _ = settings.set_bool("todoOnlyDue", false);
	}
}

/// The due date that Due menu item `pick` gives: today, tomorrow, a week later or no date (-1). Item 4 asks a
/// day in the date dialog, which opens on `current` or else today; None when it is canceled.
pub fn due_for(pick: usize, current: i64, today: i64, settings: &Settings) -> Option<i64> {
	match pick {
		0 => Some(today),
		1 => Some(today + 1),
		2 => Some(today + 7),
		3 => Some(-1),
		_ => {
			let begins = settings.get_int("weekStart", 0).clamp(0, 1) as u32;
			date_picker::pick(Mode::Day, if current >= 0 { current } else { today }, today, begins)
		}
	}
}

/// True when `t` is not done and its due date is before today.
pub fn is_overdue(t: &Todo, today: i64) -> bool {
	!t.done && t.due >= 0 && t.due < today
}

/// True when `t` has no due date, or a due date of today or earlier.
pub fn is_due_by_today(t: &Todo, today: i64) -> bool {
	t.due < 0 || t.due <= today
}

/// Sets the done flag. A new completion sets the due date to today when "todoRecordDone" is on.
pub fn mark_done(t: &mut Todo, done: bool, settings: &Settings, today: i64) {
	if done && !t.done && settings.get_bool("todoRecordDone", false) {
		t.due = today;
	}
	t.done = done;
}

/// True when the private setting and the Show Options (completed items, only due items) let `t` show.
pub fn todo_shown(t: &Todo, settings: &Settings, show_private: bool, today: i64) -> bool {
	(show_private || !t.is_private)
		&& (settings.get_bool("todoShowDone", false) || !t.done)
		&& (!settings.get_bool("todoOnlyDue", false) || is_due_by_today(t, today))
}

/// The Sort by index of the Show Options (0 Priority, Due Date; 1 Due Date, Priority; 2 Category,
/// Priority; 3 Category, Due Date). Reads the old "todoSort" index when "todoSortBy" is not set.
pub fn sort_index(settings: &Settings) -> usize {
	let saved = settings.get_int("todoSortBy", -1);
	if saved >= 0 {
		return saved.min(3) as usize;
	}
	// The old order put Due Date, Priority first: its first two entries swap places.
	match settings.get_int("todoSort", 0) {
		0 => 1,
		1 => 0,
		old => old.clamp(2, 3) as usize,
	}
}

/// `list` in the order of the Show Options. The sort is stable, so equal items keep their store order.
pub fn todo_order(mut list: Vec<Todo>, settings: &Settings, store: &DesktopStore) -> Vec<Todo> {
	let due = |t: &Todo| if t.due < 0 { i64::MAX } else { t.due };
	let cat = |t: &Todo| store.category_name(t.category).to_lowercase();
	match sort_index(settings) {
		1 => list.sort_by(|a, b| due(a).cmp(&due(b)).then(a.priority.cmp(&b.priority))),
		2 => list.sort_by(|a, b| cat(a).cmp(&cat(b)).then(a.priority.cmp(&b.priority))),
		3 => list.sort_by(|a, b| cat(a).cmp(&cat(b)).then(due(a).cmp(&due(b)))),
		_ => list.sort_by(|a, b| a.priority.cmp(&b.priority).then(due(a).cmp(&due(b)))),
	}
	list
}

/// The due text of a To Do: M/d, with "!" when overdue, or "-" when no date.
pub fn due_label(t: &Todo, today: i64) -> String {
	if t.due < 0 {
		return "-".to_string();
	}
	let d = ymd(t.due);
	format!("{}/{}{}", d.month, d.day, if is_overdue(t, today) { "!" } else { "" })
}

/// The Show Options dialog: the sort order, the view filters and the columns of the list. Saves them and
/// returns true on OK.
fn show_options(settings: &mut Settings) -> bool {
	let mut win = Window::default().with_size(300, 230).with_label("Show Options");
	key_label("&Sort by", 12, 12, 44, 22, Align::Left);
	let mut sort = Choice::new(58, 12, 130, 22, None);
	for n in SORT_NAMES {
		sort.add_choice(n);
	}
	sort.set_value(sort_index(settings) as i32);
	let mut view = Group::new(12, 54, 176, 164, "View Options");
	view.set_frame(FrameType::EngravedBox);
	view.set_align(Align::TopLeft);
	let checks: Vec<(&str, &str, bool)> = vec![
		("Show &completed items", "todoShowDone", false),
		("Show only due &items", "todoOnlyDue", false),
		("&Record completion date", "todoRecordDone", false),
		("Show &due dates", "todoShowDue", true),
		("Show &priorities", "todoShowPri", false),
		("Show cate&gories", "todoShowCat", true),
	];
	let mut boxes = Vec::new();
	for (i, (label, key, default)) in checks.iter().enumerate() {
		let y = 62 + 23 * i as i32 + if i >= 3 { 10 } else { 0 };
		let mut b = CheckButton::new(20, y, 164, 21, None);
		b.set_label(label);
		b.set_value(settings.get_bool(key, *default));
		boxes.push((*key, b));
	}
	view.end();
	let saved = Rc::new(Cell::new(false));
	let mut ok = Button::new(206, 12, 82, 23, "OK");
	let mut cancel = Button::new(206, 41, 82, 23, "Cancel");
	ok.set_shortcut(fltk::enums::Shortcut::None | fltk::enums::Key::Enter);
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
	let _ = ok.take_focus();
	run_modal(&mut win);
	if !saved.get() {
		return false;
	}
	let _ = settings.set_int("todoSortBy", sort.value().max(0));
	for (key, b) in &boxes {
		let _ = settings.set_bool(key, b.value());
	}
	true
}

#[cfg(test)]
mod tests {
	use super::*;

	fn settings_with(pairs: &[(&str, &str)]) -> (Settings, std::path::PathBuf) {
		let dir = std::env::temp_dir().join(format!("palm-todo-{}", crate::store::new_uid()));
		std::fs::create_dir_all(&dir).unwrap();
		let mut s = Settings::open(&dir);
		for (k, v) in pairs {
			s.set(k, v).unwrap();
		}
		(s, dir)
	}

	fn todo(id: i64, priority: i32, due: i64) -> Todo {
		Todo {
			id,
			priority,
			due,
			..Todo::default()
		}
	}

	#[test]
	fn the_sort_index_reads_the_old_setting_with_its_first_two_swapped() {
		let cases = [
			(vec![("todoSortBy", "2")], 2),
			(vec![("todoSortBy", "9")], 3),
			(vec![], 1),
			(vec![("todoSort", "1")], 0),
			(vec![("todoSort", "3")], 3),
		];
		for (pairs, want) in cases {
			let (s, dir) = settings_with(&pairs);
			assert_eq!(sort_index(&s), want, "{pairs:?}");
			std::fs::remove_dir_all(dir).unwrap();
		}
	}

	#[test]
	fn order_puts_items_without_a_date_last_and_keeps_ties_in_store_order() {
		let (_, dir) = settings_with(&[]);
		let store = DesktopStore::open(&dir.join("desktop-data.json")).unwrap();
		let list = vec![
			todo(1, 2, -1),
			todo(2, 1, 50),
			todo(3, 1, -1),
			todo(4, 2, 40),
			todo(5, 1, 50),
		];
		let ids = |v: Vec<Todo>| v.iter().map(|t| t.id).collect::<Vec<_>>();
		let (by_pri, d1) = settings_with(&[("todoSortBy", "0")]);
		assert_eq!(ids(todo_order(list.clone(), &by_pri, &store)), [2, 5, 3, 4, 1]);
		let (by_due, d2) = settings_with(&[("todoSortBy", "1")]);
		assert_eq!(ids(todo_order(list, &by_due, &store)), [4, 2, 5, 3, 1]);
		for d in [d1, d2, dir] {
			std::fs::remove_dir_all(d).unwrap();
		}
	}

	#[test]
	fn due_labels_completion_and_the_show_options() {
		let today = 100;
		assert_eq!(due_label(&todo(1, 1, -1), today), "-");
		// Epoch day 99 is 1970-04-10.
		assert_eq!(due_label(&todo(1, 1, 99), today), "4/10!");
		assert_eq!(due_label(&todo(1, 1, 100), today), "4/11");
		let (record, dir) = settings_with(&[("todoRecordDone", "true"), ("todoOnlyDue", "true")]);
		let mut t = todo(1, 1, 120);
		assert!(!todo_shown(&t, &record, false, today));
		mark_done(&mut t, true, &record, today);
		assert_eq!((t.done, t.due), (true, 100));
		assert!(
			!todo_shown(&t, &record, false, today),
			"completed items are hidden by default"
		);
		let private = Todo {
			is_private: true,
			..todo(2, 1, -1)
		};
		assert!(todo_shown(&private, &record, true, today) && !todo_shown(&private, &record, false, today));
		std::fs::remove_dir_all(dir).unwrap();
		let (none, dir) = settings_with(&[]);
		assert_eq!(due_for(2, -1, today, &none), Some(107));
		assert_eq!(due_for(3, 50, today, &none), Some(-1));
		std::fs::remove_dir_all(dir).unwrap();
	}
}
