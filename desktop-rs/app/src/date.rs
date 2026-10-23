//! The Date view, as DatePanel.kt: the Day view, the right pane (the year, the months and the calendar, then
//! the To Do or Address list) and the Day, Week and Month tabs on the right edge. The Week and Month views
//! (week.rs, month.rs) share the tabs and take the full width.

use crate::address::{self, by_company, draw_row, row_of, widest_number};
use crate::category::{IdChoice, ALL, UNFILED};
use crate::date_picker::{self, add_months, Mode};
use crate::date_shared::{
	apply_alarm_preset, change_occurrence, date_title, delete_occurrence, draw_box, draw_diamond, draw_hour_label,
	draw_outline, edit_alarm, edit_note, event_menu, marker_at, on_handle, on_resize_bar, paint_event, raised_cell,
	title_x, visible_events, week_begins, work_day, BoxAction, Fill, Marker, Occ, Rect, BAR_W, CHANGE_TEXT, HOUR_LINE,
	SEL_H,
};
use crate::draw::{fit, text_w, Clip, Dev, HIGHLIGHT};
use crate::event_dialog;
use crate::month::MonthView;
use crate::msg::{DateMode, DateMsg, FieldEv, Mouse, MouseKind, Msg};
use crate::platform::today;
use crate::settings::Settings;
use crate::text_edit::Field;
use crate::theme::{self, icon, PANEL, SELECT, SHADOW, WORKSPACE};
use crate::todo::{due_label, is_overdue, mark_done, todo_order, todo_shown, DUE_NAMES};
use crate::view::Ctx;
use crate::view::{LEFT, RIGHT};
use crate::week::WeekView;
use fltk::app::{self, MouseWheel, Sender};
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, CallbackTrigger, Color, Cursor, Event, EventState, FrameType, Key};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::input::Input;
use fltk::menu::{Choice, MenuButton, MenuButtonType};
use fltk::prelude::*;
use fltk::table::{TableContext, TableRow, TableRowSelectFlag, TableRowSelectMode};
use fltk::valuator::{Scrollbar, ScrollbarType};
use palm_core::days::{epoch_day, ymd};
use palm_core::model::{Address, Event as Ev, Todo, DOW_LETTER, MONTH_SHORT};
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// The width of the time column.
pub const TIME_W: i32 = 52;
/// The height of one untimed row: a header of one row is 18 px with the 4 px selection bar, as in 3.1.1.
const UNTIMED_H: i32 = 14;
/// Pixels per half hour: Palm Desktop draws 38 px per hour.
const HALF: i32 = 19;
/// The width of the right pane, 234 px as in Palm Desktop 3.1.1.
const PANE_W: i32 = 234;
/// The width of the tab strip on the right edge.
const TABS_W: i32 = 24;
const ROW_H: i32 = 16;
/// The height of the title box of the Day view.
const TITLE_H: i32 = 18;
/// The gap between the title box and the grid.
const TITLE_GAP: i32 = 4;
/// The size of the Today, Edit... and New... buttons.
const BTN_W: i32 = 70;
const BTN_H: i32 = 20;
/// The height of one row of month buttons.
const MONTH_H: i32 = 15;

/// The mouse event of `ev`, when it is one a Date Book view takes.
pub fn mouse_of(ev: Event) -> Option<Mouse> {
	let kind = match ev {
		Event::Push => MouseKind::Press,
		Event::Drag => MouseKind::Drag,
		Event::Released => MouseKind::Release,
		Event::Move => MouseKind::Move,
		Event::MouseWheel => MouseKind::Wheel(match app::event_dy() {
			MouseWheel::Up => -1,
			MouseWheel::Down => 1,
			_ => 0,
		}),
		_ => return None,
	};
	Some(Mouse {
		kind,
		x: app::event_x(),
		y: app::event_y(),
		right: app::event_button() == RIGHT,
		double: app::event_clicks(),
	})
}

/// Sends the mouse events of a widget as messages; `wrap` makes the message.
pub fn forward_mouse<W: WidgetBase>(w: &mut W, s: Sender<Msg>, wrap: impl Fn(Mouse) -> Msg + 'static) {
	w.handle(move |_, ev| match mouse_of(ev) {
		Some(m) => {
			s.send(wrap(m));
			true
		}
		// Enter keeps the Move events coming.
		None => ev == Event::Enter || ev == Event::Leave,
	});
}

/// A vertical scroll bar for a custom view whose content is taller than the view.
pub struct Scroller {
	pub bar: Scrollbar,
	/// An offset asked for before the range was set, applied by the first set_range.
	pending: Option<i32>,
}

impl Scroller {
	pub fn new(x: i32, y: i32, h: i32, s: Sender<Msg>, msg: fn() -> Msg) -> Scroller {
		let mut bar = Scrollbar::new(x, y, app::scrollbar_size(), h, None);
		bar.set_type(ScrollbarType::Vertical);
		bar.set_linesize(10);
		bar.set_callback(move |_| s.send(msg()));
		Scroller { bar, pending: None }
	}

	pub fn offset(&self) -> i32 {
		self.bar.value() as i32
	}

	/// Sets the range for a view of `view` px over content of `total` px, keeping the offset in range. A
	/// pending offset waits until the content is taller than the view.
	pub fn set_range(&mut self, view: i32, total: i32) {
		set_bar_range(&mut self.bar, view, total);
		if total - view > 0 {
			if let Some(y) = self.pending.take() {
				self.scroll_to(y);
			}
		}
	}

	pub fn scroll_to(&mut self, y: i32) {
		let max = self.bar.maximum();
		self.bar.set_value((y as f64).clamp(0.0, max));
	}

	/// Scrolls to `y` once the range is known.
	pub fn scroll_later(&mut self, y: i32) {
		self.pending = Some(y);
	}

	/// Turns the wheel by `lines`.
	pub fn wheel(&mut self, lines: i32) {
		let v = self.bar.value() + (lines * 3 * 10) as f64;
		self.scroll_to(v as i32);
	}
}

/// Sets the range of `bar` for a view of `view` px over content of `total` px, keeping the offset in range.
fn set_bar_range(bar: &mut Scrollbar, view: i32, total: i32) {
	let max = (total - view).max(0);
	bar.set_bounds(0.0, max as f64);
	bar.set_slider_size((view as f32 / total.max(1) as f32).min(1.0));
	bar.set_value(bar.value().clamp(0.0, max as f64));
}

/// The widgets of the Day view that its group lays out. The group's resize callback holds a copy, so the
/// group keeps the fixed sizes of the right pane and the buttons, and only the day area and the lists grow.
#[derive(Clone)]
struct Place {
	title: Frame,
	edge: Frame,
	header: Frame,
	grid: Frame,
	bar: Scrollbar,
	today: Button,
	edit: Button,
	new: Button,
	back: Button,
	year: Frame,
	fwd: Button,
	months: Vec<Button>,
	month_edge: Frame,
	cal_edge: Frame,
	cal: Frame,
	switch: Frame,
	todo_cat: Choice,
	addr_cat: Choice,
	todo_list: TableRow,
	addr_list: TableRow,
	look_label: Frame,
	look: Input,
	model: Rc<RefCell<DayModel>>,
}

impl Place {
	/// Lays the Day view out in `g`: the day area takes the width left of the right pane, the grid takes the
	/// height under the title, and the lists take the height under the switch. The fixed parts keep their sizes.
	fn apply(&mut self, g: Rect) {
		let (cx, cy, cw, ch) = (g.x, g.y, g.w, g.h);
		let dw = (cw - PANE_W - 11).max(0);
		let rows = self.model.borrow().untimed_rows;
		let hh = rows * UNTIMED_H + SEL_H;
		let gy = cy + TITLE_H + TITLE_GAP;
		let gh = (ch - (gy - cy) - 33).max(0);
		let sb = app::scrollbar_size();
		let view = (gh - 2 - hh).max(0);
		self.title.resize(cx, cy, dw, TITLE_H);
		self.edge.resize(cx, gy, dw, gh);
		self.header.resize(cx + 1, gy + 1, (dw - 2).max(0), hh);
		self.grid.resize(cx + 1, gy + 1 + hh, (dw - 2 - sb).max(0), view);
		self.bar.resize(cx + dw - 1 - sb, gy + 1 + hh, sb, view);
		set_bar_range(&mut self.bar, view, y_of(24 * 60) + 1);
		self.model.borrow_mut().offset = self.bar.value() as i32;
		let by = cy + ch - 28;
		self.today.resize(cx + dw - 228, by, BTN_W, BTN_H);
		self.edit.resize(cx + dw - 152, by, BTN_W, BTN_H);
		self.new.resize(cx + dw - 76, by, BTN_W, BTN_H);

		let px = cx + cw - PANE_W;
		self.back.resize(px, cy + 2, 13, 20);
		self.year.resize(px + 14, cy + 2, PANE_W - 28, 20);
		self.fwd.resize(px + PANE_W - 13, cy + 2, 13, 20);
		let bw = PANE_W / 6;
		for (m, b) in self.months.iter_mut().enumerate() {
			let (c, r) = ((m % 6) as i32, (m / 6) as i32);
			b.resize(px + c * bw, cy + 26 + r * MONTH_H, bw, MONTH_H);
		}
		self.month_edge.resize(px, cy + 26, PANE_W, 2 * MONTH_H);
		self.cal_edge.resize(px, cy + 60, PANE_W, 130);
		self.cal.resize(px + 2, cy + 62, PANE_W - 4, 126);
		let hy = cy + 194;
		self.switch.resize(px, hy, 117, 18);
		self.todo_cat.resize(px + 121, hy, PANE_W - 121, 18);
		self.addr_cat.resize(px + 121, hy, PANE_W - 121, 18);
		let ly = hy + 22;
		let list_h = (ch - (ly - cy)).max(0);
		self.todo_list.resize(px, ly, PANE_W, list_h);
		self.addr_list.resize(px, ly, PANE_W, (list_h - 24).max(0));
		self.look_label.resize(px, cy + ch - 20, 44, 20);
		self.look.resize(px + 48, cy + ch - 20, PANE_W - 48, 20);
	}
}

