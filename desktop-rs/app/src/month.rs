//! The Month view, as MonthView.kt: a grid of six weeks, with the events of each day in its cell.

use crate::date_picker::week_start_of;
use crate::date_shared::{dow_index, time_label, visible_events, week_begins, Occ, MONTH_LONG};
use crate::draw::{fit, text_w, Dev};
use crate::event_dialog;
use crate::msg::{DateMode, DateMsg, Mouse, MouseKind, Msg};
use crate::platform::today;
use crate::theme::{self, icon, PANEL, SELECT};
use crate::view::Ctx;
use crate::week::{nav, NAV_H};
use fltk::app::{self, Sender};
use fltk::draw;
use fltk::enums::{Align, Color, Event, EventState};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::menu::{MenuButton, MenuButtonType, MenuFlag};
use fltk::prelude::*;
use palm_core::days::{epoch_day, ymd};
use palm_core::model::DOW_LONG;
use std::cell::RefCell;
use std::rc::Rc;

const NAME_H: i32 = 18;
const NUM_PAD: i32 = 2;
const WEEKS: i32 = 6;
/// The triangle that marks a cell with more events than fit.
const MORE_W: i32 = 7;
const MORE_H: i32 = 4;

/// What the month grid draws.
#[derive(Default)]
struct MonthModel {
	start: i64,
	day: i64,
	today: i64,
	begins: u32,
	days: Vec<Vec<Occ>>,
	h24: bool,
}

pub struct MonthView {
	pub root: Group,
	title: Frame,
	grid: Frame,
	model: Rc<RefCell<MonthModel>>,
}

impl MonthView {
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>) -> MonthView {
		let mut root = Group::new(x, y, w, h, None);
		let title = nav(x, y, w, s);
		let mut grid = Frame::new(x, y + NAV_H, w, h - NAV_H, None);
		let model = Rc::new(RefCell::new(MonthModel {
			h24: crate::platform::uses_24_hours(),
			..MonthModel::default()
		}));
		{
			let m = model.clone();
			grid.draw(move |f| draw_month(f, &m.borrow()));
		}
		// A printable key starts a new event on the selected day, titled with the key.
		let mut g = grid.clone();
		let inner = move |_: &mut Frame, ev: Event| -> bool {
			if ev == Event::KeyDown {
				let state = app::event_state();
				let text = app::event_text();
				let mut chars = text.chars();
				if let (Some(c), None) = (chars.next(), chars.next()) {
					if !state.contains(EventState::Ctrl) && !state.contains(EventState::Alt) && !c.is_control() {
						s.send(Msg::Date(DateMsg::MonthKey(c)));
						return true;
					}
				}
				return false;
			}
			if let Some(m) = crate::date::mouse_of(ev) {
				s.send(Msg::Date(DateMsg::MonthGrid(m)));
				return true;
			}
			matches!(ev, Event::Focus | Event::Unfocus)
		};
		g.handle(inner);
		root.resizable(&grid);
		root.end();
		root.hide();
		MonthView {
			root,
			title,
			grid,
			model,
		}
	}

	pub fn focus(&mut self) {
		let _ = self.grid.take_focus();
	}

	pub fn refresh(&mut self, ctx: &mut Ctx) {
		let d = ymd(*ctx.day);
		let begins = week_begins(ctx.settings);
		let start = week_start_of(epoch_day(d.year, d.month, 1), begins);
		{
			let mut m = self.model.borrow_mut();
			m.start = start;
			m.day = *ctx.day;
			m.today = today();
			m.begins = begins;
			m.days = (0..7 * WEEKS as i64)
				.map(|i| visible_events(ctx.store, start + i, ctx.show_private))
				.collect();
		}
		self.title
			.set_label(&format!("{}, {}", MONTH_LONG[d.month as usize - 1], d.year));
		self.grid.redraw();
	}

	/// The index of the cell at window point (x, y), or None on the name row.
	fn cell_at(&self, x: i32, y: i32) -> Option<i64> {
		let (lx, ly) = (x - self.grid.x(), y - self.grid.y());
		let (cw, ch) = (self.grid.w() / 7, (self.grid.h() - NAME_H) / WEEKS);
		if ly < NAME_H || cw == 0 || ch == 0 {
			return None;
		}
		let row = ((ly - NAME_H) / ch).min(WEEKS - 1);
		let col = (lx / cw).min(6);
		Some((row * 7 + col) as i64)
	}

	/// A press selects the day under it; a right press shows the cell menu; a double click opens the Day view,
	/// which the caller shows.
	pub fn mouse(&mut self, ctx: &mut Ctx, m: Mouse) -> Option<DateMode> {
		if m.kind != MouseKind::Press {
			return None;
		}
		let _ = self.grid.take_focus();
		let i = self.cell_at(m.x, m.y)?;
		let day = self.model.borrow().start + i;
		*ctx.day = day;
		if m.right {
			match cell_menu() {
				Some(0) => return Some(DateMode::Day),
				Some(1) => return Some(DateMode::Week),
				Some(_) => event_dialog::edit(ctx, None, day, None, ""),
				None => {}
			}
			return None;
		}
		m.double.then_some(DateMode::Day)
	}
}

