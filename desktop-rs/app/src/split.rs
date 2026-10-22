//! A list beside a detail pane, parted by a 14 px divider the user drags, as detailSplit in Theme.kt. The
//! divider draws the raised bar of Palm Desktop. The list takes the extra width and the detail pane keeps
//! its width, which is saved as a setting when the divider is released.

use crate::draw::{Dev, HIGHLIGHT, SHADOW};
use crate::msg::Msg;
use crate::theme::PANEL;
use fltk::app::Sender;
use fltk::draw;
use fltk::enums::{Color, Cursor, Event, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::prelude::*;

const DIVIDER: i32 = 14;
const MIN_LIST: i32 = 150;
const MIN_DETAIL: i32 = 200;

pub struct Split {
	pub outer: Group,
	pub left: Group,
	pub right: Group,
}

/// Builds the split in (x, y, w, h). `detail_w` is the saved width of the detail pane. Fill each side
/// between its begin() and end(). `key` names the setting of the width.
pub fn split(x: i32, y: i32, w: i32, h: i32, detail_w: i32, key: &'static str, s: Sender<Msg>) -> Split {
	let detail_w = detail_w.clamp(MIN_DETAIL, (w - DIVIDER - MIN_LIST).max(MIN_DETAIL));
	let outer = Group::new(x, y, w, h, None);
	let lw = w - DIVIDER - detail_w;
	let left = Group::new(x, y, lw, h, None);
	left.end();
	let mut bar = Frame::new(x + lw, y, DIVIDER, h, None);
	bar.set_frame(FrameType::FlatBox);
	let right = Group::new(x + lw + DIVIDER, y, detail_w, h, None);
	right.end();
	outer.resizable(&left);
	outer.end();

	bar.draw(|b| {
		draw::draw_rect_fill(b.x(), b.y(), b.w(), b.h(), PANEL);
		// The raised bar: 8 px of panel gray, then white, light gray three times, shadow gray and black.
		let d = Dev::begin();
		let columns = [
			HIGHLIGHT,
			Color::from_rgb(200, 200, 200),
			Color::from_rgb(200, 200, 200),
			Color::from_rgb(200, 200, 200),
			SHADOW,
			Color::Black,
		];
		let (_, y1, _, h1) = d.rect(b.x(), b.y(), b.w(), b.h());
		let k = d.lw();
		let x0 = d.p(b.x() + 8);
		for (i, c) in columns.iter().enumerate() {
			draw::set_draw_color(*c);
			draw::draw_rectf(x0 + i as i32 * k, y1, k, h1);
		}
	});
	let (mut l, mut r, mut o) = (left.clone(), right.clone(), outer.clone());
	bar.handle(move |b, ev| match ev {
		Event::Enter => {
			if let Some(mut win) = b.window() {
				win.set_cursor(Cursor::WE);
			}
			true
		}
		Event::Leave => {
			if let Some(mut win) = b.window() {
				win.set_cursor(Cursor::Default);
			}
			true
		}
		Event::Push => true,
		Event::Drag => {
			let total = o.w();
			let mx = fltk::app::event_x() - o.x() - DIVIDER / 2;
			let lw = mx.clamp(MIN_LIST, (total - DIVIDER - MIN_DETAIL).max(MIN_LIST));
			l.resize(o.x(), o.y(), lw, o.h());
			b.resize(o.x() + lw, o.y(), DIVIDER, o.h());
			r.resize(o.x() + lw + DIVIDER, o.y(), total - lw - DIVIDER, o.h());
			o.init_sizes();
			o.redraw();
			true
		}
		Event::Released => {
			s.send(Msg::SplitMoved { key, width: r.w() });
			true
		}
		_ => false,
	});
	Split { outer, left, right }
}