/// A field that types a new event or a box title, over the box.
pub struct TypeField {
	pub input: Input,
}

impl TypeField {
	/// Opens a field at (x, y, w) in window coordinates, holding `text`, in the group `g`. No group is current
	/// afterwards: FLTK's end() makes the parent of `g` current, and a dialog window built later would become
	/// a child of that group, drawn inside the view with no title bar.
	pub fn open(g: &Group, x: i32, y: i32, w: i32, text: &str, s: Sender<Msg>) -> TypeField {
		g.begin();
		let mut input = Input::new(x, y, w.max(20), 17, None);
		g.end();
		Group::set_current(None::<&Group>);
		input.set_frame(FrameType::FlatBox);
		input.set_color(Color::White);
		// The font of the box titles, so the text keeps its size while it is edited.
		input.set_text_font(theme::fonts().sans);
		input.set_text_size(11);
		input.set_value(text);
		input.set_trigger(CallbackTrigger::EnterKeyAlways);
		input.set_callback(move |_| s.send(Msg::Date(DateMsg::Field(FieldEv::Enter))));
		input.handle(move |i, ev| match ev {
			Event::KeyDown if app::event_key() == Key::Escape => {
				s.send(Msg::Date(DateMsg::Field(FieldEv::Escape)));
				true
			}
			Event::Unfocus => {
				s.send(Msg::Date(DateMsg::Field(FieldEv::Lost)));
				false
			}
			Event::Push if app::event_button() == RIGHT => {
				Field::Input(i.clone()).popup();
				true
			}
			Event::Push if app::event_clicks() => {
				s.send(Msg::Date(DateMsg::Field(FieldEv::Double)));
				true
			}
			_ => false,
		});
		let _ = input.take_focus();
		input.redraw();
		TypeField { input }
	}

	pub fn text(&self) -> String {
		self.input.value().trim().to_string()
	}

	pub fn close(self) {
		let mut parent = self.input.parent();
		app::delete_widget(self.input);
		if let Some(p) = parent.as_mut() {
			p.redraw();
		}
	}
}

/// A field that types a new event into an empty hour or the untimed rows.
struct Typing {
	field: TypeField,
	day: i64,
	min: i32,
	untimed: bool,
}

/// The in-place editor of a box title.
struct TitleEdit {
	field: TypeField,
	occ: Occ,
}

/// A press on a box: the box moves to a new time or day, or its end is resized. `moved` after 4 px.
struct Drag {
	occ: Occ,
	resize: bool,
	header: bool,
	/// The distance from the top of the box to the press.
	grab: i32,
	origin: (i32, i32),
	moved: bool,
	new_start: i32,
	new_end: i32,
	/// The calendar day under the mouse.
	drop_day: Option<i64>,
}

/// What the Day header and grid draw. The view writes it; the draw closures read it.
#[derive(Default)]
struct DayModel {
	occs: Vec<Occ>,
	selected: Option<i64>,
	/// The untimed rows: one for each untimed event and an open untimed field, at least one.
	untimed_rows: i32,
	/// The box of the field of a new event, local to the header (true) or the grid.
	typing: Option<(bool, Rect)>,
	/// The outline of a box being moved or resized, local to the grid.
	outline: Option<Rect>,
	/// The outline of the event a dragged pane row makes, local to the grid.
	drop_box: Option<Rect>,
	/// The event whose title is open for editing: its box is drawn white, with the field over the title.
	editing: Option<i64>,
	offset: i32,
	h24: bool,
}

/// One row of the To Do pane.
struct PaneTodo {
	todo: Todo,
	due: String,
	overdue: bool,
}

/// What the right pane and the calendar draw.
#[derive(Default)]
struct PaneModel {
	day: i64,
	today: i64,
	begins: u32,
	address: bool,
	todos: Vec<PaneTodo>,
	show_pri: bool,
	show_due: bool,
	addrs: Vec<address::Row>,
}

/// Pixel row on the grid of a time in minutes after midnight.
fn y_of(min: i32) -> i32 {
	min * HALF / 30
}

/// Minutes after midnight at a pixel row of the grid.
fn min_at(y: i32) -> i32 {
	y * 30 / HALF
}

/// The start of an event dropped at pixel row `y`: the half hour under the pointer. It lasts one hour.
fn drop_min(y: i32) -> i32 {
	(min_at(y) / 30 * 30).clamp(0, 23 * 60)
}

fn round_half(min: i32) -> i32 {
	(min + 15) / 30 * 30
}

/// The box of a one-hour event from `min` in a grid `width` px wide.
fn hour_box(min: i32, width: i32) -> Rect {
	let top = y_of(min);
	Rect::new(TIME_W + 2, top + 2, width - TIME_W - 4, y_of(min + 60) - top - 3)
}

/// The outline of a dragged event from `start` to `end` minutes, at least a half hour tall.
fn outline_box(start: i32, end: i32, width: i32) -> Rect {
	let top = y_of(start);
	Rect::new(
		TIME_W + 3,
		top + 1,
		width - TIME_W - 5,
		y_of(end).max(top + HALF) - top - 2,
	)
}

/// The bounds of each event for a width of `width` px: an untimed event on its own full-width row of the
/// header, as in Palm Desktop, and a timed event on the grid, in columns where events overlap. Returns
/// the index into `occs`, the box and true for a header row.
fn layout_day(occs: &[Occ], width: i32) -> Vec<(usize, Rect, bool)> {
	let mut out = Vec::new();
	let mut row = 0;
	for (i, o) in occs.iter().enumerate() {
		if o.event.untimed {
			out.push((
				i,
				Rect::new(TIME_W + 2, row * UNTIMED_H + 1, width - TIME_W - 4, UNTIMED_H - 2),
				true,
			));
			row += 1;
		}
	}
	let timed: Vec<usize> = (0..occs.len()).filter(|i| !occs[*i].event.untimed).collect();
	// The column of each event: the first column that is free at its start.
	let mut col_end: Vec<i32> = Vec::new();
	let mut col = vec![0usize; timed.len()];
	for (k, &i) in timed.iter().enumerate() {
		let e = &occs[i].event;
		let c = col_end.iter().position(|end| *end <= e.start).unwrap_or_else(|| {
			col_end.push(0);
			col_end.len() - 1
		});
		col_end[c] = e.end;
		col[k] = c;
	}
	let area_w = width - TIME_W - 2;
	for (k, &i) in timed.iter().enumerate() {
		let e = &occs[i].event;
		// The column count of an event: the widest column among the events it overlaps.
		let mut n = col[k] + 1;
		for (j, &o) in timed.iter().enumerate() {
			let p = &occs[o].event;
			if e.start < p.end && p.start < e.end {
				n = n.max(col[j] + 1);
			}
		}
		let col_w = area_w / n as i32;
		let top = y_of(e.start);
		let bottom = y_of(e.end).max(top + HALF);
		// Palm Desktop keeps a 2 px gap between the box and the grid lines.
		out.push((
			i,
			Rect::new(TIME_W + 2 + col[k] as i32 * col_w, top + 2, col_w - 2, bottom - top - 3),
			false,
		));
	}
	out
}

pub struct DateView {
	pub root: Group,
	pub mode: DateMode,
	tabs: Frame,
	tab_mode: Rc<Cell<DateMode>>,
	day_group: Group,
	week: WeekView,
	month: MonthView,
	title: Frame,
	header: Frame,
	grid: Frame,
	scroller: Scroller,
	model: Rc<RefCell<DayModel>>,
	edit_b: Button,
	year: Frame,
	months: Vec<Button>,
	cal: Frame,
	switch: Frame,
	todo_cat: IdChoice,
	addr_cat: IdChoice,
	todo_list: TableRow,
	addr_list: TableRow,
	look_label: Frame,
	look: Input,
	pane: Rc<RefCell<PaneModel>>,
	place: Place,
	/// The day the grid last showed; a new day opens at the work day.
	shown_day: Option<i64>,
	occs: Vec<Occ>,
	selected_id: Option<i64>,
	/// The To Do category of the pane, None for All.
	todo_cat_id: Option<i32>,
	pane_todos: Vec<Todo>,
	pane_addrs: Vec<Address>,
	typing: Option<Typing>,
	title_edit: Option<TitleEdit>,
	drag: Option<Drag>,
	suppress_click: bool,
	/// A press on a pane row, and true once it drags.
	pane_press: Option<(i32, i32, i32, bool)>,
	s: Sender<Msg>,
}

