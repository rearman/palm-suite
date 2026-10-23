//! The Week view, as WeekView.kt: seven day columns with the untimed row above a time grid, and the row of
//! the step buttons, Go to, the title and Today that the Month view shares.

use crate::date::{forward_mouse, Scroller, TypeField, TIME_W};
use crate::date_picker::week_start_of;
use crate::date_shared::{
	apply_alarm_preset, change_occurrence, delete_occurrence, dow_index, draw_box, draw_diamond, draw_hour_label,
	draw_outline, edit_alarm, edit_note, event_menu, marker_at, on_handle, on_resize_bar, paint_event, raised_cell,
	title_x, visible_events, week_begins, week_of_year, work_day, BoxAction, Fill, Marker, Occ, Rect, BAR_W,
	CHANGE_TEXT, HOUR_LINE, MONTH_LONG, SEL_H,
};
use crate::event_dialog;
use crate::msg::{DateMsg, FieldEv, Mouse, MouseKind, Msg};
use crate::platform::today;
use crate::theme::{self, PANEL, SELECT};
use crate::view::Ctx;
use fltk::app::{self, Sender};
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, Color, Cursor, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::prelude::*;
use palm_core::days::ymd;
use palm_core::model::{Event as Ev, DOW_SHORT};
use std::cell::RefCell;
use std::rc::Rc;

const HOUR_H: i32 = 38;
const HEAD_H: i32 = 20;
const UNTIMED_LINE: i32 = 19;
const MIN_UNTIMED_H: i32 = 20;
const MIN_OF_DAY: i32 = 1440;
/// The height of the navigation row.
pub const NAV_H: i32 = 28;

/// The navigation row of the Week and Month views: back, Go to and forward at the left, the title, and
/// Today at the right. Returns the title.
pub fn nav(x: i32, y: i32, w: i32, s: Sender<Msg>) -> Frame {
	// The row is its own group with the title as its resizable widget, so a wider window widens the title
	// and the buttons keep the 3.1.1 sizes: arrows 18x20, Go to and Today 75x20.
	let row = Group::new(x, y, w, 26, None);
	let mut back = crate::draw::arrow(x + 2, y + 4, 18, 20, false);
	back.set_callback(move |_| s.send(Msg::Date(DateMsg::Step(-1))));
	let mut go = Button::new(x + 20, y + 4, 75, 20, "&Go to");
	go.set_callback(move |_| s.send(Msg::Date(DateMsg::GoTo)));
	let mut fwd = crate::draw::arrow(x + 95, y + 4, 18, 20, true);
	fwd.set_callback(move |_| s.send(Msg::Date(DateMsg::Step(1))));
	let mut today_b = Button::new(x + w - 77, y + 4, 75, 20, "T&oday");
	today_b.set_callback(move |_| s.send(Msg::Date(DateMsg::Today)));
	let mut title = Frame::new(x + 117, y + 4, w - 198, 20, None);
	title.set_frame(FrameType::BorderBox);
	title.set_color(Color::White);
	title.set_label_font(theme::fonts().arial_bold);
	title.set_label_size(13);
	row.resizable(&title);
	row.end();
	title
}

/// The length of an event in minutes, 30 when it has no end.
fn dur_of(e: &Ev) -> i32 {
	if e.end > e.start {
		e.end - e.start
	} else {
		30
	}
}

/// The height of a box for an event of `min` minutes, as the grid draws it.
fn box_h(min: i32) -> i32 {
	(min * HOUR_H / 60 - SEL_H).max(12)
}

/// `min` rounded to the nearest 30 minutes.
fn round30(min: i32) -> i32 {
	((min as f64 / 30.0).round() * 30.0) as i32
}

/// A press on a box body (move) or on the blue bar of the selected box (resize), until release.
struct Drag {
	occ: Occ,
	rect: Rect,
	origin: (i32, i32),
	resize: bool,
	dur: i32,
	moved: bool,
	new_day: i64,
	new_start: i32,
	new_end: i32,
}

/// The field of a new event: its column, its hour (None for the untimed row) and its untimed line.
struct Typing {
	field: TypeField,
	day: i64,
	col: usize,
	min: Option<i32>,
	line: i32,
}

/// What the week grid and header draw.
#[derive(Default)]
struct WeekModel {
	start: i64,
	day: i64,
	today: i64,
	begins: u32,
	days: Vec<Vec<Occ>>,
	selected: Option<(i64, i64)>,
	untimed_h: i32,
	typing: Option<Rect>,
	outline: Option<Rect>,
	offset: i32,
	h24: bool,
}

