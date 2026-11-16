// The edit actions of a text field, as desktop-rs/app/src/text_edit.rs.
#include "palm/text_edit.hpp"

#include "palm/draw.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Text_Buffer.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace palm::text_edit {
namespace {

// The enabled state of the Edit items of a multi-line editor.
EditState editor_state(Fl_Text_Editor* e) {
	Fl_Text_Buffer* buf = e->buffer();
	if (!buf) {
		return {};
	}
	const bool selected = buf->selected();
	const bool editable = e->active();
	return EditState{
	    .undo = editable && buf->can_undo(),
	    .cut = editable && selected,
	    .copy = selected,
	    .paste = editable && clipboard_has_text(),
	    .delete_text_edit = editable && selected,
	    .select_all = buf->length() > 0,
	};
}

// The enabled state of the Edit items of a one-line input.
EditState input_state(Fl_Input* i) {
	const bool selected = i->insert_position() != i->mark();
	const bool editable = i->active() && !i->readonly();
	return EditState{
	    // FLTK keeps no undo state of an Input; a field the user changed has something to undo.
	    .undo = editable && i->changed(),
	    .cut = editable && selected,
	    .copy = selected,
	    .paste = editable && clipboard_has_text(),
	    .delete_text_edit = editable && selected,
	    .select_all = i->size() > 0,
	};
}

// Runs one Edit command on a multi-line editor.
void editor_run(Fl_Text_Editor* e, TextAction a) {
	Fl_Text_Buffer* buf = e->buffer();
	if (!buf) {
		return;
	}
	switch (a) {
	case TextAction::Undo:
		buf->undo();
		break;
	case TextAction::Copy:
	case TextAction::Cut:
		if (buf->selected()) {
			char* text = buf->selection_text();
			Fl::copy(text, static_cast<int>(std::strlen(text)), 1);
			std::free(text);
			if (a == TextAction::Cut) {
				buf->remove_selection();
			}
		}
		break;
	// Paste goes through FLTK's paste event, which inserts the clipboard text at the cursor.
	case TextAction::Paste:
		Fl::paste(*e, 1);
		break;
	case TextAction::Delete:
		buf->remove_selection();
		break;
	case TextAction::SelectAll:
		buf->select(0, buf->length());
		break;
	}
	e->redraw();
}

// Runs one Edit command on a one-line input.
void input_run(Fl_Input* i, TextAction a) {
	switch (a) {
	case TextAction::Undo:
		i->undo();
		break;
	case TextAction::Copy:
		i->copy(1);
		break;
	case TextAction::Cut:
		i->cut();
		break;
	case TextAction::Paste:
		Fl::paste(*i, 1);
		break;
	case TextAction::Delete: {
		const int p = i->insert_position();
		const int m = i->mark();
		i->replace(std::min(p, m), std::max(p, m), "");
		break;
	}
	case TextAction::SelectAll:
		// Cursor at the end, mark at the start, as Rust sets them.
		i->insert_position(i->size());
		i->mark(0);
		break;
	}
	i->redraw();
}

// The Edit items of the right-click menu, in order.
struct MenuItem {
	const char* label;
	TextAction action;
};

// Shows the right-click menu at the pointer, and returns the chosen item. The items and their dividers follow the
// Windows edit menu of Palm Desktop 3.1.1.
std::optional<TextAction> menu(const EditState& s) {
	static constexpr std::array<MenuItem, 6> ITEMS{{
	    {"&Undo", TextAction::Undo},
	    {"Cu&t", TextAction::Cut},
	    {"&Copy", TextAction::Copy},
	    {"&Paste", TextAction::Paste},
	    {"&Delete", TextAction::Delete},
	    {"Select &All", TextAction::SelectAll},
	}};
	const std::array<bool, ITEMS.size()> on{s.undo, s.cut, s.copy, s.paste, s.delete_text_edit, s.select_all};
	std::array<std::string_view, ITEMS.size()> labels{};
	std::vector<std::size_t> grayed;
	for (std::size_t i = 0; i < ITEMS.size(); ++i) {
		labels[i] = ITEMS[i].label;
		if (!on[i]) {
			grayed.push_back(i);
		}
	}
	// A divider follows Undo and Delete.
	static constexpr std::array<std::size_t, 2> DIVIDERS{0, 4};
	const std::optional<std::size_t> pick = draw::popup(labels, DIVIDERS, grayed);
	if (!pick) {
		return std::nullopt;
	}
	return ITEMS[*pick].action;
}

} // namespace

bool clipboard_has_text() {
	return Fl::clipboard_contains(Fl::clipboard_plain_text) != 0;
}

std::string buffer_text(Fl_Text_Buffer& buf) {
	char* raw = buf.text();
	std::string out = raw ? raw : "";
	std::free(raw);
	return out;
}

EditState state(const Field& field) {
	if (const auto* e = std::get_if<Fl_Text_Editor*>(&field)) {
		return editor_state(*e);
	}
	return input_state(std::get<Fl_Input*>(field));
}

void run(const Field& field, TextAction action) {
	if (const auto* e = std::get_if<Fl_Text_Editor*>(&field)) {
		editor_run(*e, action);
		return;
	}
	input_run(std::get<Fl_Input*>(field), action);
}

void popup(const Field& field) {
	std::visit([](auto* w) { (void)w->take_focus(); }, field);
	if (const auto action = menu(state(field))) {
		run(field, *action);
	}
}

int Editor::handle(int event) {
	// FLTK 1.4 opens its own Cut/Copy/Paste menu on a right push, so that push and its release never reach FLTK:
	// only this menu opens, and the caret stays put, as in a Windows edit box. All other events go to FLTK first.
	if ((event == FL_PUSH || event == FL_RELEASE) && Fl::event_button() == FL_RIGHT_MOUSE) {
		if (event == FL_PUSH) {
			popup(Field{this});
		}
		return 1;
	}
	return Fl_Text_Editor::handle(event);
}

int Input::handle(int event) {
	// As Editor::handle: the right push opens this menu only.
	if ((event == FL_PUSH || event == FL_RELEASE) && Fl::event_button() == FL_RIGHT_MOUSE) {
		if (event == FL_PUSH) {
			popup(Field{this});
		}
		return 1;
	}
	return Fl_Input::handle(event);
}

} // namespace palm::text_edit