impl DateView {
	/// Builds the view in (x, y, w, h), the whole group of the Date view.
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>, settings: &Settings) -> DateView {
		let fonts = theme::fonts();
		let mut root = Group::new(x, y, w, h, None);
		root.set_frame(FrameType::FlatBox);
		root.set_color(WORKSPACE);
		// The views sit on a raised panel over the workspace gray, and the tabs on the gray beside it.
		let (bx, by, bw, bh) = (x + 4, y + 4, w - 4 - 3 - TABS_W, h - 4 - 3);
		let mut body = Frame::new(bx, by, bw, bh, None);
		body.set_frame(FrameType::FlatBox);
		body.set_color(PANEL);
		body.draw(|f| {
			// The raised bevel without its right side; the tab strip draws that edge.
			let d = Dev::begin();
			let (x, y, w, h) = d.rect(f.x(), f.y(), f.w(), f.h());
			let k = d.lw();
			draw::draw_rect_fill(x, y, w, k, HIGHLIGHT);
			draw::draw_rect_fill(x, y, k, h - k, HIGHLIGHT);
			draw::draw_rect_fill(x, y + h - 2 * k, w, k, SHADOW);
			draw::draw_rect_fill(x, y + h - k, w, k, Color::Black);
		});
		let (cx, cy, cw, ch) = (bx + 8, by + 8, bw - 14, bh - 16);

		// The Day view and the right pane.
		let day_group = Group::new(cx, cy, cw, ch, None);
		let dw = cw - PANE_W - 11;
		let mut title = Frame::new(cx, cy, dw, TITLE_H, None);
		title.set_frame(FrameType::DownBox);
		title.set_color(Color::White);
		title.set_label_font(fonts.arial_bold);
		title.set_label_size(13);
		title.set_label_color(Color::Black);
		title.handle(move |_, ev| {
			if ev == Event::Push && app::event_clicks() {
				s.send(Msg::Date(DateMsg::TitleDouble));
				return true;
			}
			false
		});
		let gy = cy + TITLE_H + TITLE_GAP;
		let gh = ch - (gy - cy) - 33;
		let model = Rc::new(RefCell::new(DayModel {
			untimed_rows: 1,
			h24: crate::platform::uses_24_hours(),
			..DayModel::default()
		}));
		// The black edge around the header and the grid.
		let mut edge = Frame::new(cx, gy, dw, gh, None);
		edge.set_frame(FrameType::BorderBox);
		edge.set_color(Color::White);
		let hh = UNTIMED_H + SEL_H;
		let mut header = Frame::new(cx + 1, gy + 1, dw - 2, hh, None);
		let mut grid = Frame::new(cx + 1, gy + 1 + hh, dw - 2 - app::scrollbar_size(), gh - 2 - hh, None);
		let scroller = Scroller::new(cx + dw - 1 - app::scrollbar_size(), gy + 1 + hh, gh - 2 - hh, s, || {
			Msg::Date(DateMsg::Scrolled)
		});
		{
			let m = model.clone();
			header.draw(move |f| draw_header(f, &m.borrow()));
			let m = model.clone();
			grid.draw(move |f| draw_grid(f, &m.borrow()));
		}
		forward_mouse(&mut header, s, |m| Msg::Date(DateMsg::Day(true, m)));
		forward_mouse(&mut grid, s, |m| Msg::Date(DateMsg::Day(false, m)));
		let by2 = cy + ch - 28;
		let mut today_b = Button::new(cx + dw - 228, by2, BTN_W, BTN_H, "T&oday");
		today_b.set_callback(move |_| s.send(Msg::Date(DateMsg::Today)));
		let mut edit_b = Button::new(cx + dw - 152, by2, BTN_W, BTN_H, "Ed&it...");
		edit_b.set_callback(move |_| s.send(Msg::Date(DateMsg::Edit)));
		edit_b.deactivate();
		let mut new_b = Button::new(cx + dw - 76, by2, BTN_W, BTN_H, "&New...");
		new_b.set_callback(move |_| s.send(Msg::Date(DateMsg::New)));

		// The right pane.
		let px = cx + cw - PANE_W;
		let mut back = crate::draw::arrow(px, cy + 2, 13, 20, false);
		back.set_callback(move |_| s.send(Msg::Date(DateMsg::Year(-1))));
		let mut year = Frame::new(px + 14, cy + 2, PANE_W - 28, 20, None);
		year.set_frame(FrameType::DownBox);
		year.set_color(Color::White);
		year.set_label_font(fonts.sans_bold);
		let mut fwd = crate::draw::arrow(px + PANE_W - 13, cy + 2, 13, 20, true);
		fwd.set_callback(move |_| s.send(Msg::Date(DateMsg::Year(1))));
		let mut months = Vec::new();
		for m in 0..12u32 {
			let (c, r) = ((m % 6) as i32, (m / 6) as i32);
			let bw = PANE_W / 6;
			let mut b = Button::new(px + c * bw, cy + 26 + r * MONTH_H, bw, MONTH_H, MONTH_SHORT[m as usize]);
			b.set_frame(FrameType::FlatBox);
			b.set_down_frame(FrameType::FlatBox);
			b.clear_visible_focus();
			b.draw(|b| {
				// The black lines of the month grid: right and bottom of each button, and the top and left
				// of the first row and column.
				let d = Dev::begin();
				let (x, y, w, h) = d.rect(b.x(), b.y(), b.w(), b.h());
				let k = d.lw();
				draw::draw_rect_fill(x + w - k, y, k, h, Color::Black);
				draw::draw_rect_fill(x, y + h - k, w, k, Color::Black);
			});
			b.set_callback(move |_| s.send(Msg::Date(DateMsg::Month(m + 1))));
			months.push(b);
		}
		let mut month_edge = Frame::new(px, cy + 26, PANE_W, 2 * MONTH_H, None);
		month_edge.draw(|f| {
			let d = Dev::begin();
			let (x, y, w, h) = d.rect(f.x(), f.y(), f.w(), f.h());
			let k = d.lw();
			draw::draw_rect_fill(x, y, w, k, Color::Black);
			draw::draw_rect_fill(x, y, k, h, Color::Black);
		});
		let pane = Rc::new(RefCell::new(PaneModel::default()));
		let mut cal_edge = Frame::new(px, cy + 60, PANE_W, 130, None);
		cal_edge.set_frame(FrameType::DownBox);
		let mut cal = Frame::new(px + 2, cy + 62, PANE_W - 4, 126, None);
		{
			let p = pane.clone();
			cal.draw(move |f| draw_calendar(f, &p.borrow()));
		}
		forward_mouse(&mut cal, s, |m| Msg::Date(DateMsg::Calendar(m)));
		let hy = cy + 194;
		let mut switch = Frame::new(px, hy, 117, 18, None);
		switch.set_frame(FrameType::DownBox);
		{
			let p = pane.clone();
			switch.draw(move |f| {
				let address = p.borrow().address;
				let half = (f.w() - 4) / 2;
				for (i, (label, on)) in [("Address", address), ("To Do", !address)].into_iter().enumerate() {
					let x = f.x() + 2 + i as i32 * half;
					draw::draw_rect_fill(x, f.y() + 2, half, f.h() - 4, if on { SELECT } else { Color::White });
					draw::set_font(theme::fonts().sans, 11);
					draw::set_draw_color(if on { Color::White } else { Color::Black });
					draw::draw_text2(label, x, f.y() + 2, half, f.h() - 4, Align::Center);
				}
			});
		}
		switch.handle(move |f, ev| {
			if ev == Event::Push && app::event_button() == LEFT {
				s.send(Msg::Date(DateMsg::Pane(app::event_x() < f.x() + f.w() / 2)));
				return true;
			}
			false
		});
		let mut todo_choice = Choice::new(px + 121, hy, PANE_W - 121, 18, None);
		crate::draw::style_menu(&mut todo_choice);
		crate::combo::drop_below(&mut todo_choice);
		let todo_cat = IdChoice::new(todo_choice, Some(0), s, Some(|| Msg::Date(DateMsg::Category)));
		let mut addr_choice = Choice::new(px + 121, hy, PANE_W - 121, 18, None);
		crate::draw::style_menu(&mut addr_choice);
		crate::combo::drop_below(&mut addr_choice);
		let addr_cat = IdChoice::new(addr_choice, Some(2), s, Some(|| Msg::Date(DateMsg::Category)));
		let ly = hy + 22;
		let mut lists = Vec::new();
		for address in [false, true] {
			let mut t = TableRow::new(px, ly, PANE_W, cy + ch - ly, None);
			t.set_type(TableRowSelectMode::Single);
			t.set_rows(0);
			t.set_cols(1);
			t.set_row_height_all(ROW_H);
			t.set_col_header(false);
			t.set_row_header(false);
			t.set_frame(FrameType::DownBox);
			t.set_color(Color::White);
			t.end();
			let p = pane.clone();
			let (note, key) = (icon("note"), icon("private"));
			let widest = Rc::new(Cell::new(0));
			t.draw_cell(move |t, ctx, row, _col, x, y, w, h| match ctx {
				TableContext::StartPage => {
					crate::records::fit_column(t, ROW_H);
					if address {
						widest.set(widest_number(&p.borrow().addrs));
					}
				}
				TableContext::Cell => {
					let p = p.borrow();
					let _clip = Clip::cell(t, x, y, w, h);
					if address {
						if let Some(r) = p.addrs.get(row as usize) {
							draw_row(r, t.row_selected(row), (x, y, w, h), widest.get());
						}
					} else if let Some(r) = p.todos.get(row as usize) {
						draw_pane_todo(r, &p, t.row_selected(row), (x, y, w, h), (&note, &key));
					}
				}
				_ => {}
			});
			t.handle(move |t, ev| pane_event(t, ev, s));
			lists.push(t);
		}
		let addr_list = lists.pop().expect("two lists");
		let todo_list = lists.pop().expect("two lists");
		let mut look_label = Frame::new(px, cy + ch - 20, 44, 20, "Look up:");
		look_label.set_align(Align::Left | Align::Inside);
		let mut look = Input::new(px + 48, cy + ch - 20, PANE_W - 48, 20, None);
		look.set_trigger(CallbackTrigger::Changed);
		look.set_callback(move |_| s.send(Msg::Date(DateMsg::Look)));
		Field::Input(look.clone()).attach_menu();
		day_group.end();
		// The group lays its widgets out on every resize, so the buttons and the right pane never stretch.
		let mut place = Place {
			title: title.clone(),
			edge: edge.clone(),
			header: header.clone(),
			grid: grid.clone(),
			bar: scroller.bar.clone(),
			today: today_b.clone(),
			edit: edit_b.clone(),
			new: new_b.clone(),
			back: back.clone(),
			year: year.clone(),
			fwd: fwd.clone(),
			months: months.clone(),
			month_edge: month_edge.clone(),
			cal_edge: cal_edge.clone(),
			cal: cal.clone(),
			switch: switch.clone(),
			todo_cat: todo_cat.choice.clone(),
			addr_cat: addr_cat.choice.clone(),
			todo_list: todo_list.clone(),
			addr_list: addr_list.clone(),
			look_label: look_label.clone(),
			look: look.clone(),
			model: model.clone(),
		};
		let mut dg = day_group.clone();
		let mut on_resize = place.clone();
		dg.resize_callback(move |_, x, y, w, h| on_resize.apply(Rect::new(x, y, w, h)));
		place.apply(Rect::new(cx, cy, cw, ch));

		let week = WeekView::new(cx, cy, cw, ch, s);
		let month = MonthView::new(cx, cy, cw, ch, s);

		// The tabs on the right edge.
		let tab_mode = Rc::new(Cell::new(DateMode::Day));
		let mut tabs = Frame::new(bx + bw, by, TABS_W, bh, None);
		{
			let m = tab_mode.clone();
			tabs.draw(move |f| draw_tabs(f, m.get()));
		}
		tabs.handle(move |f, ev| match ev {
			Event::Push if app::event_button() == LEFT => {
				let y = app::event_y() - f.y();
				let mut top = 2;
				for mode in [DateMode::Day, DateMode::Week, DateMode::Month] {
					let len = tab_len(mode);
					if y >= top && y < top + len {
						s.send(Msg::Date(if app::event_clicks() {
							DateMsg::Today
						} else {
							DateMsg::Mode(mode)
						}));
					}
					top += len;
				}
				true
			}
			// Alt+D, Alt+W and Alt+M, as in Palm Desktop.
			Event::Shortcut if app::event_state().contains(EventState::Alt) => {
				let mode = match app::event_key().to_char().map(|c| c.to_ascii_lowercase()) {
					Some('d') => DateMode::Day,
					Some('w') => DateMode::Week,
					Some('m') => DateMode::Month,
					_ => return false,
				};
				s.send(Msg::Date(DateMsg::Mode(mode)));
				true
			}
			_ => false,
		});
		let rgrow = Frame::new(bx + 20, by + 20, bw - 40, bh - 40, None);
		root.resizable(&rgrow);
		root.end();

		let mut v = DateView {
			root,
			mode: DateMode::Day,
			tabs,
			tab_mode,
			day_group,
			week,
			month,
			title,
			header,
			grid,
			scroller,
			model,
			edit_b,
			year,
			months,
			cal,
			switch,
			todo_cat,
			addr_cat,
			todo_list,
			addr_list,
			look_label,
			look,
			pane,
			place,
			shown_day: None,
			occs: Vec::new(),
			selected_id: None,
			todo_cat_id: None,
			pane_todos: Vec::new(),
			pane_addrs: Vec::new(),
			typing: None,
			title_edit: None,
			drag: None,
			suppress_click: false,
			pane_press: None,
			s,
		};
		let mode = match settings.get("dateMode", "day").as_str() {
			"week" => DateMode::Week,
			"month" => DateMode::Month,
			_ => DateMode::Day,
		};
		v.show_mode(mode);
		// The Day grid opens at the work day hour, as in Palm Desktop.
		let wd = work_day(settings);
		v.scroller.scroll_later(y_of(wd * 60));
		v.week.scroll_to_hour(wd);
		v
	}

	pub fn look(&self) -> &Input {
		&self.look
	}

	/// The editors of the view that take the Edit commands: an open field, else Look up.
	pub fn field_widget(&self) -> Option<Input> {
		self.typing
			.as_ref()
			.map(|t| t.field.input.clone())
			.or_else(|| self.title_edit.as_ref().map(|t| t.field.input.clone()))
			.or_else(|| self.week.field())
	}

	pub fn focus(&mut self) {
		match self.mode {
			DateMode::Day => drop(self.grid.take_focus()),
			DateMode::Week => self.week.focus(),
			DateMode::Month => self.month.focus(),
		}
	}

	/// Shows the Day, Week or Month view and remembers the choice. Palm Desktop shows the right pane in the
	/// Day view only; Week and Month take the full width.
	pub fn show_mode(&mut self, mode: DateMode) {
		self.mode = mode;
		self.tab_mode.set(mode);
		let groups: [(&mut Group, DateMode); 3] = [
			(&mut self.day_group, DateMode::Day),
			(&mut self.week.root, DateMode::Week),
			(&mut self.month.root, DateMode::Month),
		];
		for (g, m) in groups {
			if m == mode {
				g.show();
			} else {
				g.hide();
			}
		}
		self.tabs.redraw();
		self.root.redraw();
	}

	/// Shows another view and remembers it. The open fields save first, so their text is not lost.
	fn switch_mode(&mut self, ctx: &mut Ctx, mode: DateMode) {
		self.end_typing_ctx(ctx, true);
		self.end_title_edit_ctx(ctx, true);
		self.week.close_saving(ctx);
		self.show_mode(mode);
		ctx.set("dateMode", mode.key());
		self.refresh(ctx);
	}

	/// Reads the store, the settings and the day again: every part of the view.
	pub fn refresh(&mut self, ctx: &mut Ctx) {
		let day = *ctx.day;
		if self.typing.as_ref().is_some_and(|t| t.day != day) {
			// The field belongs to the day it was opened on.
			self.end_typing_ctx(ctx, true);
		}
		self.end_title_edit_ctx(ctx, true);
		self.title.set_label(&date_title(day));
		// The title is red only for today.
		self.title
			.set_label_color(if day == today() { Color::Red } else { Color::Black });
		if self.shown_day != Some(day) {
			// A newly shown day opens at the work day start.
			self.shown_day = Some(day);
			self.scroller.scroll_later(y_of(work_day(ctx.settings) * 60));
		}
		self.occs = visible_events(ctx.store, day, ctx.show_private);
		if !self.occs.iter().any(|o| Some(o.event.id) == self.selected_id) {
			self.selected_id = None;
		}
		if self.selected_id.is_some() {
			self.edit_b.activate();
		} else {
			self.edit_b.deactivate();
		}
		let d = ymd(day);
		self.year.set_label(&d.year.to_string());
		for (i, b) in self.months.iter_mut().enumerate() {
			let on = i as u32 + 1 == d.month;
			b.set_color(if on { SELECT } else { Color::White });
			b.set_label_color(if on { Color::White } else { Color::Black });
			b.redraw();
		}
		let address = ctx.settings.get("datePane", "todo") == "address";
		self.refresh_categories(ctx, address);
		self.refresh_lists(ctx, address);
		{
			let mut p = self.pane.borrow_mut();
			p.day = day;
			p.today = today();
			p.begins = week_begins(ctx.settings);
			p.address = address;
		}
		self.sync_model();
		self.week.refresh(ctx);
		self.month.refresh(ctx);
		self.cal.redraw();
		self.switch.redraw();
		self.root.redraw();
	}

	/// The category drop-down of the pane: the To Do categories, or the Address categories and Unfiled.
	fn refresh_categories(&mut self, ctx: &mut Ctx, address: bool) {
		if address {
			let cur = ctx.settings.get_int("addressCategory", ALL);
			let mut items = vec![("All".to_string(), ALL)];
			items.extend(ctx.store.address_categories.iter().map(|c| (c.name.clone(), c.id)));
			items.push(("Unfiled".to_string(), UNFILED));
			self.addr_cat.set_items(&items, true, cur);
			self.addr_cat.choice.show();
			self.todo_cat.choice.hide();
		} else {
			if !ctx.store.categories.iter().any(|c| Some(c.id) == self.todo_cat_id) {
				self.todo_cat_id = None;
			}
			let mut items = vec![("All".to_string(), ALL)];
			items.extend(ctx.store.categories.iter().map(|c| (c.name.clone(), c.id)));
			self.todo_cat.set_items(&items, true, self.todo_cat_id.unwrap_or(ALL));
			self.todo_cat.choice.show();
			self.addr_cat.choice.hide();
		}
	}

	/// Fills the list of the pane mode and shows it, with Look up in Address mode.
	fn refresh_lists(&mut self, ctx: &mut Ctx, address: bool) {
		let today = today();
		let list: Vec<Todo> = ctx
			.store
			.todos
			.iter()
			.filter(|t| {
				self.todo_cat_id.is_none_or(|c| t.category == c) && todo_shown(t, ctx.settings, ctx.show_private, today)
			})
			.cloned()
			.collect();
		self.pane_todos = todo_order(list, ctx.settings, ctx.store);
		let keep = self.selected_addr();
		self.pane_addrs = address::visible(ctx);
		{
			let mut p = self.pane.borrow_mut();
			p.todos = self
				.pane_todos
				.iter()
				.map(|t| PaneTodo {
					todo: t.clone(),
					due: due_label(t, today),
					overdue: is_overdue(t, today),
				})
				.collect();
			p.show_pri = ctx.settings.get_bool("todoShowPri", false);
			p.show_due = ctx.settings.get_bool("todoShowDue", true);
			let by = by_company(ctx.settings);
			p.addrs = self.pane_addrs.iter().map(|a| row_of(a, by)).collect();
		}
		for (t, n) in [
			(&mut self.todo_list, self.pane_todos.len()),
			(&mut self.addr_list, self.pane_addrs.len()),
		] {
			t.set_rows(n as i32);
			t.set_row_height_all(ROW_H);
			t.redraw();
		}
		self.addr_list.select_all_rows(TableRowSelectFlag::Deselect);
		if let Some(i) = keep.and_then(|id| self.pane_addrs.iter().position(|a| a.id == id)) {
			let _ = self.addr_list.select_row(i as i32, TableRowSelectFlag::Select);
		}
		if address {
			self.todo_list.hide();
			self.addr_list.show();
			self.look_label.show();
			self.look.show();
		} else {
			self.addr_list.hide();
			self.todo_list.show();
			self.look_label.hide();
			self.look.hide();
		}
	}

	fn selected_addr(&self) -> Option<i64> {
		records_selected(&self.addr_list, self.pane_addrs.len()).map(|i| self.pane_addrs[i].id)
	}

	/// Copies the state the Day header and grid draw into their model, and sizes the header.
	fn sync_model(&mut self) {
		let extra = i32::from(self.typing.as_ref().is_some_and(|t| t.untimed));
		let rows = (self.occs.iter().filter(|o| o.event.untimed).count() as i32 + extra).max(1);
		{
			let mut m = self.model.borrow_mut();
			m.occs = self.occs.clone();
			m.selected = self.selected_id;
			m.untimed_rows = rows;
			m.editing = self.title_edit.as_ref().map(|t| t.occ.event.id);
		}
		// The header grows by one row for each untimed event, and the grid takes the rest of the height.
		let g = Rect::new(
			self.day_group.x(),
			self.day_group.y(),
			self.day_group.w(),
			self.day_group.h(),
		);
		self.place.apply(g);
		let typing = self.typing.as_ref().map(|t| {
			let w = if t.untimed { self.header.w() } else { self.grid.w() };
			let r = if t.untimed {
				Rect::new(TIME_W + 2, (rows - 1) * UNTIMED_H + 1, w - TIME_W - 4, UNTIMED_H - 2)
			} else {
				hour_box(t.min, w)
			};
			(t.untimed, r)
		});
		self.model.borrow_mut().typing = typing;
		let gh = self.grid.h();
		self.scroller.set_range(gh, y_of(24 * 60) + 1);
		self.model.borrow_mut().offset = self.scroller.offset();
		self.place_fields();
		self.header.redraw();
		self.grid.redraw();
		self.scroller.bar.redraw();
	}

	/// Puts the open fields over their boxes.
	fn place_fields(&mut self) {
		let typing_box = self.model.borrow().typing;
		if let (Some(t), Some((untimed, r))) = (self.typing.as_mut(), typing_box) {
			let (wx, wy) = if untimed {
				(self.header.x(), self.header.y())
			} else {
				(self.grid.x(), self.grid.y() - self.scroller.offset())
			};
			t.field.input.resize(wx + r.x + 3, wy + r.y + 1, r.w - BAR_W - 4, 17);
		}
	}

	/// The occurrence selected in the Day view.
	fn selected_occ(&self) -> Option<Occ> {
		self.occs.iter().find(|o| Some(o.event.id) == self.selected_id).cloned()
	}

	/// The selected occurrence of the view shown: the Week view's own selection, else the Day selection.
	fn current_occ(&self) -> Option<Occ> {
		match self.mode {
			DateMode::Week => self.week.selected_occ(),
			_ => self.selected_occ(),
		}
	}

	/// True when the view shown has a selected event, for the Edit menu: Edit Event, Delete Event, Delete and
	/// Select All are gray without one, as in Palm Desktop 3.1.1.
	pub fn has_selected_event(&self) -> bool {
		self.current_occ().is_some()
	}

	/// Shows the event `e` in the Day view on its date, selected.
	pub fn select(&mut self, ctx: &mut Ctx, e: &Ev) {
		*ctx.day = e.date;
		self.selected_id = Some(e.id);
		self.switch_mode(ctx, DateMode::Day);
	}

	/// The title and the lines that Print prints: the date and the events of the day, with their times.
	pub fn print_lines(&self, ctx: &Ctx) -> (String, Vec<String>) {
		let lines = self
			.occs
			.iter()
			.map(|o| {
				let e = &o.event;
				let time = if e.untimed {
					"Untimed".to_string()
				} else {
					palm_core::model::time(e.start, ctx.h24)
				};
				format!("{time}  {}", e.title)
			})
			.collect();
		(date_title(*ctx.day), lines)
	}

	pub fn new_record(&mut self, ctx: &mut Ctx) {
		let day = *ctx.day;
		event_dialog::edit(ctx, None, day, None, "");
	}

	pub fn edit_selected(&mut self, ctx: &mut Ctx) {
		if let Some(o) = self.current_occ() {
			event_dialog::edit(ctx, Some(o.event), o.day, None, "");
		}
	}

	pub fn delete_selected(&mut self, ctx: &mut Ctx) {
		if let Some(o) = self.current_occ() {
			if delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, &o) && self.mode == DateMode::Day {
				self.selected_id = None;
			}
		}
	}

	pub fn handle(&mut self, msg: DateMsg, ctx: &mut Ctx) {
		match msg {
			DateMsg::Mode(m) => self.switch_mode(ctx, m),
			DateMsg::Today => *ctx.day = today(),
			DateMsg::New => self.new_record(ctx),
			DateMsg::Edit => self.edit_selected(ctx),
			DateMsg::TitleDouble => {
				if let Some(d) = date_picker::pick(Mode::Day, *ctx.day, today(), week_begins(ctx.settings)) {
					*ctx.day = d;
				}
			}
			DateMsg::Year(n) => *ctx.day = add_months(*ctx.day, 12 * n as i64),
			DateMsg::Month(m) => {
				let d = ymd(*ctx.day);
				*ctx.day = add_months(*ctx.day, m as i64 - d.month as i64);
			}
			DateMsg::Calendar(m) => {
				if m.kind == MouseKind::Press && !m.right {
					if let Some(d) = self.calendar_day(m.x, m.y) {
						*ctx.day = d;
					}
				}
			}
			DateMsg::Pane(address) => {
				ctx.set("datePane", if address { "address" } else { "todo" });
				self.refresh(ctx);
			}
			DateMsg::Category => {
				if self.pane.borrow().address {
					// The category is shared with the Address view.
					ctx.set("addressCategory", &self.addr_cat.id().to_string());
				} else {
					let id = self.todo_cat.id();
					self.todo_cat_id = (id != ALL).then_some(id);
				}
				self.refresh(ctx);
			}
			DateMsg::Look => self.seek_address(ctx),
			DateMsg::PaneList(m) => self.pane_mouse(ctx, m),
			DateMsg::Day(header, m) => self.day_mouse(ctx, header, m),
			DateMsg::Scrolled if self.mode == DateMode::Week => self.week.scrolled(),
			DateMsg::Scrolled => {
				self.model.borrow_mut().offset = self.scroller.offset();
				self.place_fields();
				self.grid.redraw();
				self.root.redraw();
			}
			DateMsg::Field(f) => self.field_event(ctx, f),
			DateMsg::Step(n) => match self.mode {
				DateMode::Week => *ctx.day += 7 * n as i64,
				_ => *ctx.day = add_months(*ctx.day, n as i64),
			},
			DateMsg::GoTo => {
				let mode = if self.mode == DateMode::Week {
					Mode::Week
				} else {
					Mode::Month
				};
				if let Some(d) = date_picker::pick(mode, *ctx.day, today(), week_begins(ctx.settings)) {
					*ctx.day = d;
				}
			}
			DateMsg::WeekHeader(m) => self.week.header_mouse(ctx, m),
			DateMsg::WeekGrid(m) => self.week.grid_mouse(ctx, m),
			DateMsg::MonthGrid(m) => {
				if let Some(mode) = self.month.mouse(ctx, m) {
					self.switch_mode(ctx, mode);
				}
			}
			DateMsg::MonthKey(c) => {
				let day = *ctx.day;
				event_dialog::edit(ctx, None, day, None, &c.to_string());
			}
		}
	}

	/// The day of the calendar cell at window point (x, y), or None outside the day cells.
	fn calendar_day(&self, x: i32, y: i32) -> Option<i64> {
		let (cw, ch) = (self.cal.w() / 7, self.cal.h() / 7);
		if cw == 0 || ch == 0 || !self.cal.visible() {
			return None;
		}
		let (lx, ly) = (x - self.cal.x(), y - self.cal.y());
		if lx < 0 || lx >= self.cal.w() || ly < 0 || ly >= self.cal.h() {
			return None;
		}
		let row = ly / ch - 1;
		if !(0..=5).contains(&row) {
			return None;
		}
		let col = (lx / cw).min(6);
		let p = self.pane.borrow();
		Some(calendar_start(p.day, p.begins) + (row * 7 + col) as i64)
	}

	/// Selects the first address whose list name starts with the Look up text, ignoring case.
	fn seek_address(&mut self, ctx: &Ctx) {
		let text = self.look.value().to_lowercase();
		if text.is_empty() {
			return;
		}
		let by = by_company(ctx.settings);
		if let Some(i) = self
			.pane_addrs
			.iter()
			.position(|a| a.list_name(by).to_lowercase().starts_with(&text))
		{
			self.addr_list.select_all_rows(TableRowSelectFlag::Deselect);
			let _ = self.addr_list.select_row(i as i32, TableRowSelectFlag::Select);
			self.addr_list.set_row_position(i as i32);
			self.addr_list.redraw();
		}
	}

	// The fields that type a new event or a title.

	/// Closes the field of a new event. With `save`, a title that is not empty becomes a one-hour event, or an
	/// untimed one, and the selected event.
	fn end_typing_ctx(&mut self, ctx: &mut Ctx, save: bool) {
		let Some(t) = self.typing.take() else { return };
		let title = t.field.text();
		let (day, min, untimed) = (t.day, t.min, t.untimed);
		t.field.close();
		if save && !title.is_empty() {
			self.add_event(ctx, &title, day, min, untimed);
		}
		self.sync_model();
	}

	/// Closes the title editor. With `save`, a changed title that is not empty is saved for its occurrence.
	fn end_title_edit_ctx(&mut self, ctx: &mut Ctx, save: bool) {
		let Some(t) = self.title_edit.take() else { return };
		let text = t.field.text();
		let occ = t.occ.clone();
		t.field.close();
		if save && !text.is_empty() && text != occ.event.title {
			change_occurrence(ctx.store, &occ, CHANGE_TEXT, |e| e.title = text);
		}
		self.sync_model();
	}

	fn add_event(&mut self, ctx: &mut Ctx, title: &str, day: i64, min: i32, untimed: bool) {
		let mut e = Ev {
			title: title.to_string(),
			date: day,
			untimed,
			start: if untimed { 8 * 60 } else { min },
			end: if untimed { 9 * 60 } else { min + 60 },
			..Ev::default()
		};
		apply_alarm_preset(&mut e, ctx.settings);
		ctx.store.save_event(&mut e);
		self.selected_id = Some(e.id);
	}

	fn field_event(&mut self, ctx: &mut Ctx, f: FieldEv) {
		if self.mode == DateMode::Week {
			return self.week.field_event(ctx, f);
		}
		match f {
			FieldEv::Enter => {
				self.end_typing_ctx(ctx, true);
				self.end_title_edit_ctx(ctx, true);
			}
			FieldEv::Escape => {
				self.end_typing_ctx(ctx, false);
				self.end_title_edit_ctx(ctx, false);
			}
			FieldEv::Lost => {
				// A focus loss saves, unless the focus went to a dialog of the field itself.
				let focused = app::focus().map(|w| w.as_widget_ptr());
				if self
					.typing
					.as_ref()
					.is_some_and(|t| Some(t.field.input.as_widget_ptr()) != focused)
				{
					self.end_typing_ctx(ctx, true);
				}
				if self
					.title_edit
					.as_ref()
					.is_some_and(|t| Some(t.field.input.as_widget_ptr()) != focused)
				{
					self.end_title_edit_ctx(ctx, true);
				}
			}
			FieldEv::Double => {
				// A double click in the field of an empty hour opens Edit Event, as on the hour.
				if let Some(t) = self.typing.as_ref().filter(|t| !t.untimed) {
					let (day, min) = (t.day, t.min);
					self.end_typing_ctx(ctx, false);
					event_dialog::edit(ctx, None, day, Some(min), "");
				}
			}
		}
	}

	/// Opens a field in the hour at `min`, or a new untimed row, for the title of a new event.
	fn start_typing(&mut self, ctx: &mut Ctx, min: i32, untimed: bool) {
		self.end_typing_ctx(ctx, true);
		self.selected_id = None;
		self.edit_b.deactivate();
		let field = TypeField::open(&self.day_group, 0, 0, 50, "", self.s);
		self.typing = Some(Typing {
			field,
			day: *ctx.day,
			min,
			untimed,
		});
		self.sync_model();
	}

	/// Opens an editor on the title of the box `occ` at `r`, local to the header or the grid.
	fn edit_title(&mut self, occ: Occ, r: Rect, header: bool) {
		let (wx, wy) = if header {
			(self.header.x(), self.header.y())
		} else {
			(self.grid.x(), self.grid.y() - self.scroller.offset())
		};
		let x = title_x(r, &occ.event);
		let y = if header { r.y + (r.h - 17) / 2 } else { r.y + 1 };
		let mut field = TypeField::open(
			&self.day_group,
			wx + x,
			wy + y,
			(r.x + r.w - BAR_W - x).max(20),
			&occ.event.title,
			self.s,
		);
		let n = field.input.value().len() as i32;
		let _ = field.input.set_position(n);
		let _ = field.input.set_mark(0);
		self.title_edit = Some(TitleEdit { field, occ });
		self.sync_model();
	}

	/// The local point and the layout of the header (true) or the grid, for window point (x, y).
	fn local(&self, header: bool, x: i32, y: i32) -> (i32, i32) {
		if header {
			(x - self.header.x(), y - self.header.y())
		} else {
			(x - self.grid.x(), y - self.grid.y() + self.scroller.offset())
		}
	}

	fn width_of(&self, header: bool) -> i32 {
		if header {
			self.header.w()
		} else {
			self.grid.w()
		}
	}

	/// The box of the header or the grid at the local point, with its occurrence.
	fn box_at(&self, header: bool, lx: i32, ly: i32) -> Option<(Occ, Rect)> {
		layout_day(&self.occs, self.width_of(header))
			.into_iter()
			.find(|(_, r, h)| *h == header && r.contains(lx, ly))
			.map(|(i, r, _)| (self.occs[i].clone(), r))
	}

	/// The box of the selected timed event, local to the grid.
	fn sel_box(&self) -> Option<Rect> {
		layout_day(&self.occs, self.grid.w())
			.into_iter()
			.find(|(i, _, h)| !h && Some(self.occs[*i].event.id) == self.selected_id)
			.map(|(_, r, _)| r)
	}

	fn set_cursor(&self, c: Cursor) {
		if let Some(mut w) = self.grid.window() {
			w.set_cursor(c);
		}
	}

	fn day_mouse(&mut self, ctx: &mut Ctx, header: bool, m: Mouse) {
		let (lx, ly) = self.local(header, m.x, m.y);
		match m.kind {
			MouseKind::Wheel(n) => {
				self.scroller.wheel(n);
				self.handle(DateMsg::Scrolled, ctx);
			}
			MouseKind::Move => {
				let on = !header && self.sel_box().is_some_and(|r| on_resize_bar(r, lx, ly));
				self.set_cursor(if on { Cursor::NS } else { Cursor::Default });
			}
			MouseKind::Press => {
				self.suppress_click = false;
				let _ = if header {
					self.header.take_focus()
				} else {
					self.grid.take_focus()
				};
				self.end_typing_ctx(ctx, true);
				self.end_title_edit_ctx(ctx, true);
				if m.right {
					// The menu of the box under a right click. Empty space has no menu, as in Palm Desktop.
					if let Some((occ, _)) = self.box_at(header, lx, ly) {
						self.selected_id = Some(occ.event.id);
						self.edit_b.activate();
						self.sync_model();
						self.box_action(ctx, &occ);
					}
					return;
				}
				// The blue bar under the selected box resizes it, so it is tested before the box.
				if !header {
					if let (Some(r), Some(occ)) = (self.sel_box(), self.selected_occ()) {
						if on_resize_bar(r, lx, ly) {
							self.drag = Some(Drag::new(occ, true, false, 0, (lx, ly)));
							return;
						}
					}
				}
				let Some((occ, r)) = self.box_at(header, lx, ly) else {
					return;
				};
				if on_handle(r, lx, ly) || marker_at(r, &occ.event, lx, ly, header).is_some() {
					return;
				}
				self.drag = Some(Drag::new(occ, false, header, ly - r.y, (lx, ly)));
			}
			MouseKind::Drag => {
				let Some(mut d) = self.drag.take() else { return };
				if d.resize {
					self.set_cursor(Cursor::NS);
				}
				if !d.moved && (lx - d.origin.0).abs().max((ly - d.origin.1).abs()) <= 4 {
					self.drag = Some(d);
					return;
				}
				d.moved = true;
				let outline = self.update_drag(&mut d, m.x, m.y, lx, ly);
				self.model.borrow_mut().outline = outline;
				self.drag = Some(d);
				self.grid.redraw();
			}
			MouseKind::Release => {
				let on = !header && self.sel_box().is_some_and(|r| on_resize_bar(r, lx, ly));
				self.set_cursor(if on { Cursor::NS } else { Cursor::Default });
				if let Some(mut d) = self.drag.take() {
					self.model.borrow_mut().outline = None;
					self.grid.redraw();
					if d.moved {
						self.suppress_click = true;
						let _ = self.update_drag(&mut d, m.x, m.y, lx, ly);
						let e = d.occ.event.clone();
						if !d.resize {
							// A moved box is deselected, as in Palm Desktop 3.1.1.
							self.selected_id = None;
							self.edit_b.deactivate();
							self.sync_model();
						}
						if let Some(day) = d.drop_day {
							change_occurrence(ctx.store, &d.occ, CHANGE_TEXT, |x| x.date = day);
						} else if d.resize {
							if d.new_end != e.end {
								change_occurrence(ctx.store, &d.occ, CHANGE_TEXT, |x| x.end = d.new_end);
							}
						} else if !e.untimed && d.new_start != e.start {
							let len = e.end - e.start;
							change_occurrence(ctx.store, &d.occ, CHANGE_TEXT, |x| {
								x.start = d.new_start;
								x.end = d.new_start + len;
							});
						}
						return;
					}
				}
				if self.suppress_click || m.right {
					self.suppress_click = false;
					return;
				}
				self.click(ctx, header, lx, ly, m.double);
			}
		}
	}

	/// A click on the header or the grid: a box is selected, and its markers, its bar or a double click act;
	/// a click on empty space types a new event.
	fn click(&mut self, ctx: &mut Ctx, header: bool, lx: i32, ly: i32, double: bool) {
		match self.box_at(header, lx, ly) {
			Some((occ, r)) => {
				self.selected_id = Some(occ.event.id);
				self.edit_b.activate();
				self.sync_model();
				let marker = marker_at(r, &occ.event, lx, ly, header);
				if double && on_handle(r, lx, ly) {
					event_dialog::edit(ctx, Some(occ.event), occ.day, None, "");
				} else if double {
					// A single click selects the box; a double click on the title edits it in place.
					self.edit_title(occ, r, header);
				} else if marker == Some(Marker::Alarm) {
					edit_alarm(ctx.store, &occ);
				} else if marker == Some(Marker::Note) {
					edit_note(ctx.store, &occ);
				}
			}
			None if !double => {
				if header {
					self.start_typing(ctx, 0, true);
				} else {
					self.start_typing(ctx, (min_at(ly) / 60 * 60).clamp(0, 23 * 60), false);
				}
			}
			None => {}
		}
	}

	/// The items of the menu of a box.
	fn box_action(&mut self, ctx: &mut Ctx, occ: &Occ) {
		match event_menu() {
			Some(BoxAction::Delete) => {
				if delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, occ) {
					self.selected_id = None;
				}
			}
			Some(BoxAction::Note) => edit_note(ctx.store, occ),
			Some(BoxAction::Alarm) => edit_alarm(ctx.store, occ),
			Some(BoxAction::Edit) => event_dialog::edit(ctx, Some(occ.event.clone()), occ.day, None, ""),
			None => {}
		}
	}

	/// Sets the target of `d` for the mouse at window point (x, y), local (lx, ly): a calendar day, or an
	/// outline in the grid, which it returns.
	fn update_drag(&self, d: &mut Drag, x: i32, y: i32, _lx: i32, ly: i32) -> Option<Rect> {
		let e = &d.occ.event;
		d.drop_day = if d.resize { None } else { self.calendar_day(x, y) };
		if d.drop_day.is_some() || d.header {
			return None;
		}
		// The grid point, even when the drag started on the header.
		let gy = y - self.grid.y() + self.scroller.offset();
		let _ = ly;
		let w = self.grid.w();
		if d.resize {
			d.new_end = (e.start + 30).max(round_half(min_at(gy))).min(1440);
			Some(outline_box(e.start, d.new_end, w))
		} else if !e.untimed {
			let dur = e.end - e.start;
			d.new_start = round_half(min_at(gy - d.grab)).max(0).min(1440 - dur);
			Some(outline_box(d.new_start, d.new_start + dur, w))
		} else {
			None
		}
	}

	// The lists of the right pane.

	fn pane_mouse(&mut self, ctx: &mut Ctx, m: Mouse) {
		let address = self.pane.borrow().address;
		let list = if address { &self.addr_list } else { &self.todo_list };
		let (lx, ly) = (m.x - list.x(), m.y - list.y());
		match m.kind {
			MouseKind::Press => {
				let row = list.clone().cursor2rowcol().map_or(-1, |(_, r, _, _)| r);
				self.pane_press = Some((lx, ly, row, false));
			}
			MouseKind::Drag => {
				let Some((px, py, row, dragging)) = self.pane_press else {
					return;
				};
				if !dragging && (lx - px).abs().max((ly - py).abs()) <= 4 {
					return;
				}
				self.pane_press = Some((px, py, row, true));
				// A To Do item or an address dragged to the Day grid becomes an event: the outline shows it.
				let over = self.drop_target(m.x, m.y);
				self.model.borrow_mut().drop_box = over
					.filter(|(header, _)| !header)
					.map(|(_, gy)| outline_box(drop_min(gy), drop_min(gy) + 60, self.grid.w()));
				self.grid.redraw();
			}
			MouseKind::Release => {
				let Some((_, _, row, dragging)) = self.pane_press.take() else {
					return;
				};
				self.model.borrow_mut().drop_box = None;
				self.grid.redraw();
				if row < 0 {
					return;
				}
				if dragging {
					let title = if address {
						self.pane_addrs.get(row as usize).map(event_title_of)
					} else {
						self.pane_todos.get(row as usize).map(|t| t.title.clone())
					};
					if let (Some(title), Some((header, gy))) = (title, self.drop_target(m.x, m.y)) {
						let title = title.trim().to_string();
						if !title.is_empty() {
							self.end_typing_ctx(ctx, true);
							self.end_title_edit_ctx(ctx, true);
							let day = *ctx.day;
							self.add_event(ctx, &title, day, drop_min(gy), header);
						}
					}
					return;
				}
				if address {
					if m.double {
						if let Some(a) = self.pane_addrs.get(row as usize).cloned() {
							crate::address_dialog::edit(ctx, Some(a), UNFILED);
						}
					}
				} else {
					self.todo_click(ctx, row as usize, lx, m.double);
				}
			}
			_ => {}
		}
	}

	/// The header (true) or the grid under window point (x, y), with the grid row, for a drop.
	fn drop_target(&self, x: i32, y: i32) -> Option<(bool, i32)> {
		let inside = |w: &Frame| x >= w.x() && x < w.x() + w.w() && y >= w.y() && y < w.y() + w.h();
		if self.mode != DateMode::Day {
			return None;
		}
		if inside(&self.header) {
			return Some((true, 0));
		}
		inside(&self.grid).then(|| (false, y - self.grid.y() + self.scroller.offset()))
	}

	/// A click on a To Do row of the pane: the check box marks the item done or not done, the priority and the
	/// due date open their menus, and a double click shows the item in the To Do List.
	fn todo_click(&mut self, ctx: &mut Ctx, row: usize, lx: i32, double: bool) {
		let Some(mut t) = self.pane_todos.get(row).cloned() else {
			return;
		};
		let p = self.pane.borrow();
		let (pri, due) = pane_parts(
			&p,
			self.todo_list.w() - 4 - 17 * i32::from(self.todo_list.rows() * ROW_H > self.todo_list.h() - 4),
		);
		drop(p);
		if lx < 20 {
			let done = !t.done;
			mark_done(&mut t, done, ctx.settings, today());
			ctx.store.save_todo(&mut t);
		} else if !double && pri.is_some_and(|(a, b)| lx >= a && lx < b) {
			if let Some(i) = popup(&["1", "2", "3", "4", "5"]) {
				t.priority = i as i32 + 1;
				ctx.store.save_todo(&mut t);
			}
		} else if !double && due.is_some_and(|(a, b)| lx >= a && lx < b) {
			if let Some(pick) = popup(&DUE_NAMES) {
				if let Some(d) = crate::todo::due_for(pick, t.due, today(), ctx.settings) {
					t.due = d;
					ctx.store.save_todo(&mut t);
				}
			}
		} else if double {
			ctx.s.send(Msg::ShowTodo(t.id));
		}
	}
}

