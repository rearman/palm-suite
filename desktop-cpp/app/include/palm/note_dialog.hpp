// The Note Editor of EventDialog.kt (NoteDialog) and its NoteBox, as desktop-rs/app/src/note_dialog.rs: a text area
// that wraps its lines, under a toolbar of Cut, Copy, Paste and Undo. The To Do List and the Date Book use the editor,
// and the Note tab of Edit Address uses the NoteBox.
#pragma once

#include "palm/draw.hpp"
#include "palm/text_edit.hpp"

#include <FL/Fl_Text_Buffer.H>

#include <array>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

class Fl_Button;
class Fl_PNG_Image;
class Fl_RGB_Image;
class Fl_Scrollbar;
class Fl_Widget;

namespace palm::note_dialog {

// A note box as Palm Desktop 3.1.1 draws it: a toolbar of Cut, Copy, Paste and Undo, and a text area that wraps its
// lines, with a vertical scroll bar that is always shown. Call sync() after each event, which grays the buttons that do
// not apply and keeps the bar in step with the text. The box is built in the current group. The window that holds it
// must be deleted before the box is destroyed, since the text area refers to the buffer that the box owns.
class NoteBox {
public:
	// The toolbar at (tool_x, tool_y), its buttons `step` px apart, and the text box in `box`.
	NoteBox(int tool_x, int tool_y, int step, draw::Rect box);
	~NoteBox();
	NoteBox(const NoteBox&) = delete;
	NoteBox& operator=(const NoteBox&) = delete;

	// Grays the toolbar buttons that do not apply, as the main toolbar does, and sets the bar to the top row of the
	// text area, its thumb sized to the rows in view.
	void sync();

	text_edit::Editor* area = nullptr;
	Fl_Text_Buffer buf;
	text_edit::Field field;

private:
	// One toolbar button: its Edit command, the button, its two images, and the field the command runs on.
	struct Tool {
		text_edit::TextAction action = text_edit::TextAction::Cut;
		Fl_Button* button = nullptr;
		std::unique_ptr<Fl_PNG_Image> image;
		std::unique_ptr<Fl_RGB_Image> deimage;
		text_edit::Field target;
	};

	// Runs the Edit command of the button that was pressed. `data` is its Tool.
	static void run_tool(Fl_Widget* w, void* data);

	std::array<Tool, 4> tools;
	Fl_Scrollbar* bar = nullptr;
	int view_rows = 1;
};

// Edits `text` in a dialog named `title`. The new text on OK, nullopt on Cancel.
std::optional<std::string> edit(std::string_view title, std::string_view text);

} // namespace palm::note_dialog
