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