impl Drag {
	fn new(occ: Occ, resize: bool, header: bool, grab: i32, origin: (i32, i32)) -> Drag {
		let (s, e) = (occ.event.start, occ.event.end);
		Drag {
			occ,
			resize,
			header,
			grab,
			origin,
			moved: false,
			new_start: s,
			new_end: e,
			drop_day: None,
		}
	}
}

/// The title of an event made by dragging an address to the Day view: the name and the number of its row.
fn event_title_of(a: &Address) -> String {
	let row = row_of(a, false);
	if row.number.is_empty() {
		row.name
	} else {
		format!("{} {}", row.name, row.number)
	}
}

/// The selected row of a single-selection list.
fn records_selected(t: &TableRow, n: usize) -> Option<usize> {
	crate::records::selected_rows(t, n).first().copied()
}

/// Shows a menu of `labels` at the pointer; the index of the pick.
fn popup(labels: &[&str]) -> Option<usize> {
	let mut mb = MenuButton::default();
	crate::draw::style_menu(&mut mb);
	mb.set_type(MenuButtonType::Popup3);
	for l in labels {
		mb.add_choice(l);
	}
	let pick = mb.popup().map(|_| mb.value()).filter(|v| *v >= 0).map(|v| v as usize);
	MenuButton::delete(mb);
	pick
}

