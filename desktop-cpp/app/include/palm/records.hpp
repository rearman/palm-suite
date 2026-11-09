// The record clipboard, the list menu, and the events of the record lists, as desktop-rs/app/src/records.rs: the
// Cut, Copy, and Paste of the records of a list, the right-click menu of a list, and the events that the Date Book,
// the Memo Pad, the Address Book, and the To Do List share.
#pragma once

#include "palm/msg.hpp"
#include "palm/runner.hpp"
#include "palm/text_edit.hpp"

#include <FL/Fl_Table_Row.H>

#include <cstddef>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace palm::records {

// Keeps `records` of `kind` ("todo", "memo" or "address") and puts `text` on the system clipboard.
void put(std::string_view kind, std::vector<runner::Record> records, std::string text);

// Keeps the records of a Cut or Copy, without the system clipboard. Rust keeps this private; it is public here for
// the test, which must not touch the system clipboard.
void keep(std::string_view kind, std::vector<runner::Record> records, std::string text);

// What a paste of `text` gives in a list of `kind`: the kept records when the text is theirs, else no value, and
// the caller makes records from the text.
std::optional<std::vector<runner::Record>> records_for(std::string_view kind, std::string_view text);

// The Edit items of a record list with `selected` records and `rows` rows.
text_edit::EditState list_state(bool selected, bool rows);

// The items that the user picks in a list menu. Extra holds the index of the item the view added.
namespace action {

struct Cut {};
struct Copy {};
struct Paste {};
struct Delete {};
struct SelectAll {};

struct Extra {
	std::size_t index;
};

} // namespace action

using ListAction =
    std::variant<action::Cut, action::Copy, action::Paste, action::Delete, action::SelectAll, action::Extra>;

// Shows the right-click menu of a record list at the pointer. `extra` holds the label and the enabled state of each
// item the view adds below Select All. A divider follows Delete, and one follows Select All when the view adds
// items. No value when the user picks no item.
std::optional<ListAction> list_menu(text_edit::EditState s, std::span<const std::pair<const char*, bool>> extra);

// The messages a record list sends to its view.
struct ListMsgs {
	// The selection moved.
	msg::Msg (*picked)();
	// A right click: the list menu.
	msg::Msg (*menu)();
	// The clipboard text of a Paste into the list.
	msg::Msg (*paste)(std::string);
	// A printable key, when the view takes one.
	std::optional<msg::Msg (*)(char32_t)> typed;
	// A double click on a row, when the view opens one.
	std::optional<msg::Msg (*)()> open;
	// A left click on a cell: its row, its column, and true for the second click of a double click.
	std::optional<msg::Msg (*)(int, int, bool)> cell;
};

// The height of a row of every record list, as Palm Desktop 3.1.1.
inline constexpr int ROW_H = 16;

// A record list: a Fl_Table_Row with one column. FLTK's cursor2rowcol is private, so the cell under the pointer is
// found here, through the protected members of the table. The lists set both headers off, as every record list does.
class RecordTable : public Fl_Table_Row {
public:
	// A list with no rows yet, `ncols` columns of rows `row_h` high, selection `mode`, both headers off, and a white
	// inside. The table gets the sunken edge as a frame: Fl_Table::draw draws its box on every draw, the partial one of
	// a drag-select too, which repaints only the rows whose selection changed, so a filled box blanked every other row.
	// The table is ended, so no later widget joins it, and a subclass that adds a child widget must begin() it first.
	RecordTable(int x, int y, int w, int h, TableRowSelectMode mode, int row_h = ROW_H, int ncols = 1);

	// Sets the number of rows, each `row_h` high. FLTK gives a new row the last row's height, or 25 px when the
	// table had none, so a fill of an empty list first makes one row `row_h` high, and the rest copy it in one pass.
	void rows(int n) override;
	using Fl_Table_Row::rows;

	// The height of every row.
	int row_h() const { return row_h_; }

	// The row and the column of the cell under the pointer. No value outside the cells, so the blank area below the
	// last row is no row.
	std::optional<std::pair<int, int>> cell_under_cursor();

	// Fl_Table_Row::handle, except that a left press on the blank area below the rows goes no further. FLTK clears the
	// selection when a press and its release both fall there; a list keeps its selection, as a Windows list does.
	// Every subclass's handle calls this in place of Fl_Table_Row::handle.
	int table_handle(int event);

private:
	int row_h_;
};

// Fits the one column of a record list to the width inside its edge and its scroll bar. Call it at the start of each
// page draw.
void fit_column(RecordTable& t);

// The events of a record list: a right click selects the row under the pointer and opens the list menu, a click or
// an arrow key moves the lead row, a double click opens the row, a printable key goes to the view, and a paste event
// brings the clipboard text of Paste. `lead` is the lead row, shared with the view as the Rust Rc<Cell> is.
bool list_event(RecordTable& t, int ev, const std::shared_ptr<int>& lead, msg::Sender s, ListMsgs m);

// The selected rows of a list of `n` rows. FLTK's row_selected is not const, so the table is not const.
std::vector<std::size_t> selected_rows(Fl_Table_Row& t, std::size_t n);

// Selects row `row` alone, or nothing, and makes it the lead row.
void pick_row(Fl_Table_Row& t, int& lead, std::optional<std::size_t> row);

} // namespace palm::records
