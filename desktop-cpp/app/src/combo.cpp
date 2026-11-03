// A Choice that drops its list below the field, as desktop-rs/app/src/combo.rs.
#include "palm/combo.hpp"

#include "palm/theme.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Menu_Item.H>

#include <algorithm>

namespace palm::combo {

int step_index(int current, int step, int len) {
	if (len <= 0) {
		return -1;
	}
	return std::clamp(current + step, 0, len - 1);
}

int Choice::handle(int e) {
	const int key = Fl::event_key();
	const bool is_enter = key == FL_Enter || key == FL_KP_Enter;
	if (swallow_enter_ && is_enter && (e == FL_KEYDOWN || e == FL_KEYUP || e == FL_SHORTCUT)) {
		// The key release ends the Enter that picked the item.
		if (e == FL_KEYUP) {
			swallow_enter_ = false;
		}
		return 1;
	}
	const bool alt = Fl::event_state(FL_ALT) != 0;
	if (e == FL_PUSH && Fl::event_button() == FL_LEFT_MOUSE) {
		swallow_enter_ = open_list();
		return 1;
	}
	if ((e == FL_KEYDOWN || e == FL_SHORTCUT) && ((alt && key == FL_Down) || key == FL_F + 4)) {
		swallow_enter_ = open_list();
		return 1;
	}
	if (e == FL_KEYDOWN && !alt && (key == FL_Up || key == FL_Down)) {
		const int idx = step_index(value(), key == FL_Down ? 1 : -1, size());
		if (idx >= 0) {
			select(idx);
		}
		return 1;
	}
	return Fl_Choice::handle(e);
}

bool Choice::open_list() {
	const Fl_Menu_Item* items = menu();
	if (!items || size() == 0) {
		return false;
	}
	// FLTK puts the top of the list at Y + (H - item height) / 2 - border - index * item height, where index is the
	// starting item (the choice's current item when none is given). The first item is given, H is one item high and
	// Y is one border below the field, so the list starts at the bottom edge of the field.
	const int border = 1;
	const int top = y() + h() + border;
	const Fl_Menu_Item* picked = items->pulldown(x(), top, w(), theme::MENU_ITEM_H, items, this);
	if (!picked) {
		return false;
	}
	const int ev = Fl::event();
	const bool keyboard = ev == FL_KEYDOWN || ev == FL_KEYUP || ev == FL_SHORTCUT;
	for (int i = 0; i < size(); ++i) {
		if (items + i == picked) {
			select(i);
			break;
		}
	}
	return keyboard;
}

void Choice::select(int idx) {
	if (value(idx)) {
		do_callback();
	}
}

} // namespace palm::combo
