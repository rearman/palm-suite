// The record clipboard, the list menu, and the events of the record lists, as desktop-rs/app/src/records.rs.
#include "palm/records.hpp"

#include "palm/draw.hpp"
#include "palm/text.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Menu_Button.H>

#include <algorithm>
#include <string>
#include <utility>

namespace palm::records {
namespace {

// The records of a Cut or Copy and their text.
struct Clip {
	std::string kind;
	std::string text;
	std::vector<runner::Record> records;
};

// Rust's thread_local; the UI runs on the main thread.
thread_local std::optional<Clip> clip;

// Rust's char::is_control: the C0 controls, DEL, and the C1 controls.
bool is_control(char32_t c) {
	return c < 0x20 || (c >= 0x7F && c <= 0x9F);
}

// The text of the event that FLTK is handling; empty when there is none.
std::string event_text() {
	const char* text = Fl::event_text();
	return text ? std::string(text, static_cast<std::size_t>(Fl::event_length())) : std::string();
}

} // namespace

void put(std::string_view kind, std::vector<runner::Record> records, std::string text) {
	Fl::copy(text.data(), static_cast<int>(text.size()), 1);
	keep(kind, std::move(records), std::move(text));
}

void keep(std::string_view kind, std::vector<runner::Record> records, std::string text) {
	clip = Clip{std::string(kind), std::move(text), std::move(records)};
}

std::optional<std::vector<runner::Record>> records_for(std::string_view kind, std::string_view text) {
	if (clip && clip->kind == kind && clip->text == text) {
		return clip->records;
	}
	return std::nullopt;
}

text_edit::EditState list_state(bool selected, bool rows) {
	return text_edit::EditState{
	    .undo = false,
	    .cut = selected,
	    .copy = selected,
	    .paste = text_edit::clipboard_has_text(),
	    .delete_text_edit = selected,
	    .select_all = rows,
	};
}

std::optional<ListAction> list_menu(text_edit::EditState s, std::span<const std::pair<const char*, bool>> extra) {
	// The label and the enabled state of each item, the five of the list and the view's own.
	std::vector<std::pair<const char*, bool>> items{
	    {"Cu&t", s.cut},
	    {"&Copy", s.copy},
	    {"&Paste", s.paste},
	    {"&Delete", s.delete_text_edit},
	    {"Select &All", s.select_all},
	};
	items.insert(items.end(), extra.begin(), extra.end());

	Fl_Menu_Button mb(0, 0, 0, 0);
	mb.type(Fl_Menu_Button::POPUP3);
	for (std::size_t i = 0; i < items.size(); ++i) {
		// The divider comes with the flags of the item, so each item gets its flags once: Inactive when it is off.
		const bool divider = i == 3 || (i == 4 && !extra.empty());
		const int flags = (divider ? FL_MENU_DIVIDER : 0) | (items[i].second ? 0 : FL_MENU_INACTIVE);
		mb.add(items[i].first, 0, nullptr, nullptr, flags);
	}
	draw::style_menu(&mb);
	if (!mb.popup()) {
		return std::nullopt;
	}
	const int v = mb.value();
	switch (v) {
	case 0:
		return action::Cut{};
	case 1:
		return action::Copy{};
	case 2:
		return action::Paste{};
	case 3:
		return action::Delete{};
	case 4:
		return action::SelectAll{};
	default:
		if (v < 0) {
			return std::nullopt;
		}
		return action::Extra{static_cast<std::size_t>(v - 5)};
	}
}

RecordTable::RecordTable(int x, int y, int w, int h, TableRowSelectMode mode, int row_h, int ncols)
    : Fl_Table_Row(x, y, w, h), row_h_(row_h) {
	type(mode);
	rows(0);
	cols(ncols);
	col_header(0);
	row_header(0);
	// The cells and FLTK's fill of the area below them, in color(), paint the inside.
	box(FL_DOWN_FRAME);
	color(FL_WHITE);
	end();
}

void RecordTable::rows(int n) {
	// Each row_height call resizes the whole table, so setting the rows one at a time cost a time that grows with the
	// square of the rows. FLTK's row_height also writes past the end of its list when it grows it, so the one row that
	// sets the height comes from rows(1). Every row here is row_h_ high, so the rows a fill adds copy the last one.
	if (n > 0 && rows() == 0) {
		Fl_Table_Row::rows(1);
		row_height(0, row_h_);
	}
	Fl_Table_Row::rows(n);
}

std::optional<std::pair<int, int>> RecordTable::cell_under_cursor() {
	// FLTK's cursor2rowcol with no header, for the visible cells only: FLTK gives its dead zone as row 0 and column 0,
	// which made a click below the rows act on row 0. The arithmetic is FLTK's find_cell for a cell.
	if (!Fl::event_inside(tix, tiy, tiw, tih)) {
		return std::nullopt;
	}
	for (int r = toprow; r <= botrow; ++r) {
		const int y = static_cast<int>(row_scroll_position(r) - vscrollbar->value() + tiy);
		const int h = row_height(r);
		if (Fl::event_y() < y) {
			break;
		}
		if (Fl::event_y() >= y + h) {
			continue;
		}
		for (int c = leftcol; c <= rightcol; ++c) {
			const int x = static_cast<int>(col_scroll_position(c) - hscrollbar->value() + tix);
			if (Fl::event_inside(x, y, col_width(c), h)) {
				return std::pair{r, c};
			}
		}
	}
	return std::nullopt;
}

int RecordTable::table_handle(int event) {
	// The press is taken, so its release and drags come here, but FLTK never records it: its release test then sees
	// the last press on a row and clears nothing.
	if (event == FL_PUSH && Fl::event_button() == FL_LEFT_MOUSE && Fl::event_inside(tix, tiy, tiw, tih) &&
	    !cell_under_cursor()) {
		return 1;
	}
	return Fl_Table_Row::handle(event);
}

void fit_column(RecordTable& t) {
	const int width = t.w() - 4 - (t.rows() * t.row_h() > t.h() - 4 ? 17 : 0);
	if (t.col_width(0) != width) {
		t.col_width(0, width);
	}
}

bool list_event(RecordTable& t, int ev, const std::shared_ptr<int>& lead, msg::Sender s, ListMsgs m) {
	switch (ev) {
	case FL_PUSH: {
		(void)t.take_focus();
		std::optional<int> row;
		if (const auto hit = t.cell_under_cursor(); hit && hit->first >= 0 && hit->first < t.rows()) {
			row = hit->first;
		}
		if (Fl::event_button() == FL_RIGHT_MOUSE) {
			if (row && !t.row_selected(*row)) {
				t.select_all_rows(0);
				(void)t.select_row(*row, 1);
				*lead = *row;
				t.redraw();
			}
			s.send(m.picked());
			s.send(m.menu());
			return true;
		}
		if (row) {
			*lead = *row;
		}
		return false;
	}
	case FL_RELEASE: {
		s.send(m.picked());
		// A release below the rows touches no record: no cell click, and a double click there opens nothing.
		const auto hit = t.cell_under_cursor();
		const bool on_row = hit && hit->first >= 0 && hit->first < t.rows() && hit->second >= 0;
		if (m.cell && on_row && Fl::event_button() == FL_LEFT_MOUSE) {
			s.send((*m.cell)(hit->first, hit->second, Fl::event_clicks() != 0));
		}
		if (m.open && on_row && Fl::event_clicks() != 0 && Fl::event_button() == FL_LEFT_MOUSE && *lead >= 0) {
			s.send((*m.open)());
		}
		return false;
	}
	case FL_KEYDOWN: {
		const int key = Fl::event_key();
		if (key == FL_Up || key == FL_Down) {
			const int n = t.rows();
			if (n == 0) {
				return true;
			}
			const int next = std::clamp(*lead + (key == FL_Up ? -1 : 1), 0, n - 1);
			t.select_all_rows(0);
			(void)t.select_row(next, 1);
			t.row_position(next);
			*lead = next;
			t.redraw();
			s.send(m.picked());
			return true;
		}
		if (!m.typed) {
			return false;
		}
		const std::string text = event_text();
		if (text.empty()) {
			return false;
		}
		const text::Decoded first = text::decode_at(text, 0);
		const int state = Fl::event_state();
		const bool ctrl = (state & FL_CTRL) != 0 || (state & FL_ALT) != 0;
		// One code point, with no Ctrl or Alt, that is not a control.
		if (first.len == text.size() && !ctrl && !is_control(first.cp)) {
			s.send((*m.typed)(first.cp));
			return true;
		}
		return false;
	}
	case FL_PASTE:
		s.send(m.paste(event_text()));
		return true;
	case FL_FOCUS:
	case FL_UNFOCUS:
		return true;
	default:
		return false;
	}
}

std::vector<std::size_t> selected_rows(Fl_Table_Row& t, std::size_t n) {
	std::vector<std::size_t> rows;
	for (std::size_t i = 0; i < n; ++i) {
		if (t.row_selected(static_cast<int>(i))) {
			rows.push_back(i);
		}
	}
	return rows;
}

void pick_row(Fl_Table_Row& t, int& lead, std::optional<std::size_t> row) {
	t.select_all_rows(0);
	if (row) {
		(void)t.select_row(static_cast<int>(*row), 1);
		t.row_position(static_cast<int>(*row));
	}
	lead = row ? static_cast<int>(*row) : -1;
	t.redraw();
}

} // namespace palm::records
