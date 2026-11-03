// The detail split, as desktop-rs/app/src/split.rs: a list beside a detail pane, parted by a 14 px divider the user
// drags, as detailSplit in Theme.kt. The divider draws the raised bar of Palm Desktop. Which side takes a change of the
// window width is set by `Grow`: the Address Book and Memo Pad keep the list and the detail pane takes the extra width;
// the To Do List keeps both sides in their shares. The width is saved as a setting when the divider is released.
#pragma once

#include "palm/msg.hpp"

#include <string_view>

class Fl_Group;

namespace palm::split {

// How the panes follow a change of the window width.
enum class Grow {
	// The list keeps its width and the detail pane takes the extra width (Address Book, Memo Pad).
	Detail,
	// The list and the detail pane keep their shares of the width (To Do List). The saved width is the detail width
	// in a window of 1000 px, so it scales with the window.
	Share,
};

// The three groups of a split. The window owns them: the split is a child of the current group.
struct Split {
	Fl_Group* outer;
	Fl_Group* left;
	Fl_Group* right;
};

// Builds the split in (x, y, w, h) with `Grow::Detail`. `detail_w` is the saved width of the detail pane. Fill each
// side between its begin() and end(). `key` names the setting of the width; the message keeps the view, so it must be a
// string literal.
Split split(int x, int y, int w, int h, int detail_w, std::string_view key, msg::Sender s);

// Builds the split in (x, y, w, h) with the rule `grow`. `detail_w` is the saved width of the detail pane; with
// `Grow::Share` it is the width at 1000 px. Fill each side between its begin() and end(). `key` as in split().
Split split_grow(int x, int y, int w, int h, int detail_w, std::string_view key, Grow grow, msg::Sender s);

} // namespace palm::split
