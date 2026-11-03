// links: assets draw mnemonics platform_ui dialogs date_picker
// The test of desktop-rs/app/src/date_picker.rs: the weeks and the months move as the calendar does.
#include "palm/date_picker.hpp"

#include "palm/days.hpp"

#include <doctest/doctest.h>

#include <cstdint>

namespace dp = palm::date_picker;
namespace days = palm::days;

TEST_CASE("weeks and months move as the calendar does") {
	// 2026-10-23 is a Friday.
	const std::int64_t fri = days::epoch_day(2026, 10, 23);
	CHECK_EQ(dp::week_start_of(fri, 0), days::epoch_day(2026, 10, 18));
	CHECK_EQ(dp::week_start_of(fri, 1), days::epoch_day(2026, 10, 19));
	CHECK_EQ(dp::add_months(days::epoch_day(2026, 1, 31), 1), days::epoch_day(2026, 2, 28));
	CHECK_EQ(dp::add_months(days::epoch_day(2026, 1, 15), -13), days::epoch_day(2024, 12, 15));
	CHECK_EQ(dp::add_months(days::epoch_day(2024, 2, 29), 12), days::epoch_day(2025, 2, 28));
	// October 2026 starts on a Thursday, so its Sunday calendar starts on September 27.
	CHECK_EQ(dp::detail::calendar_start(fri, 0), days::epoch_day(2026, 9, 27));
}
