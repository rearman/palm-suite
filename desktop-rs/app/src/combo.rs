//! A Choice that drops its list below the field, as the combo boxes of Palm Desktop 3.1.1 on Windows do.

use fltk::app::{self, MouseButton};
use fltk::enums::{Event, EventState, Key};
use fltk::menu::Choice;
use fltk::prelude::*;

/// The index one step from `current` in a list of `len` items, kept inside the list. Returns -1 for an empty list.
pub fn step_index(current: i32, step: i32, len: i32) -> i32 {
	if len <= 0 {
		return -1;
	}
	(current + step).clamp(0, len - 1)
}

/// Makes `c` drop its list below the field, as a Windows combo box does. A left click, Alt+Down or F4 opens the
/// list, Up and Down while the list is closed step through the items, and each change fires the callback.
pub fn drop_below(c: &mut Choice) {
	// Set after a keyboard pick, so the Enter that picked the item does not also press the default button of the dialog.
	let mut swallow_enter = false;
	// fltk-rs runs FLTK's own handler first, which would open the list over the field; this handler must see
	// the events first, and leaves the ones it does not use to FLTK.
	c.super_handle_first(false);
	c.handle(move |c, ev| {
		let key = app::event_key();
		let is_enter = key == Key::Enter || key == Key::KPEnter;
		if swallow_enter && is_enter && matches!(ev, Event::KeyDown | Event::KeyUp | Event::Shortcut) {
			// The key release ends the Enter that picked the item.
			if ev == Event::KeyUp {
				swallow_enter = false;
			}
			return true;
		}
		let alt = app::event_state().contains(EventState::Alt);
		match ev {
			Event::Push if app::event_mouse_button() == MouseButton::Left => {
				swallow_enter = open_list(c);
				true
			}
			Event::KeyDown | Event::Shortcut if (alt && key == Key::Down) || key == Key::F4 => {
				swallow_enter = open_list(c);
				true
			}
			Event::KeyDown if !alt && (key == Key::Up || key == Key::Down) => {
				let step = if key == Key::Down { 1 } else { -1 };
				let idx = step_index(c.value(), step, c.size());
				if idx >= 0 {
					select(c, idx);
				}
				true
			}
			_ => false,
		}
	});
}

/// Opens the list of `c` below the field, as wide as the field. A pick selects its item. Returns true when the
/// pick was made from the keyboard.
fn open_list(c: &mut Choice) -> bool {
	let Some(menu) = c.menu() else {
		return false;
	};
	// FLTK puts the top of the list at Y + (H - item height) / 2 - border - index * item height, where index
	// is the starting item (the choice's current item when none is given). The first item is given, H is
	// one item high and Y is one border below the field, so the list starts at the bottom edge of the field.
	let first = c.at(0);
	let border = 1;
	let y = c.y() + c.h() + border;
	let Some(picked) = menu.pulldown(c.x(), y, c.w(), crate::theme::MENU_ITEM_H, first, Some(&*c)) else {
		return false;
	};
	let keyboard = matches!(app::event(), Event::KeyDown | Event::KeyUp | Event::Shortcut);
	if let Some(idx) = (0..c.size()).find(|&i| c.at(i).as_ref() == Some(&picked)) {
		select(c, idx);
	}
	keyboard
}

/// Selects item `idx` and fires the callback when the value changes.
fn select(c: &mut Choice, idx: i32) {
	if c.set_value(idx) {
		c.do_callback();
	}
}

#[cfg(test)]
mod tests {
	use super::step_index;

	#[test]
	fn step_index_stays_inside_the_list() {
		assert_eq!(step_index(0, 1, 3), 1);
		assert_eq!(step_index(2, 1, 3), 2);
		assert_eq!(step_index(2, -1, 3), 1);
		assert_eq!(step_index(0, -1, 3), 0);
		assert_eq!(step_index(-1, 1, 3), 0);
		assert_eq!(step_index(-1, -1, 3), 0);
		assert_eq!(step_index(0, 1, 0), -1);
		assert_eq!(step_index(0, 1, 1), 0);
	}
}