/// The events of a list of the right pane: a press and a drag go to the view, which drags a row to the Day
/// grid; the list keeps its own selection of the pressed row.
fn pane_event(t: &mut TableRow, ev: Event, s: Sender<Msg>) -> bool {
	let Some(m) = mouse_of(ev) else {
		return matches!(ev, Event::Focus | Event::Unfocus);
	};
	match m.kind {
		MouseKind::Press => {
			let _ = t.take_focus();
			s.send(Msg::Date(DateMsg::PaneList(m)));
			false
		}
		MouseKind::Drag => {
			s.send(Msg::Date(DateMsg::PaneList(m)));
			true
		}
		MouseKind::Release => {
			s.send(Msg::Date(DateMsg::PaneList(m)));
			false
		}
		_ => false,
	}
}

/// An x range from the left of a row.
type Span = Option<(i32, i32)>;

/// The x ranges of the priority and the due date in a To Do pane row `w` px wide, when they show.
fn pane_parts(p: &PaneModel, w: i32) -> (Span, Span) {
	let pri = p.show_pri.then_some((26, 38));
	let due = p.show_due.then_some((w - 4 - 32 - 45, w - 4 - 32));
	(pri, due)
}

/// One To Do row of the pane, as Palm Desktop shows it after the Show Options: the check box, the priority,
/// the description and the due date column, then the note and private marks. A selected row highlights
/// the description only.
fn draw_pane_todo(
	r: &PaneTodo,
	p: &PaneModel,
	selected: bool,
	(x, y, w, h): (i32, i32, i32, i32),
	icons: (&fltk::image::PngImage, &fltk::image::PngImage),
) {
	let fonts = theme::fonts();
	draw::draw_rect_fill(x, y, w, h, Color::White);
	crate::todo::draw_check(x + 4, y + (h - 13) / 2, r.todo.done);
	draw::set_font(fonts.sans, 11);
	let base = y + (h - draw::height()) / 2 + draw::height() - draw::descent();
	let (pri, due) = pane_parts(p, w);
	let tx = if let Some((a, _)) = pri {
		draw::set_draw_color(Color::Black);
		draw::draw_text(&r.todo.priority.to_string(), x + a, base);
		x + 39
	} else {
		x + 21
	};
	let tail = x + w - 4 - 32 - if p.show_due { 45 } else { 0 };
	let title = fit(&r.todo.title.replace('\n', " "), tail - tx - 4, &text_w);
	if selected {
		draw::draw_rect_fill(tx - 1, y, text_w(&title) + 3, h, SELECT);
	}
	draw::set_draw_color(match (selected, r.overdue) {
		(true, true) => Color::Yellow,
		(true, false) => Color::White,
		(false, true) => Color::Red,
		(false, false) => Color::Black,
	});
	draw::draw_text(&title, tx, base);
	if let Some((a, _)) = due {
		if r.todo.due >= 0 {
			let plain = r.due.trim_end_matches('!');
			// An overdue due date stays black, with a bold black "!", as in Palm Desktop 3.1.1.
			draw::set_draw_color(Color::Black);
			draw::draw_text(plain, x + a, base);
			if plain.len() < r.due.len() {
				let at = x + a + text_w(plain);
				draw::set_font(fonts.sans_bold, 11);
				draw::draw_text("!", at, base);
			}
		}
	}
	let mut ix = x + w - 4 - 32;
	for (on, img) in [(!r.todo.note.is_empty(), icons.0), (r.todo.is_private, icons.1)] {
		if on {
			let mut i = img.clone();
			i.draw(ix, y + (h - i.h()) / 2, i.w(), i.h());
			ix += i.w();
		}
	}
}