impl WeekModel {
	fn hour_y(&self, hour: i32) -> i32 {
		self.untimed_h + hour * HOUR_H
	}

	fn line_y(&self, min: i32) -> i32 {
		self.untimed_h + min * HOUR_H / 60
	}

	fn min_of_y(&self, y: i32) -> i32 {
		(y - self.untimed_h) * 60 / HOUR_H
	}

	/// The event boxes at grid width `w`; a later box in the list is drawn on top.
	fn hits(&self, w: i32) -> Vec<(Rect, Occ)> {
		let cw = col_w(w);
		let mut out = Vec::new();
		for (i, day) in self.days.iter().enumerate() {
			let x = TIME_W + i as i32 * cw + 1;
			let mut untimed = 0;
			for o in day {
				let e = &o.event;
				if e.untimed {
					out.push((
						Rect::new(x, 2 + untimed * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2),
						o.clone(),
					));
					untimed += 1;
				} else {
					// A record without an end gets 30 minutes.
					let s = e.start.clamp(0, MIN_OF_DAY - 1);
					let end = if e.end > s {
						e.end.min(MIN_OF_DAY)
					} else {
						(s + 30).min(MIN_OF_DAY)
					};
					let y = self.untimed_h + s * HOUR_H / 60;
					out.push((Rect::new(x, y, cw - 3, box_h(end - s)), o.clone()));
				}
			}
		}
		out
	}
}

fn col_w(w: i32) -> i32 {
	((w - TIME_W) / 7).max(1)
}

/// The shift of content row `y` from the grid's top: the untimed strip stays put, the hours move by `offset`.
fn row_shift(untimed_h: i32, offset: i32, y: i32) -> i32 {
	if y < untimed_h {
		0
	} else {
		-offset
	}
}

/// The content row under row `sy` of the grid, the inverse of `row_shift`.
fn content_row(untimed_h: i32, offset: i32, sy: i32) -> i32 {
	if sy < untimed_h {
		sy
	} else {
		sy + offset
	}
}

pub struct WeekView {
	pub root: Group,
	title: Frame,
	header: Frame,
	grid: Frame,
	scroller: Scroller,
	model: Rc<RefCell<WeekModel>>,
	selected: Option<Occ>,
	typing: Option<Typing>,
	title_edit: Option<(TypeField, Occ)>,
	drag: Option<Drag>,
	suppress_click: bool,
	s: Sender<Msg>,
}

