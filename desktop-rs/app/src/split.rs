//! A list beside a detail pane, parted by a 14 px divider the user drags, as detailSplit in Theme.kt. The
//! divider draws the raised bar of Palm Desktop. Which side takes a change of the window width is set by
//! `Grow`: the Address Book and Memo Pad keep the list and the detail pane takes the extra width; the To
//! Do List keeps both sides in their shares. The width is saved as a setting when the divider is released.

use crate::draw::{Dev, HIGHLIGHT, SHADOW};
use crate::msg::Msg;
use crate::theme::PANEL;
use fltk::app::Sender;
use fltk::draw;
use fltk::enums::{Color, Cursor, Event, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::prelude::*;
use std::cell::Cell;
use std::rc::Rc;

const DIVIDER: i32 = 14;
/// The narrowest list: the List by or Show button and New (166 px from the right) must stay right of the 1 px
/// resizable of the list pane (128 px from the left), or FLTK widens them when the list widens again.
const MIN_LIST: i32 = 300;
const MIN_DETAIL: i32 = 200;
/// The window width at which a `Grow::Share` detail width is saved: the normal 1000 px window.
const REF_W: i32 = 1000;

/// How the panes follow a change of the window width.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Grow {
	/// The list keeps its width and the detail pane takes the extra width (Address Book, Memo Pad).
	Detail,
	/// The list and the detail pane keep their shares of the width (To Do List). The saved width is the
	/// detail width in a window of `REF_W` px, so it scales with the window.
	Share,
}

pub struct Split {
	pub outer: Group,
	pub left: Group,
	pub right: Group,
}

/// Builds the split in (x, y, w, h) with `Grow::Detail`. `detail_w` is the saved width of the detail pane.
/// Fill each side between its begin() and end(). `key` names the setting of the width.
pub fn split(x: i32, y: i32, w: i32, h: i32, detail_w: i32, key: &'static str, s: Sender<Msg>) -> Split {
	split_grow(x, y, w, h, detail_w, key, Grow::Detail, s)
}

/// Builds the split in (x, y, w, h) with the rule `grow`. `detail_w` is the saved width of the detail pane;
/// with `Grow::Share` it is the width at `REF_W`. Fill each side between its begin() and end().
#[allow(clippy::too_many_arguments)]
pub fn split_grow(
	x: i32,
	y: i32,
	w: i32,
	h: i32,
	detail_w: i32,
	key: &'static str,
	grow: Grow,
	s: Sender<Msg>,
) -> Split {
	// The detail width at REF_W, kept current by the drag, so that a resize scales from it.
	let ref_detail = Rc::new(Cell::new(detail_w));
	let detail_w = detail_for(grow, detail_w, w);
	let mut outer = Group::new(x, y, w, h, None);
	let lw = w - DIVIDER - detail_w;
	// The panes paint the panel gray under their widgets, so a widget that moves leaves no copy behind.
	let mut left = Group::new(x, y, lw, h, None);
	left.end();
	left.set_frame(FrameType::FlatBox);
	left.set_color(PANEL);
	let mut bar = Frame::new(x + lw, y, DIVIDER, h, None);
	bar.set_frame(FrameType::FlatBox);
	let mut right = Group::new(x + lw + DIVIDER, y, detail_w, h, None);
	right.end();
	right.set_frame(FrameType::FlatBox);
	right.set_color(PANEL);
	match grow {
		Grow::Detail => outer.resizable(&right),
		Grow::Share => outer.resizable(&left),
	}
	outer.end();

	if grow == Grow::Share {
		let (mut l, mut b, mut r) = (left.clone(), bar.clone(), right.clone());
		let rd = ref_detail.clone();
		outer.resize_callback(move |o, _, _, _, _| {
			let d = detail_for(Grow::Share, rd.get(), o.w());
			place(o, &mut l, &mut b, &mut r, o.w() - DIVIDER - d);
		});
	}

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
	let rd = ref_detail.clone();
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
			place(&o, &mut l, b, &mut r, lw);
			if grow == Grow::Share {
				rd.set(to_ref(total - lw - DIVIDER, total));
			}
			o.init_sizes();
			o.redraw();
			true
		}
		Event::Released => {
			let width = match grow {
				Grow::Detail => r.w(),
				Grow::Share => rd.get(),
			};
			s.send(Msg::SplitMoved { key, width });
			true
		}
		_ => false,
	});
	Split { outer, left, right }
}

/// The detail width in a window of `total` px. A `Grow::Share` width is scaled up or down from `REF_W`.
fn detail_for(grow: Grow, detail: i32, total: i32) -> i32 {
	let d = match grow {
		Grow::Detail => detail,
		// Known limit: integer division rounds the share down, by under 1 px per resize.
		Grow::Share => detail * (total - DIVIDER) / (REF_W - DIVIDER),
	};
	d.clamp(MIN_DETAIL, (total - DIVIDER - MIN_LIST).max(MIN_DETAIL))
}

/// The detail width of `detail` px in a window of `total` px, scaled to `REF_W`.
fn to_ref(detail: i32, total: i32) -> i32 {
	detail * (REF_W - DIVIDER) / (total - DIVIDER).max(1)
}

/// Lays the list, the divider and the detail pane in `o`, with the list `lw` px wide.
fn place(o: &Group, l: &mut Group, b: &mut Frame, r: &mut Group, lw: i32) {
	l.resize(o.x(), o.y(), lw, o.h());
	b.resize(o.x() + lw, o.y(), DIVIDER, o.h());
	r.resize(o.x() + lw + DIVIDER, o.y(), o.w() - lw - DIVIDER, o.h());
}
