// The Palm Desktop date dialog of DatePicker.kt, as desktop-rs/app/src/date_picker.rs: Select Date, Select Week, or
// Select Month. The year with its arrows, the twelve month buttons, and (except for a month) a calendar of six weeks.
#pragma once

#include <cstdint>
#include <optional>

namespace palm::date_picker {

// What the dialog picks.
enum class Mode {
	Day,
	Week,
	Month,
};

// The epoch day of the first day of the week that holds `day`; `begins` is 0 for Sunday, 1 for Monday.
std::int64_t week_start_of(std::int64_t day, std::uint32_t begins);

// `day` moved by `months`, on the same day of the month or the last day of a shorter month.
std::int64_t add_months(std::int64_t day, std::int64_t months);

// Shows the dialog for `mode` on `day`, and returns the chosen epoch day, or nullopt on Cancel. `today` is drawn red;
// `begins` is the first day of the week (0 Sunday, 1 Monday).
std::optional<std::int64_t> pick(Mode mode, std::int64_t day, std::int64_t today, std::uint32_t begins);

namespace detail {

// The epoch day of the first cell of the calendar of the month that holds `day`, from the start of its first week.
// Exposed for test_date_picker.cpp.
std::int64_t calendar_start(std::int64_t day, std::uint32_t begins);

} // namespace detail

} // namespace palm::date_picker