impl WeekView {
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>) -> WeekView {
		let mut root = Group::new(x, y, w, h, None);
		let title = nav(x, y, w, s);
		let gy = y + NAV_H;
		let mut edge = Frame::new(x, gy, w, h - NAV_H, None);
		edge.set_frame(FrameType::BorderBox);
		let sb = app::scrollbar_size();
		let mut header = Frame::new(x + 1, gy + 1, w - 2 - sb, HEAD_H, None);
		let mut grid = Frame::new(x + 1, gy + 1 + HEAD_H, w - 2 - sb, h - NAV_H - 2 - HEAD_H, None);
		let scroller = Scroller::new(x + w - 1 - sb, gy + 1 + HEAD_H, h - NAV_H - 2 - HEAD_H, s, || {
			Msg::Date(DateMsg::Scrolled)
		});
		let model = Rc::new(RefCell::new(WeekModel {
			untimed_h: MIN_UNTIMED_H,
			h24: crate::platform::uses_24_hours(),
			..WeekModel::default()
		}));
		{
			let m = model.clone();
			header.draw(move |f| draw_header(f, &m.borrow()));
			let m = model.clone();
			grid.draw(move |f| draw_grid(f, &m.borrow()));
		}
		forward_mouse(&mut header, s, |m| Msg::Date(DateMsg::WeekHeader(m)));
		forward_mouse(&mut grid, s, |m| Msg::Date(DateMsg::WeekGrid(m)));
		// A resize changes the height of the hours: the range and the bar follow at the next sync.
		grid.resize_callback(move |_, _, _, _, _| s.send(Msg::Date(DateMsg::Scrolled)));
		let grow = Frame::new(x + 200, gy + 40, w - 300, h - NAV_H - 80, None);
		root.resizable(&grow);
		root.end();
		root.hide();
		WeekView {
			root,
			title,
			header,
			grid,
			scroller,
			model,
			selected: None,
			typing: None,
			title_edit: None,
			drag: None,
			suppress_click: false,
			s,
		}
	}

	pub fn focus(&mut self) {
		let _ = self.grid.take_focus();
	}

	pub fn field(&self) -> Option<fltk::input::Input> {
		self.typing
			.as_ref()
			.map(|t| t.field.input.clone())
			.or_else(|| self.title_edit.as_ref().map(|t| t.0.input.clone()))
	}

	/// Scrolls so that `hour` opens the hours area. The untimed strip does not scroll, so hour 0 is at offset 0.
	pub fn scroll_to_hour(&mut self, hour: i32) {
		self.scroller.scroll_later(hour * HOUR_H);
	}

	/// The occurrence selected in the grid, or None when it is no longer shown.
	pub fn selected_occ(&self) -> Option<Occ> {
		let s = self.selected.as_ref()?;
		self.find(s.event.id, s.day)
	}

	fn find(&self, id: i64, day: i64) -> Option<Occ> {
		self.model
			.borrow()
			.days
			.iter()
			.flatten()
			.find(|o| o.event.id == id && o.day == day)
			.cloned()
	}

	pub fn refresh(&mut self, ctx: &mut Ctx) {
		let begins = week_begins(ctx.settings);
		let start = week_start_of(*ctx.day, begins);
		// A new week saves the field first, so its text is not lost.
		if self.typing.is_some() && self.model.borrow().start != start {
			self.end_typing(ctx, true);
		}
		let new_week = self.model.borrow().start != start;
		let days: Vec<Vec<Occ>> = (0..7)
			.map(|i| visible_events(ctx.store, start + i, ctx.show_private))
			.collect();
		let most = days
			.iter()
			.map(|d| d.iter().filter(|o| o.event.untimed).count())
			.max()
			.unwrap_or(0) as i32;
		let mut untimed_h = (most * UNTIMED_LINE + 4).max(MIN_UNTIMED_H);
		// An open untimed field keeps its line.
		if let Some(t) = self.typing.as_ref().filter(|t| t.min.is_none()) {
			untimed_h = untimed_h.max((t.line + 1) * UNTIMED_LINE + 4);
		}
		{
			let mut m = self.model.borrow_mut();
			m.start = start;
			m.day = *ctx.day;
			m.today = today();
			m.begins = begins;
			m.days = days;
			m.untimed_h = untimed_h;
		}
		self.selected = self.selected.as_ref().and_then(|s| self.find(s.event.id, s.day));
		let long = |d: i64| {
			let y = ymd(d);
			format!("{} {}, {}", MONTH_LONG[y.month as usize - 1], y.day, y.year)
		};
		self.title.set_label(&format!(
			"{} - {} Week {}",
			long(start),
			long(start + 6),
			week_of_year(start, begins == 1)
		));
		// A new week opens at the work-day start hour, as Palm Desktop does.
		if new_week {
			self.scroll_to_hour(work_day(ctx.settings));
		}
		self.sync();
	}

	/// Sets the range and the bar of the hours from the grid size and the untimed strip.
	fn sync(&mut self) {
		let untimed_h = self.model.borrow().untimed_h;
		// The hours scroll under the untimed strip, so their bar starts below it.
		let hours_h = (self.grid.h() - untimed_h).max(1);
		let total = self.model.borrow().hour_y(24) - untimed_h + 1;
		self.scroller.set_range(hours_h, total);
		let b = &mut self.scroller.bar;
		b.resize(b.x(), self.grid.y() + untimed_h, b.w(), hours_h);
		let typing = self.typing.as_ref().map(|t| {
			let m = self.model.borrow();
			let cw = col_w(self.grid.w());
			let x = TIME_W + t.col as i32 * cw + 1;
			match t.min {
				None => Rect::new(x, 2 + t.line * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2),
				Some(min) => Rect::new(x, m.untimed_h + min * HOUR_H / 60, cw - 3, box_h(60)),
			}
		});
		{
			let mut m = self.model.borrow_mut();
			m.offset = self.scroller.offset();
			m.selected = self.selected.as_ref().map(|o| (o.event.id, o.day));
			m.typing = typing;
		}
		if let (Some(t), Some(r)) = (self.typing.as_mut(), typing) {
			let top = self.grid.y() + row_shift(untimed_h, self.scroller.offset(), r.y);
			t.field
				.input
				.resize(self.grid.x() + r.x + 3, top + r.y + 1, r.w - BAR_W - 4, 17);
		}
		self.header.redraw();
		self.grid.redraw();
		self.scroller.bar.redraw();
	}

	pub fn scrolled(&mut self) {
		self.sync();
		self.root.redraw();
	}

	fn set_cursor(&self, c: Cursor) {
		if let Some(mut w) = self.grid.window() {
			w.set_cursor(c);
		}
	}

	/// The box of the selected timed event, local to the grid.
	fn sel_rect(&self) -> Option<Rect> {
		let sel = self.selected_occ()?;
		self.model
			.borrow()
			.hits(self.grid.w())
			.into_iter()
			.find(|(_, o)| *o == sel && !o.event.untimed)
			.map(|(r, _)| r)
	}

	fn hit_at(&self, lx: i32, ly: i32) -> Option<(Rect, Occ)> {
		self.model
			.borrow()
			.hits(self.grid.w())
			.into_iter()
			.rev()
			.find(|(r, _)| r.contains(lx, ly))
	}

	pub fn header_mouse(&mut self, ctx: &mut Ctx, m: Mouse) {
		if m.kind != MouseKind::Press || m.right {
			return;
		}
		let lx = m.x - self.header.x();
		if lx < TIME_W {
			return;
		}
		let start = self.model.borrow().start;
		*ctx.day = start + ((lx - TIME_W) / col_w(self.header.w())).clamp(0, 6) as i64;
	}

	pub fn grid_mouse(&mut self, ctx: &mut Ctx, m: Mouse) {
		let untimed_h = self.model.borrow().untimed_h;
		let lx = m.x - self.grid.x();
		let ly = content_row(untimed_h, self.scroller.offset(), m.y - self.grid.y());
		match m.kind {
			MouseKind::Wheel(n) => {
				self.scroller.wheel(n);
				self.scrolled();
			}
			MouseKind::Move => {
				let on = self.sel_rect().is_some_and(|r| on_resize_bar(r, lx, ly));
				self.set_cursor(if on { Cursor::NS } else { Cursor::Default });
			}
			MouseKind::Press => {
				self.suppress_click = false;
				let _ = self.grid.take_focus();
				if lx < TIME_W {
					return;
				}
				if m.right {
					// The menu of a box. Empty space has no menu, as in Palm Desktop.
					self.close_saving(ctx);
					let hit = self.hit_at(lx, ly);
					self.selected = hit.as_ref().map(|(_, o)| o.clone());
					self.sync();
					if let Some((_, occ)) = hit {
						self.box_action(ctx, &occ);
					}
					return;
				}
				if let (Some(r), Some(occ)) = (self.sel_rect(), self.selected_occ()) {
					if on_resize_bar(r, lx, ly) {
						self.drag = Some(Drag::new(occ, r, (lx, ly), true));
						return;
					}
				}
				let Some((r, occ)) = self.hit_at(lx, ly) else { return };
				if on_handle(r, lx, ly) || marker_at(r, &occ.event, lx, ly, occ.event.untimed).is_some() {
					return;
				}
				self.selected = Some(occ.clone());
				self.drag = Some(Drag::new(occ, r, (lx, ly), false));
				self.sync();
			}
			MouseKind::Drag => {
				let Some(mut d) = self.drag.take() else { return };
				if d.resize {
					self.set_cursor(Cursor::NS);
				}
				if !d.moved && (lx - d.origin.0).abs() <= 4 && (ly - d.origin.1).abs() <= 4 {
					self.drag = Some(d);
					return;
				}
				d.moved = true;
				let outline = self.update_drag(&mut d, lx, ly);
				self.model.borrow_mut().outline = Some(outline);
				self.drag = Some(d);
				self.grid.redraw();
			}
			MouseKind::Release => {
				let on = self.sel_rect().is_some_and(|r| on_resize_bar(r, lx, ly));
				self.set_cursor(if on { Cursor::NS } else { Cursor::Default });
				if let Some(d) = self.drag.take() {
					self.model.borrow_mut().outline = None;
					self.grid.redraw();
					if d.moved {
						self.suppress_click = true;
						self.save_drag(ctx, &d);
						return;
					}
				}
				if self.suppress_click || m.right {
					self.suppress_click = false;
					return;
				}
				self.click(ctx, lx, ly, m.double);
			}
		}
	}

	/// Moves the outline of the dragged box to the slot under the mouse, and returns it.
	fn update_drag(&self, d: &mut Drag, lx: i32, ly: i32) -> Rect {
		let m = self.model.borrow();
		let cw = col_w(self.grid.w());
		let col = ((lx - TIME_W) / cw).clamp(0, 6);
		if d.resize {
			let start = d.occ.event.start;
			d.new_end = round30(m.min_of_y(ly)).max(start + 30).min(MIN_OF_DAY);
			let top = m.line_y(start);
			Rect::new(
				d.rect.x,
				top + 1,
				d.rect.w,
				m.line_y(d.new_end).max(top + HOUR_H / 2) - top - 2,
			)
		} else if d.occ.event.untimed {
			d.new_day = m.start + col as i64;
			Rect::new(TIME_W + col * cw + 1, d.rect.y, cw - 3, d.rect.h)
		} else {
			d.new_day = m.start + col as i64;
			let grab = d.origin.1 - d.rect.y;
			d.new_start = round30(m.min_of_y(ly - grab)).max(0).min(MIN_OF_DAY - d.dur);
			d.new_end = d.new_start + d.dur;
			let top = m.line_y(d.new_start);
			Rect::new(
				d.rect.x + lx - d.origin.0,
				top + 1,
				d.rect.w,
				m.line_y(d.new_end) - top - 2,
			)
		}
	}

	/// Saves a moved or resized box.
	fn save_drag(&mut self, ctx: &mut Ctx, d: &Drag) {
		let (occ, e) = (&d.occ, &d.occ.event);
		if d.resize {
			if d.new_end != e.end {
				change_occurrence(ctx.store, occ, CHANGE_TEXT, |x| x.end = d.new_end);
			}
		} else if e.untimed {
			if d.new_day != occ.day {
				change_occurrence(ctx.store, occ, CHANGE_TEXT, |x| x.date = d.new_day);
			}
		} else if d.new_day != occ.day || d.new_start != e.start {
			change_occurrence(ctx.store, occ, CHANGE_TEXT, |x| {
				x.date = d.new_day;
				x.start = d.new_start;
				x.end = d.new_start + d.dur;
			});
		}
	}

	/// A click on the grid: a new event on empty space, else the box is selected and acts.
	fn click(&mut self, ctx: &mut Ctx, lx: i32, ly: i32, double: bool) {
		if lx < TIME_W {
			return;
		}
		if double {
			self.end_title_edit(ctx, false);
			self.end_typing(ctx, false);
		} else {
			self.close_saving(ctx);
		}
		let col = ((lx - TIME_W) / col_w(self.grid.w())).clamp(0, 6) as usize;
		let start = self.model.borrow().start;
		*ctx.day = start + col as i64;
		match self.hit_at(lx, ly) {
			None => {
				self.selected = None;
				let untimed_h = self.model.borrow().untimed_h;
				let min = (ly >= untimed_h).then(|| ((ly - untimed_h) / HOUR_H).clamp(0, 23) * 60);
				if double {
					let day = *ctx.day;
					event_dialog::edit(ctx, None, day, min, "");
					return;
				}
				let line = self.model.borrow().days[col].iter().filter(|o| o.event.untimed).count() as i32;
				self.start_typing(ctx, col, min, line);
			}
			Some((r, occ)) => {
				let marker = marker_at(r, &occ.event, lx, ly, occ.event.untimed);
				self.selected = Some(occ.clone());
				self.sync();
				if double && on_handle(r, lx, ly) {
					event_dialog::edit(ctx, Some(occ.event), occ.day, None, "");
				} else if double {
					self.start_title_edit(r, occ);
				} else if marker == Some(Marker::Alarm) {
					edit_alarm(ctx.store, &occ);
				} else if marker == Some(Marker::Note) {
					edit_note(ctx.store, &occ);
				}
			}
		}
	}

	fn box_action(&mut self, ctx: &mut Ctx, occ: &Occ) {
		match event_menu() {
			Some(BoxAction::Delete) => {
				delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, occ);
			}
			Some(BoxAction::Note) => edit_note(ctx.store, occ),
			Some(BoxAction::Alarm) => edit_alarm(ctx.store, occ),
			Some(BoxAction::Edit) => event_dialog::edit(ctx, Some(occ.event.clone()), occ.day, None, ""),
			None => {}
		}
	}

	/// Closes both fields; a new event and a changed title are saved.
	pub fn close_saving(&mut self, ctx: &mut Ctx) {
		self.end_title_edit(ctx, true);
		self.end_typing(ctx, true);
	}

	fn start_typing(&mut self, ctx: &mut Ctx, col: usize, min: Option<i32>, line: i32) {
		self.end_typing(ctx, true);
		let start = self.model.borrow().start;
		if min.is_none() {
			let mut m = self.model.borrow_mut();
			m.untimed_h = m.untimed_h.max((line + 1) * UNTIMED_LINE + 4);
		}
		let field = TypeField::open(&self.root, 0, 0, 50, "", self.s);
		self.typing = Some(Typing {
			field,
			day: start + col as i64,
			col,
			min,
			line,
		});
		self.sync();
	}

	/// Closes the field of a new event. With `save`, a title that is not empty becomes an event, selected.
	fn end_typing(&mut self, ctx: &mut Ctx, save: bool) {
		let Some(t) = self.typing.take() else { return };
		let title = t.field.text();
		let (day, min) = (t.day, t.min);
		t.field.close();
		self.sync();
		if !save || title.is_empty() {
			return;
		}
		let mut e = Ev {
			title,
			date: day,
			untimed: min.is_none(),
			start: min.unwrap_or(8 * 60),
			end: min.map_or(9 * 60, |m| m + 60),
			..Ev::default()
		};
		apply_alarm_preset(&mut e, ctx.settings);
		ctx.store.save_event(&mut e);
		self.selected = Some(Occ { event: e, day });
	}

	/// Opens the title of the box `occ` at `r` for editing, over its title area.
	fn start_title_edit(&mut self, r: Rect, occ: Occ) {
		let x = title_x(r, &occ.event);
		let shift = row_shift(self.model.borrow().untimed_h, self.scroller.offset(), r.y);
		let top = self.grid.y() + shift;
		let mut field = TypeField::open(
			&self.root,
			self.grid.x() + x,
			top + r.y + 1,
			r.x + r.w - BAR_W - x,
			&occ.event.title,
			self.s,
		);
		let n = field.input.value().len() as i32;
		let _ = field.input.set_position(n);
		let _ = field.input.set_mark(0);
		self.title_edit = Some((field, occ));
	}

	/// Closes the title field. With `save`, a changed title that is not empty is saved.
	fn end_title_edit(&mut self, ctx: &mut Ctx, save: bool) {
		let Some((field, occ)) = self.title_edit.take() else {
			return;
		};
		let text = field.text();
		field.close();
		self.grid.redraw();
		if save && !text.is_empty() && text != occ.event.title {
			change_occurrence(ctx.store, &occ, CHANGE_TEXT, |e| e.title = text);
		}
	}

	pub fn field_event(&mut self, ctx: &mut Ctx, f: FieldEv) {
		match f {
			FieldEv::Enter => self.close_saving(ctx),
			FieldEv::Escape => {
				self.end_title_edit(ctx, false);
				self.end_typing(ctx, false);
			}
			FieldEv::Lost => {
				let focused = app::focus().map(|w| w.as_widget_ptr());
				if self
					.typing
					.as_ref()
					.is_some_and(|t| Some(t.field.input.as_widget_ptr()) != focused)
				{
					self.end_typing(ctx, true);
				}
				if self
					.title_edit
					.as_ref()
					.is_some_and(|t| Some(t.0.input.as_widget_ptr()) != focused)
				{
					self.end_title_edit(ctx, true);
				}
			}
			FieldEv::Double => {
				// A double click in the field opens the event dialog, as on an empty hour.
				if let Some(t) = self.typing.as_ref() {
					let (day, min) = (t.day, t.min);
					self.end_typing(ctx, false);
					event_dialog::edit(ctx, None, day, min, "");
				}
			}
		}
	}
}

