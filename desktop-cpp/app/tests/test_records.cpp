// links: assets draw msg text_edit records
// The test of desktop-rs/app/src/records.rs: the kept records come back only for their own text and kind.
#include "palm/records.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Window.H>
#include <doctest/doctest.h>

#include <optional>
#include <utility>
#include <vector>

namespace records = palm::records;
namespace model = palm::model;
namespace runner = palm::runner;

TEST_CASE("paste takes the kept records only for their own text and kind") {
	model::Memo m;
	m.text = "Groceries";
	const std::vector<runner::Record> want{runner::Record{m}};
	records::keep("memo", want, "Groceries");
	CHECK(records::records_for("memo", "Groceries") == want);
	CHECK_FALSE(records::records_for("memo", "Groceries\n").has_value());
	CHECK_FALSE(records::records_for("todo", "Groceries").has_value());
}

namespace {

// Puts the pointer at (x, y) with the left button, as FLTK's event loop does ere it delivers a mouse event.
void left_at(int x, int y) {
	Fl::e_x = x;
	Fl::e_y = y;
	Fl::e_keysym = FL_Button + FL_LEFT_MOUSE;
	Fl::e_clicks = 0;
}

} // namespace

TEST_CASE("a click below the rows of a list hits no row and keeps the selection") {
	Fl_Window win(200, 120);
	records::RecordTable t(0, 0, 200, 120, Fl_Table_Row::SELECT_MULTI, 16);
	t.rows(3);
	t.col_width(0, 180);
	win.end();

	// The second row is the one under y = 24; nothing is under y = 100.
	left_at(20, 24);
	REQUIRE(t.cell_under_cursor() == std::optional<std::pair<int, int>>{std::pair{1, 0}});
	t.table_handle(FL_PUSH);
	t.table_handle(FL_RELEASE);
	REQUIRE(t.row_selected(1) == 1);

	left_at(20, 100);
	CHECK_FALSE(t.cell_under_cursor().has_value());
	t.table_handle(FL_PUSH);
	t.table_handle(FL_RELEASE);
	CHECK(t.row_selected(1) == 1);
}

TEST_CASE("a drag-select changes the swept rows alone, and the list's edge leaves the other rows drawn") {
	Fl_Window win(200, 120);
	records::RecordTable t(0, 0, 200, 120, Fl_Table_Row::SELECT_MULTI, 16);
	t.rows(5);
	t.col_width(0, 180);
	// The list is ended, so the next widget joins the window.
	CHECK(Fl_Group::current() == &win);
	win.end();

	// A drag redraws only the rows whose selection changed, and Fl_Table draws the box on that partial draw too: a
	// filled box would blank the rest, so the edge must be a frame.
	CHECK(t.box() == FL_DOWN_FRAME);

	// Every row is row_h high: the first rows of an empty list, the rows a later fill adds, and the rows of a list
	// filled again after a filter emptied it, which FLTK alone makes 25 px high.
	CHECK(t.row_h() == 16);
	CHECK(t.row_height(0) == 16);
	CHECK(t.row_height(4) == 16);
	t.rows(7);
	CHECK(t.row_height(6) == 16);
	t.rows(0);
	t.rows(3);
	CHECK(t.row_height(0) == 16);
	CHECK(t.row_height(2) == 16);
	t.rows(5);
	CHECK(t.row_height(4) == 16);

	// A press on row 1 and a drag to row 3 select rows 1 to 3.
	left_at(20, 24);
	t.table_handle(FL_PUSH);
	left_at(20, 56);
	t.table_handle(FL_DRAG);
	t.table_handle(FL_RELEASE);
	CHECK(t.row_selected(0) == 0);
	CHECK(t.row_selected(1) == 1);
	CHECK(t.row_selected(2) == 1);
	CHECK(t.row_selected(3) == 1);
	CHECK(t.row_selected(4) == 0);
}
