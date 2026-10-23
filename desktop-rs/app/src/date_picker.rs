//! The Palm Desktop date dialog of DatePicker.kt: Select Date, Select Week or Select Month. The year with
//! its arrows, the twelve month buttons, and (except for a month) a calendar of six weeks.

use crate::draw::Dev;
use crate::theme::{self, SELECT, SHADOW};
use fltk::app;
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, Color, Event, FrameType, Key};
use fltk::frame::Frame;
use fltk::prelude::*;
use fltk::window::Window;
use palm_core::days::{dow, epoch_day, month_length, ymd};
use palm_core::model::{DOW_LETTER, MONTH_SHORT};
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// What the dialog picks.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
	Day,
	Week,
	Month,
}

/// Epoch day of the first day of the week that holds `day`; `begins` is 0 for Sunday, 1 for Monday.
pub fn week_start_of(day: i64, begins: u32) -> i64 {
	day - ((dow(day) + 7 - begins) % 7) as i64
}

/// `day` moved by `months`, on the same day of the month or the last day of a shorter month.
pub fn add_months(day: i64, months: i64) -> i64 {
	let d = ymd(day);
	let n = d.year * 12 + (d.month as i64 - 1) + months;
	let (y, m) = (n.div_euclid(12), n.rem_euclid(12) as u32 + 1);
	epoch_day(y, m, d.day.min(month_length(y, m)))
}

/// The 42 days of the calendar of the month that holds `day`, from the start of its first week.
fn calendar_start(day: i64, begins: u32) -> i64 {
	let d = ymd(day);
	week_start_of(epoch_day(d.year, d.month, 1), begins)
}