impl Drag {
	fn new(occ: Occ, rect: Rect, origin: (i32, i32), resize: bool) -> Drag {
		let dur = dur_of(&occ.event);
		let (day, start) = (occ.day, occ.event.start);
		Drag {
			occ,
			rect,
			origin,
			resize,
			dur,
			moved: false,
			new_day: day,
			new_start: start,
			new_end: start + dur,
		}
	}
}

/// The day names over the columns on the panel gray, with no lines between them. The selected day fills blue
/// with white text; today is red and bold, unless it is the selected day.
fn draw_header(f: &Frame, m: &WeekModel) {
	let fonts = theme::fonts();
	let (x, y, w, h) = (f.x(), f.y(), f.w(), f.h());
	draw::draw_rect_fill(x, y, w, h, PANEL);
	let cw = col_w(w);
	for i in 0..7 {
		let cx = x + TIME_W + i * cw;
		let day = m.start + i as i64;
		let (sel, today) = (day == m.day, day == m.today);
		if sel {
			draw::draw_rect_fill(cx, y, cw, h, SELECT);
		}
		let text = format!("{} {}", DOW_SHORT[dow_index(i as usize, m.begins)], ymd(day).day);
		draw::set_font(if today { fonts.sans_bold } else { fonts.sans }, 11);
		draw::set_draw_color(if sel {
			Color::White
		} else if today {
			Color::Red
		} else {
			Color::Black
		});
		draw::draw_text2(&text, cx, y, cw, h, Align::Center);
	}
	draw::draw_line(x + TIME_W, y + h - 1, x + w, y + h - 1);
}

