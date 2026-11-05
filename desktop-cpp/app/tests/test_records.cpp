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
	records::RecordTable t(0, 0, 200, 120);
	t.type(Fl_Table_Row::SELECT_MULTI);
	t.col_header(0);
	t.row_header(0);
	t.rows(3);
	t.cols(1);
	t.row_height_all(16);
	t.col_width(0, 180);
	t.end();
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
	records::RecordTable t(0, 0, 200, 120);
	t.type(Fl_Table_Row::SELECT_MULTI);
	t.col_header(0);
	t.row_header(0);
	t.rows(5);
	t.cols(1);
	t.row_height_all(16);
	t.col_width(0, 180);
	t.end();
	win.end();

	// A drag redraws only the rows whose selection changed, and Fl_Table draws the box on that partial draw too: a
	// filled box would blank the rest, so the edge must be a frame.
	CHECK(t.box() == FL_DOWN_FRAME);

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