/// Shows the dialog for `mode` on `day`, and returns the chosen epoch day, or None on Cancel. `today` is
/// drawn red; `begins` is the first day of the week (0 Sunday, 1 Monday).
pub fn pick(mode: Mode, day: i64, today: i64, begins: u32) -> Option<i64> {
	let title = match mode {
		Mode::Day => "Select Date",
		Mode::Week => "Select Week",
		Mode::Month => "Select Month",
	};
	let h = if mode == Mode::Month { 112 } else { 250 };
	let mut win = Window::default().with_size(210, h).with_label(title);
	let sel = Rc::new(Cell::new(day));
	let done: Rc<Cell<Option<bool>>> = Rc::new(Cell::new(None));
	let fonts = theme::fonts();

	let mut back = Button::new(6, 6, 20, 20, "@4>");
	let mut year = Frame::new(30, 6, 150, 20, None);
	year.set_frame(FrameType::DownBox);
	year.set_label_font(fonts.sans_bold);
	let mut fwd = Button::new(184, 6, 20, 20, "@>");
	for (b, years) in [(&mut back, -12), (&mut fwd, 12)] {
		b.set_label_size(9);
		let sel = sel.clone();
		b.set_callback(move |_| sel.set(add_months(sel.get(), years)));
	}

	let mut months = Vec::new();
	for m in 0..12 {
		let (col, row) = (m % 6, m / 6);
		let x = 6 + col * 33;
		let mut b = Button::new(x, 32 + row * 20, 32, 18, MONTH_SHORT[m as usize]);
		// A flat face in a gray line, as the LineBorder of DatePicker.kt.
		b.set_frame(FrameType::FlatBox);
		b.set_down_frame(FrameType::FlatBox);
		b.draw(|b| {
			let d = Dev::begin();
			let (x, y, w, h) = d.rect(b.x(), b.y(), b.w(), b.h());
			draw::draw_rect_with_color(x, y, w, h, SHADOW);
		});
		b.set_selection_color(SELECT);
		b.clear_visible_focus();
		let sel = sel.clone();
		b.set_callback(move |_| {
			let d = ymd(sel.get());
			sel.set(add_months(sel.get(), m as i64 + 1 - d.month as i64));
		});
		months.push(b);
	}

	// The calendar: a header row of day letters, then six weeks; a click picks a day, a double click is OK.
	let mut cal = Frame::new(6, 78, 198, 136, None);
	if mode == Mode::Month {
		cal.hide();
	}
	{
		let sel = sel.clone();
		cal.draw(move |f| {
			let start = calendar_start(sel.get(), begins);
			let s = sel.get();
			let week = week_start_of(s, begins);
			let month = ymd(s).month;
			let (cw, ch) = (f.w() as f32 / 7.0, f.h() as f32 / 7.0);
			draw::draw_rect_fill(f.x(), f.y(), f.w(), f.h(), Color::White);
			for r in 0..7 {
				for c in 0..7 {
					let x = f.x() + (c as f32 * cw) as i32;
					let y = f.y() + (r as f32 * ch) as i32;
					let (w, h) = (
						f.x() + ((c + 1) as f32 * cw) as i32 - x,
						f.y() + ((r + 1) as f32 * ch) as i32 - y,
					);
					if r == 0 {
						draw::set_font(fonts.sans_bold, 11);
						draw::set_draw_color(Color::Black);
						let letter = DOW_LETTER[((c as u32 + begins) % 7) as usize];
						draw::draw_text2(letter, x, y, w, h, Align::Center);
						continue;
					}
					let d = start + ((r - 1) * 7 + c) as i64;
					let picked = match mode {
						Mode::Week => d >= week && d < week + 7,
						Mode::Month => false,
						Mode::Day => d == s,
					};
					if picked {
						draw::draw_rect_fill(x, y, w, h, SELECT);
					}
					draw::set_font(fonts.sans, 11);
					draw::set_draw_color(if d == today {
						Color::Red
					} else if picked {
						Color::White
					} else if ymd(d).month != month {
						Color::from_rgb(128, 128, 128)
					} else {
						Color::Black
					});
					draw::draw_text2(&ymd(d).day.to_string(), x, y, w, h, Align::Center);
				}
			}
			let d = Dev::begin();
			let (x, y, w, h) = d.rect(f.x(), f.y(), f.w(), f.h());
			draw::draw_rect_with_color(x, y, w, h, SHADOW);
		});
	}
	{
		let (sel, done) = (sel.clone(), done.clone());
		cal.handle(move |f, ev| {
			if ev != Event::Push {
				return false;
			}
			let (cw, ch) = (f.w() as f32 / 7.0, f.h() as f32 / 7.0);
			let c = ((app::event_x() - f.x()) as f32 / cw) as i64;
			let r = ((app::event_y() - f.y()) as f32 / ch) as i64;
			if !(0..7).contains(&c) || !(1..7).contains(&r) {
				return true;
			}
			sel.set(calendar_start(sel.get(), begins) + (r - 1) * 7 + c);
			if app::event_clicks() {
				done.set(Some(true));
			}
			true
		});
	}

	let by = h - 29;
	let mut ok = Button::new(6, by, 64, 23, "OK");
	let mut cancel = Button::new(73, by, 64, 23, "Cancel");
	let today_label = match mode {
		Mode::Day => "&Today",
		Mode::Week => "&This Week",
		Mode::Month => "&This Month",
	};
	let mut today_b = Button::new(140, by, 64, 23, today_label);
	{
		let done = done.clone();
		ok.set_callback(move |_| done.set(Some(true)));
	}
	{
		let done = done.clone();
		cancel.set_callback(move |_| done.set(Some(false)));
	}
	{
		let (sel, done) = (sel.clone(), done.clone());
		today_b.set_callback(move |_| {
			sel.set(today);
			done.set(Some(true));
		});
	}
	win.end();
	// The arrows move by a day or a week, Page Up and Page Down by a month; Enter is OK, Escape Cancel.
	{
		let (sel, done) = (sel.clone(), done.clone());
		win.handle(move |_, ev| {
			if ev != Event::KeyDown && ev != Event::Shortcut {
				return false;
			}
			let s = sel.get();
			let next = match app::event_key() {
				Key::Left => s - 1,
				Key::Right => s + 1,
				Key::Up => s - 7,
				Key::Down => s + 7,
				Key::PageUp => add_months(s, -1),
				Key::PageDown => add_months(s, 1),
				Key::Enter | Key::KPEnter => {
					done.set(Some(true));
					return true;
				}
				Key::Escape => {
					done.set(Some(false));
					return true;
				}
				_ => return false,
			};
			sel.set(next);
			true
		});
	}

	let shown = Rc::new(RefCell::new(i64::MIN));
	win.make_modal(true);
	win.show();
	while win.shown() {
		app::wait();
		if let Some(answer) = done.take() {
			win.hide();
			return answer.then(|| sel.get());
		}
		// Redraw the year, the months and the calendar when the selection moved.
		if *shown.borrow() != sel.get() {
			*shown.borrow_mut() = sel.get();
			let d = ymd(sel.get());
			year.set_label(&d.year.to_string());
			for (m, b) in months.iter_mut().enumerate() {
				let on = m as u32 + 1 == d.month;
				b.set_color(if on { SELECT } else { Color::from_rgb(240, 240, 240) });
				b.set_label_color(if on { Color::White } else { Color::Black });
				b.redraw();
			}
			year.redraw();
			cal.redraw();
		}
	}
	None
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn weeks_and_months_move_as_the_calendar_does() {
		// 2026-10-23 is a Friday.
		let fri = epoch_day(2026, 10, 23);
		assert_eq!(week_start_of(fri, 0), epoch_day(2026, 10, 18));
		assert_eq!(week_start_of(fri, 1), epoch_day(2026, 10, 19));
		assert_eq!(add_months(epoch_day(2026, 1, 31), 1), epoch_day(2026, 2, 28));
		assert_eq!(add_months(epoch_day(2026, 1, 15), -13), epoch_day(2024, 12, 15));
		assert_eq!(add_months(epoch_day(2024, 2, 29), 12), epoch_day(2025, 2, 28));
		// October 2026 starts on a Thursday, so its Sunday calendar starts on September 27.
		assert_eq!(calendar_start(fri, 0), epoch_day(2026, 9, 27));
	}
}
