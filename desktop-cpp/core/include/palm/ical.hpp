// iCalendar (RFC 5545) files of Date Book events, as desktop-rs/core/src/ical.rs. Date Book has no time zones, so
// export writes floating local times, and import changes UTC and TZID times to the given time zone.
#pragma once

#include "palm/model.hpp"

#include <chrono>
#include <cstddef>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace palm::ical {

// Writes `events` as one VCALENDAR with CRLF line ends. `now` is the DTSTAMP. The Rust name is `export`, which
// C++20 reserves as a keyword.
std::string export_ical(std::span<const model::Event> events, std::chrono::sys_seconds now);

// Escapes a text value: backslash, semicolon, and comma get a backslash, CR is dropped, and LF becomes "\n".
std::string escape(std::string_view s);

// Appends `s` as content lines of at most 75 octets, each with a CRLF end.
void fold(std::string_view s, std::string& out);

// Reverses escape: "\n" and "\N" become LF, and any other escaped character stands for itself.
std::string unescape(std::string_view s);

// The events of a file, and the number of file events that Date Book could not show in full.
struct Parsed {
	std::vector<model::Event> events;
	std::size_t simplified = 0;
};

// Reads every VEVENT of `text`; UTC and TZID times change to local times in `zone`.
Parsed parse(std::string_view text, const std::chrono::time_zone* zone);

} // namespace palm::ical
