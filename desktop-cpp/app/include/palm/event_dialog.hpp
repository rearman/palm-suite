// The Edit Event dialog of EventDialog.kt, after Palm Desktop 3.1.1, with its Change Repeat and Select Time
// dialogs, and the parsers of its time and date fields, as desktop-rs/app/src/event_dialog.rs.
#pragma once

#include "palm/model.hpp"
#include "palm/settings.hpp"
#include "palm/view.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace palm::event_dialog {

// Parses a time field such as "8:00a", "8a", "1:30 PM", "13:30" or "830" to minutes after midnight. Spaces and case
// do not count; with no suffix the hour is 24 h.
std::optional<std::int32_t> parse_time(std::string_view text);

// Parses a date field such as "10/21/2026" or "10/19/26" to an epoch day. Two-digit years 70 to 99 mean 19xx, the
// others 20xx.
std::optional<std::int64_t> parse_short_date(std::string_view text);

// The Windows short date with a four-digit year and no leading zeros: "5/3/2026".
std::string full_date(std::int64_t day);

// Opens Edit Event for `orig`, the occurrence on `day`, or for a new event on `day` when `orig` is empty. A new event
// starts at `start_min`, or is untimed at 8:00 am when it is empty; `title` is its description.
void edit(view::Ctx& ctx, std::optional<model::Event> orig, std::int64_t day, std::optional<std::int32_t> start_min,
          std::string_view title);

// The Change Repeat dialog. Edits a copy of the repeat fields of `e`, and writes them back on OK (true).
bool change_repeat(model::Event& e, const settings::Settings& settings);

// The Select Time dialog. Edits the time of `t`; true on OK or No Time (which makes `t` untimed).
bool select_time(model::Event& t, bool h24);

// The Purge Events dialog: deletes the events that ended more than the chosen span ago, and reports the count.
void purge_events(view::Ctx& ctx);

} // namespace palm::event_dialog