/// The first day of the week that opens the first row of the calendar of the month of `day`.
fn calendar_start(day: i64, begins: u32) -> i64 {
	let d = ymd(day);
	date_picker::week_start_of(epoch_day(d.year, d.month, 1), begins)
}

/// The month calendar of the right pane: the day letters on the panel gray, then six weeks. The selected day
/// is filled; today is red and bold.
fn draw_calendar(f: &Frame, p: &PaneModel) {
	let fonts = theme::fonts();
	let (cw, ch) = (f.w() / 7, f.h() / 7);
	draw::draw_rect_fill(f.x(), f.y(), f.w(), f.h(), Color::White);
	draw::draw_rect_fill(f.x(), f.y(), f.w(), ch, PANEL);
	for c in 0..7 {
		draw::set_font(fonts.sans_bold, 11);
		draw::set_draw_color(Color::Black);
		let letter = DOW_LETTER[(c as u32 + p.begins) as usize % 7];
		draw::draw_text2(letter, f.x() + c * cw, f.y(), cw, ch, Align::Center);
	}
	let start = calendar_start(p.day, p.begins);
	let month = ymd(p.day).month;
	for i in 0..42 {
		let d = start + i as i64;
		let (x, y) = (f.x() + (i % 7) * cw, f.y() + (i / 7 + 1) * ch);
		let (sel, today) = (d == p.day, d == p.today);
		let mut color = if ymd(d).month == month {
			Color::Black
		} else {
			Color::from_rgb(128, 128, 128)
		};
		if today {
			color = Color::Red;
		}
		if sel {
			draw::draw_rect_fill(x, y, cw, ch, SELECT);
			if !today {
				color = Color::White;
			}
		}
		draw::set_font(if today { fonts.sans_bold } else { fonts.sans }, 11);
		draw::set_draw_color(color);
		draw::draw_text2(&ymd(d).day.to_string(), x, y, cw, ch, Align::Center);
	}
}