/// The grid. The untimed strip stays at the top; the hours scroll below it, clipped to their area, with the
/// time column of raised cells, the day column lines, and the boxes, the selected one last.
fn draw_grid(f: &Frame, m: &WeekModel) {
	let (x, y, w, h) = (f.x(), f.y(), f.w(), f.h());
	let top = y - m.offset;
	let hours_y = y + m.untimed_h;
	let cw = col_w(w);
	let _clip = crate::draw::Clip::push(x, y, w, h);
	draw::draw_rect_fill(x, y, w, h, Color::White);
	let mut hits = m.hits(w);
	hits.sort_by_key(|(_, o)| Some((o.event.id, o.day)) == m.selected);
	{
		let _hours = crate::draw::Clip::push(x, hours_y, w, (h - m.untimed_h).max(0));
		draw::set_draw_color(HOUR_LINE);
		for hour in 1..=24 {
			let hy = top + m.hour_y(hour);
			draw::draw_line(x, hy, x + w, hy);
		}
		for hour in 0..24 {
			let hy = top + m.hour_y(hour);
			if hy > y + h || hy + HOUR_H < y {
				continue;
			}
			raised_cell(x, hy, TIME_W, HOUR_H);
			draw_hour_label(hour * 60, TIME_W, x, hy + HOUR_H / 2, m.h24);
		}
		draw::set_draw_color(Color::Black);
		for i in 0..=7 {
			draw::draw_line(
				x + TIME_W + i * cw,
				top + m.hour_y(0),
				x + TIME_W + i * cw,
				top + m.hour_y(24),
			);
		}
		for (r, o) in hits.iter().filter(|(_, o)| !o.event.untimed) {
			let sel = Some((o.event.id, o.day)) == m.selected;
			paint_event(r.moved(x, top), &o.event, sel, false);
		}
		if let Some(r) = m.typing.filter(|r| r.y >= m.untimed_h) {
			draw_box(r.moved(x, top), Fill::White, true);
		}
		if let Some(r) = m.outline.filter(|r| r.y >= m.untimed_h) {
			draw_outline(r.moved(x, top));
		}
	}
	// The untimed strip: its own time cell and day column lines, then its rows, none of it scrolled.
	{
		let _strip = crate::draw::Clip::push(x, y, w, m.untimed_h);
		raised_cell(x, y, TIME_W, m.untimed_h);
		draw_diamond(x + TIME_W / 2, y + m.untimed_h / 2);
		draw::set_draw_color(Color::Black);
		for i in 0..=7 {
			draw::draw_line(x + TIME_W + i * cw, y, x + TIME_W + i * cw, hours_y);
		}
		for (r, o) in hits.iter().filter(|(_, o)| o.event.untimed) {
			let sel = Some((o.event.id, o.day)) == m.selected;
			paint_event(r.moved(x, y), &o.event, sel, true);
		}
		if let Some(r) = m.typing.filter(|r| r.y < m.untimed_h) {
			draw_box(r.moved(x, y), Fill::White, true);
		}
		if let Some(r) = m.outline.filter(|r| r.y < m.untimed_h) {
			draw_outline(r.moved(x, y));
		}
	}
	// The first hour line is black: it parts the untimed strip from the hours, and stays put.
	draw::set_draw_color(Color::Black);
	draw::draw_line(x + TIME_W, hours_y, x + w, hours_y);
}