/// Shows the Month cell menu at the pointer; the index of the pick.
fn cell_menu() -> Option<usize> {
	let mut mb = MenuButton::default();
	mb.set_type(MenuButtonType::Popup3);
	for l in ["&Day View", "&Week View", "&New Event..."] {
		mb.add_choice(l);
	}
	if let Some(mut item) = mb.at(1) {
		item.set_flag(MenuFlag::MenuDivider);
	}
	crate::draw::style_menu(&mut mb);
	let pick = mb.popup().map(|_| mb.value()).filter(|v| *v >= 0).map(|v| v as usize);
	MenuButton::delete(mb);
	pick
}

/// The text of an event line: an asterisk for an untimed event, the start time for a timed one.
fn line_text(o: &Occ, h24: bool) -> String {
	if o.event.untimed {
		format!("* {}", o.event.title)
	} else {
		format!("{} {}", time_label(o.event.start, h24), o.event.title)
	}
}

fn draw_month(f: &Frame, m: &MonthModel) {
	let fonts = theme::fonts();
	let (x, y, w, h) = (f.x(), f.y(), f.w(), f.h());
	draw::push_clip(x, y, w, h);
	draw::draw_rect_fill(x, y, w, h, Color::White);
	let (cw, ch) = (w / 7, (h - NAME_H) / WEEKS);
	// The day name row.
	draw::draw_rect_fill(x, y, w, NAME_H, PANEL);
	draw::set_font(fonts.sans, 11);
	draw::set_draw_color(Color::Black);
	for c in 0..7 {
		draw::draw_text2(
			DOW_LONG[dow_index(c as usize, m.begins)],
			x + c * cw,
			y,
			cw,
			NAME_H,
			Align::Center,
		);
	}
	if m.days.is_empty() {
		draw::pop_clip();
		return;
	}
	let shown = ymd(m.day).month;
	draw::set_font(fonts.sans, 11);
	let number_h = draw::height() + NUM_PAD;
	draw::set_font(fonts.arial, 12);
	let line_h = draw::height();
	let key = icon("private");
	for i in 0..7 * WEEKS {
		let (rx, ry) = (x + (i % 7) * cw, y + NAME_H + (i / 7) * ch);
		let day = m.start + i as i64;
		let other = ymd(day).month != shown;
		draw::push_clip(rx, ry, cw, ch);
		draw::draw_rect_fill(rx, ry, cw, ch, if other { PANEL } else { Color::White });
		// The date number at the top right. The selected day fills with the select color; today is red and
		// bold, on the fill too.
		let today = day == m.today;
		draw::set_font(if today { fonts.sans_bold } else { fonts.sans }, 11);
		let num = ymd(day).day.to_string();
		let nw = text_w(&num);
		let nx = rx + cw - nw - 3;
		let color = if day == m.day {
			draw::draw_rect_fill(nx - 2, ry + 1, nw + 4, draw::height(), SELECT);
			if today {
				Color::Red
			} else {
				Color::White
			}
		} else if today {
			Color::Red
		} else if other {
			Color::from_rgb(128, 128, 128)
		} else {
			Color::Black
		};
		draw::set_draw_color(color);
		draw::draw_text(&num, nx, ry + draw::height() - draw::descent() + 1);
		// The event lines that fit. A black triangle at the top left shows the day has more events.
		let room = ((ch - number_h) / line_h.max(1)).max(0) as usize;
		let list = &m.days[i as usize];
		draw::set_font(fonts.arial, 12);
		let asc = draw::height() - draw::descent();
		let mut ly = ry + number_h;
		for o in list.iter().take(room) {
			let mut lx = rx + 3;
			if o.event.is_private {
				let mut k = key.clone();
				k.draw(lx, ly, k.w(), k.h());
				lx += 13;
			}
			draw::set_font(fonts.arial, 12);
			draw::set_draw_color(Color::Black);
			draw::draw_text(&fit(&line_text(o, m.h24), rx + cw - lx - 2, &text_w), lx, ly + asc);
			ly += line_h;
		}
		if list.len() > room {
			let (tx, ty) = (rx + 2, ry + 2);
			draw::set_draw_color(Color::Black);
			draw::draw_polygon(tx, ty, tx + MORE_W, ty, tx + MORE_W / 2, ty + MORE_H);
		}
		draw::pop_clip();
	}
	draw_grid(x, y, cw, ch);
	draw::pop_clip();
}

/// The grid lines: one black device pixel line on each edge of the six weeks, drawn once for all cells, so
/// two cells never draw the same edge twice.
fn draw_grid(x: i32, y: i32, cw: i32, ch: i32) {
	let d = Dev::begin();
	let (top, bottom) = (y + NAME_H, y + NAME_H + WEEKS * ch);
	let (left, right) = (x, x + 7 * cw);
	let (dt, db) = (d.p(top), d.p(bottom));
	let (dl, dr) = (d.p(left), d.p(right));
	let lw = d.lw();
	draw::set_draw_color(Color::Black);
	for c in 0..=7 {
		draw::draw_rectf(d.p(left + c * cw), dt, lw, db - dt);
	}
	for r in 0..=WEEKS {
		draw::draw_rectf(dl, d.p(top + r * ch), dr - dl, lw);
	}
}