/// Draws the box of `e` in `r`. The box of a title being edited is white, the field lies over its title.
fn paint_box(r: Rect, e: &Ev, m: &DayModel, centered: bool) {
	let selected = Some(e.id) == m.selected;
	if m.editing == Some(e.id) {
		draw_box(r, Fill::White, selected);
	} else {
		paint_event(r, e, selected, centered);
	}
}

/// The untimed rows above the Day grid: one raised cell with the diamond at its middle, a black line under
/// the rows, and a box for each untimed event.
fn draw_header(f: &Frame, m: &DayModel) {
	let (x, y, w, h) = (f.x(), f.y(), f.w(), f.h());
	let _clip = Clip::push(x, y, w, h);
	draw::draw_rect_fill(x, y, w, h, Color::White);
	raised_cell(x, y, TIME_W, h);
	draw_diamond(x + TIME_W / 2, y + h / 2);
	draw::set_draw_color(Color::Black);
	draw::draw_line(x + TIME_W, y + h - 1, x + w, y + h - 1);
	draw::draw_line(x + TIME_W, y, x + TIME_W, y + h);
	let layout = layout_day(&m.occs, w);
	let mut boxes: Vec<_> = layout.iter().filter(|(_, _, header)| *header).collect();
	boxes.sort_by_key(|(i, _, _)| Some(m.occs[*i].event.id) == m.selected);
	for (i, r, _) in boxes {
		let e = &m.occs[*i].event;
		paint_box(r.moved(x, y), e, m, true);
	}
	if let Some((true, r)) = m.typing {
		draw_box(r.moved(x, y), Fill::White, true);
	}
}

