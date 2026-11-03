// The Note Editor and its NoteBox, as desktop-rs/app/src/note_dialog.rs. The Rust closures of the toolbar buttons and
// of the scroll bar become static callbacks that take the button or the text area as their data.
#include "palm/note_dialog.hpp"

#include "palm/dialogs.hpp"
#include "palm/mnemonics.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/Fl_Scrollbar.H>
#include <FL/Fl_Text_Display.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cstddef>
#include <string>

namespace palm::note_dialog {
namespace {

// The toolbar buttons, left to right, with the icon of each and its tooltip.
struct ToolSpec {
	const char* png;
	const char* tip;
	text_edit::TextAction action;
};

constexpr std::array<ToolSpec, 4> TOOLS{{
    {"cut", "Cut", text_edit::TextAction::Cut},
    {"copy", "Copy", text_edit::TextAction::Copy},
    {"paste", "Paste", text_edit::TextAction::Paste},
    {"undo", "Undo", text_edit::TextAction::Undo},
}};

// Moves the text area to the row that the bar gives. The text area numbers its top row from 1, the bar from 0.
void scroll_bar(Fl_Widget* w, void* data) {
	auto* bar = static_cast<Fl_Scrollbar*>(w);
	auto* area = static_cast<Fl_Text_Editor*>(data);
	area->scroll(static_cast<int>(bar->value()) + 1, 0);
}

// The answer of the dialog and the window that the buttons close.
struct Answer {
	bool ok = false;
	Fl_Window* win = nullptr;
};

// OK keeps the text and closes the dialog.
void ok_pressed(Fl_Widget*, void* data) {
	auto* answer = static_cast<Answer*>(data);
	answer->ok = true;
	answer->win->hide();
}

// Cancel closes the dialog and keeps nothing.
void cancel_pressed(Fl_Widget*, void* data) {
	static_cast<Answer*>(data)->win->hide();
}

} // namespace

NoteBox::NoteBox(int tool_x, int tool_y, int step, draw::Rect box) {
	for (std::size_t i = 0; i < tools.size(); ++i) {
		Tool& t = tools[i];
		t.action = TOOLS[i].action;
		auto* b = new Fl_Button(tool_x + step * static_cast<int>(i), tool_y, 24, 24);
		b->box(FL_UP_BOX);
		b->down_box(FL_DOWN_BOX);
		t.image = theme::icon(TOOLS[i].png);
		t.deimage = theme::gray_icon(TOOLS[i].png);
		b->image(t.image.get());
		b->deimage(t.deimage.get());
		b->tooltip(TOOLS[i].tip);
		b->clear_visible_focus();
		t.button = b;
	}
	const int sb_w = Fl::scrollbar_size();
	// The text area draws its own scroll bar only when the text overflows, so the box gets a scroll bar of its own at
	// its right, kept in step with the text area by sync.
	area = new text_edit::Editor(box.x, box.y, box.w - sb_w, box.h);
	area->buffer(&buf);
	area->wrap_mode(Fl_Text_Display::WRAP_AT_BOUNDS, 0);
	area->scrollbar_align(static_cast<Fl_Align>(0));
	field = text_edit::Field{area};
	for (Tool& t : tools) {
		t.target = field;
		t.button->callback(run_tool, &t);
	}
	fl_font(area->textfont(), area->textsize());
	// The display rows that fit in the text area, which keeps 2 px of frame on each side.
	view_rows = std::max((box.h - 4) / std::max(fl_height(), 1), 1);
	bar = new Fl_Scrollbar(box.x + box.w - sb_w, box.y, sb_w, box.h);
	bar->type(FL_VERTICAL);
	bar->linesize(1);
	bar->callback(scroll_bar, area);
}

NoteBox::~NoteBox() = default;

void NoteBox::run_tool(Fl_Widget*, void* data) {
	const auto* t = static_cast<const Tool*>(data);
	text_edit::run(t->target, t->action);
}

void NoteBox::sync() {
	const text_edit::EditState s = text_edit::state(field);
	for (Tool& t : tools) {
		bool on = false;
		switch (t.action) {
		case text_edit::TextAction::Cut:
			on = s.cut;
			break;
		case text_edit::TextAction::Copy:
			on = s.copy;
			break;
		case text_edit::TextAction::Paste:
			on = s.paste;
			break;
		default:
			on = s.undo;
			break;
		}
		if (on != (t.button->active() != 0)) {
			if (on) {
				t.button->activate();
			} else {
				t.button->deactivate();
			}
		}
	}
	const int rows = area->count_lines(0, buf.length(), true) + 1;
	const int max = std::max(rows - view_rows, 0);
	bar->bounds(0.0, max);
	bar->slider_size(std::min(1.0, static_cast<double>(view_rows) / rows));
	bar->value(std::clamp(static_cast<double>(area->scroll_row() - 1), 0.0, static_cast<double>(max)));
}

std::optional<std::string> edit(std::string_view title, std::string_view text) {
	// The official Note Editor has a fixed 267 x 276 px client area. The text box sits between the toolbar and the
	// buttons, as in Palm Desktop 3.1.1.
	constexpr int w = 267;
	constexpr int h = 276;
	// The note is declared before the window, so the window is destroyed first and the text area never outlives the
	// buffer of the note.
	std::optional<NoteBox> note;
	Fl_Double_Window win(w, h);
	win.copy_label(std::string(title).c_str());
	note.emplace(2, 2, 26, draw::Rect{11, 36, 244, 192});
	note->buf.text(std::string(text).c_str());
	Answer answer{false, &win};
	auto* ok = new Fl_Button(10, 239, 75, 23, "OK");
	auto* cancel = new Fl_Button(95, 239, 75, 23, "Cancel");
	ok->callback(ok_pressed, &answer);
	cancel->callback(cancel_pressed, &answer);
	// A window whose resizable widget is NULL gets no resize border and no maximize box.
	win.resizable(nullptr);
	win.end();
	mnemonics::ForgetOnExit forget_win(win);
	dialogs::show_modal(&win);
	note->area->take_focus();
	while (win.shown()) {
		Fl::wait();
		note->sync();
	}
	if (!answer.ok) {
		return std::nullopt;
	}
	return text_edit::buffer_text(note->buf);
}

} // namespace palm::note_dialog
