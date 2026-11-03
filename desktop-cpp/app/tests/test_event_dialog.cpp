// links: assets draw msg mnemonics platform_ui dialogs combo text_edit note_dialog date_picker date_shared event_dialog
// The tests of desktop-rs/app/src/event_dialog.rs that need no display: the time fields and the date fields.
#include "palm/event_dialog.hpp"

#include "palm/days.hpp"

#include <doctest/doctest.h>

namespace event_dialog = palm::event_dialog;
namespace days = palm::days;

TEST_CASE("time fields parse as the Swing form reads them") {
	CHECK(event_dialog::parse_time("8:00a") == 480);
	CHECK(event_dialog::parse_time("8a") == 480);
	CHECK(event_dialog::parse_time("1:30 PM") == 810);
	CHECK(event_dialog::parse_time("13:30") == 810);
	CHECK(event_dialog::parse_time("830") == 510);
	CHECK(event_dialog::parse_time("12:15am") == 15);
	CHECK(event_dialog::parse_time("12p") == 720);
	CHECK(event_dialog::parse_time("13p") == std::nullopt);
	CHECK(event_dialog::parse_time("8:75") == std::nullopt);
	CHECK(event_dialog::parse_time("24:00") == std::nullopt);
	CHECK(event_dialog::parse_time("8:0") == std::nullopt);
	CHECK(event_dialog::parse_time("noon") == std::nullopt);
}

TEST_CASE("date fields take two and four digit years") {
	CHECK(event_dialog::parse_short_date("10/21/2026") == days::epoch_day(2026, 10, 21));
	CHECK(event_dialog::parse_short_date(" 10/19/26 ") == days::epoch_day(2026, 10, 19));
	CHECK(event_dialog::parse_short_date("1/2/70") == days::epoch_day(1970, 1, 2));
	CHECK(event_dialog::parse_short_date("2/30/2026") == std::nullopt);
	CHECK(event_dialog::parse_short_date("10/21/026") == std::nullopt);
	CHECK(event_dialog::parse_short_date("10-21-2026") == std::nullopt);
	CHECK(event_dialog::full_date(days::epoch_day(2026, 5, 3)) == "5/3/2026");
}