/// The hours of the Day view: the time column of raised cells with their labels, the gray hour lines, and
/// the timed boxes, the selected one last so its blue bar is on top.
fn draw_grid(f: &Frame, m: &DayModel) {
	let (x, y, w, h) = (f.x(), f.y(), f.w(), f.h());
	let top = y - m.offset;
	let _clip = Clip::push(x, y, w, h);
	draw::draw_rect_fill(x, y, w, h, Color::White);
	for hour in 0..24 {
		let hy = top + y_of(hour * 60);
		if hy > y + h || hy + 2 * HALF < y {
			continue;
		}
		raised_cell(x, hy, TIME_W, y_of((hour + 1) * 60) - y_of(hour * 60));
		draw::set_draw_color(HOUR_LINE);
		draw::draw_line(x + TIME_W, hy, x + w, hy);
		draw_hour_label(hour * 60, TIME_W, x, hy + HALF, m.h24);
	}
	draw::set_draw_color(HOUR_LINE);
	draw::draw_line(x + TIME_W, top + y_of(24 * 60), x + w, top + y_of(24 * 60));
	draw::set_draw_color(Color::Black);
	draw::draw_line(x + TIME_W, y, x + TIME_W, y + h);
	let layout = layout_day(&m.occs, w);
	let mut boxes: Vec<_> = layout.iter().filter(|(_, _, header)| !*header).collect();
	boxes.sort_by_key(|(i, _, _)| Some(m.occs[*i].event.id) == m.selected);
	for (i, r, _) in boxes {
		let e = &m.occs[*i].event;
		paint_box(r.moved(x, top), e, m, false);
	}
	if let Some((false, r)) = m.typing {
		draw_box(r.moved(x, top), Fill::White, true);
	}
	for r in [m.outline, m.drop_box].into_iter().flatten() {
		draw_outline(r.moved(x, top));
	}
}

/// The length of a tab along the strip: the caption plus the Windows tab padding.
fn tab_len(mode: DateMode) -> i32 {
	draw::set_font(theme::fonts().sans, 11);
	text_w(tab_caption(mode)) + 28
}

fn tab_caption(mode: DateMode) -> &'static str {
	match mode {
		DateMode::Day => "Day",
		DateMode::Week => "Week",
		DateMode::Month => "Month",
	}
}

/// The tabs on the right edge, with their captions turned clockwise and their Alt letter underlined. The
/// selected tab is 4 px longer and starts 2 px higher, so it covers the edge of the body it joins.
fn draw_tabs(f: &Frame, mode: DateMode) {
	let (x, y, h) = (f.x(), f.y(), f.h());
	draw::draw_rect_fill(x, y, f.w(), h, WORKSPACE);
	// The right edge of the body: white at the top, shadow gray, black at the bottom.
	draw::draw_rect_fill(x, y, 1, 1, HIGHLIGHT);
	draw::draw_rect_fill(x, y + 1, 1, h - 2, SHADOW);
	draw::draw_rect_fill(x, y + h - 1, 1, 1, Color::Black);
	draw::draw_rect_fill(x + 1, y, 1, h, Color::Black);
	let mut top = 2;
	let mut placed = Vec::new();
	for m in [DateMode::Day, DateMode::Week, DateMode::Month] {
		let len = tab_len(m);
		placed.push((m, top, len));
		top += len;
	}
	// The selected tab last, so it lies over its neighbors.
	placed.sort_by_key(|(m, _, _)| *m == mode);
	for (m, t, len) in placed {
		let sel = m == mode;
		let (tx, ty, tw, th) = if sel {
			(x, y + t - 2, 24, len + 4)
		} else {
			(x + 1, y + t, 21, len)
		};
		draw::draw_rect_fill(tx, ty, tw - 2, th - 2, PANEL);
		draw::set_draw_color(HIGHLIGHT);
		draw::draw_line(if sel { tx } else { tx + 1 }, ty, tx + tw - 4, ty);
		draw::set_draw_color(SHADOW);
		draw::draw_point(tx + tw - 3, ty);
		draw::draw_line(tx + tw - 2, ty + 1, tx + tw - 2, ty + th - 3);
		draw::draw_line(if sel { tx + 2 } else { tx + 1 }, ty + th - 3, tx + tw - 2, ty + th - 3);
		draw::set_draw_color(Color::Black);
		draw::draw_line(tx + tw - 1, ty + 3, tx + tw - 1, ty + th - 3);
		draw::draw_line(if sel { tx + 2 } else { tx }, ty + th - 2, tx + tw - 2, ty + th - 2);
		let cap = tab_caption(m);
		draw::set_font(theme::fonts().sans, 11);
		let cw = text_w(cap);
		// The caption reads from the top down; its first letter is the Alt key.
		let (cx, cy) = (
			tx + (tw - 2) / 2 - (draw::height() - draw::descent() * 2) / 2,
			ty + (th - 2 - cw) / 2,
		);
		draw::draw_text_angled(-90, cap, cx, cy);
		let lw = text_w(&cap[..1]);
		draw::draw_line(cx - 1, cy, cx - 1, cy + lw - 1);
	}
}
