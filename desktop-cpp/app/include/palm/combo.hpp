// A Choice that drops its list below the field, as the combo boxes of Palm Desktop 3.1.1 on Windows do, as
// desktop-rs/app/src/combo.rs.
#pragma once

#include <FL/Fl_Choice.H>

namespace palm::combo {

// The index one step from `current` in a list of `len` items, kept inside the list. Returns -1 for an empty list.
int step_index(int current, int step, int len);

// A Choice that drops its list below the field. A left click, Alt+Down or F4 opens the list, Up and Down while the
// list is closed step through the items, and each change fires the callback. Rust's drop_below sets this up on an
// existing Choice; here the class is the Choice, because a C++ widget takes its event handling from a subclass.
class Choice : public Fl_Choice {
public:
	using Fl_Choice::Fl_Choice;

	// Sees each event before FLTK's own handler, which would open the list over the field, and leaves the events it
	// does not use to FLTK.
	int handle(int e) override;

private:
	// Opens the list below the field. A pick selects its item. Returns true when the pick was made from the keyboard.
	bool open_list();

	// Selects item `idx` and fires the callback when the value changes.
	void select(int idx);

	// Set after a keyboard pick, so the Enter that picked the item does not also press the default button of the
	// dialog.
	bool swallow_enter_ = false;
};

} // namespace palm::combo
