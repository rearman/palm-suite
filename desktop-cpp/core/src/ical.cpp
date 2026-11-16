#include "palm/ical.hpp"

#include "palm/days.hpp"
#include "palm/model.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <format>
#include <map>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::ical {

namespace {

constexpr std::array<std::string_view, 7> DAY_CODES = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
constexpr std::array<std::string_view, 8> RULE_PARTS = {"FREQ",  "INTERVAL",   "UNTIL",   "COUNT",
                                                        "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST"};
constexpr std::int64_t DAY_SECS = 86'400;
// The days that export writes, 0001-01-01 to 9999-12-31: a date has four digits of year.
constexpr std::int64_t EXPORT_FIRST = -719'162;
constexpr std::int64_t EXPORT_LAST = 2'932'896;
// jiff's Timestamp range in Unix seconds: -009999-01-02T01:59:59Z and 9999-12-30T22:00:00Z (the last second).
constexpr std::int64_t TIMESTAMP_MIN = -377'705'023'201;
constexpr std::int64_t TIMESTAMP_MAX = 253'402'207'200;

// The first `n` code points of `s`, as bytes.
std::string_view first_chars(std::string_view s, std::size_t n) {
	std::size_t i = 0;
	std::size_t count = 0;
	while (i < s.size() && count < n) {
		i += text::decode_at(s, i).len;
		++count;
	}
	return s.substr(0, i);
}

// Rust's str::chars().count(), for valid UTF-8: every byte that does not continue a code point.
std::size_t code_point_count(std::string_view s) {
	std::size_t n = 0;
	for (char c : s) {
		if ((static_cast<unsigned char>(c) & 0xC0) != 0x80) {
			++n;
		}
	}
	return n;
}

// Floor division and remainder for a divisor above zero, as Rust's div_euclid and rem_euclid give them.
std::int64_t floor_div(std::int64_t a, std::int64_t b) {
	std::int64_t q = a / b;
	if (a % b < 0) {
		--q;
	}
	return q;
}

std::int64_t floor_mod(std::int64_t a, std::int64_t b) {
	return a - floor_div(a, b) * b;
}

// Local date-times, as seconds after 1970-01-01 00:00 with no zone.

std::int64_t day_of(std::int64_t t) {
	return floor_div(t, DAY_SECS);
}

std::int32_t hour_of(std::int64_t t) {
	return static_cast<std::int32_t>(floor_mod(t, DAY_SECS) / 3600);
}

std::int32_t minute_of(std::int64_t t) {
	return static_cast<std::int32_t>(floor_mod(t, DAY_SECS) % 3600 / 60);
}

bool is_midnight(std::int64_t t) {
	return floor_mod(t, DAY_SECS) == 0;
}

std::string ymd_text(std::int64_t day) {
	days::Ymd d = days::ymd(day);
	return std::format("{:04}{:02}{:02}", d.year, d.month, d.day);
}

// "yyyyMMdd'T'HHmmss" of a local time.
std::string time_text(std::int64_t t) {
	std::int64_t s = floor_mod(t, DAY_SECS);
	return std::format("{}T{:02}{:02}{:02}", ymd_text(day_of(t)), s / 3600, s % 3600 / 60, s % 60);
}

// The value of a string of ASCII digits, or nullopt for an empty string, a non-digit, or a value above 32 bits.
std::optional<std::uint32_t> digits(std::string_view s) {
	if (s.empty()) {
		return std::nullopt;
	}
	std::uint64_t v = 0;
	for (char c : s) {
		if (c < '0' || c > '9') {
			return std::nullopt;
		}
		v = v * 10 + static_cast<std::uint64_t>(c - '0');
		if (v > UINT32_MAX) {
			return std::nullopt;
		}
	}
	return static_cast<std::uint32_t>(v);
}

// Rust's str::parse::<i32>: an optional sign, then digits, within the range of 32 bits.
std::optional<std::int32_t> parse_i32(std::string_view s) {
	bool negative = false;
	if (!s.empty() && (s[0] == '+' || s[0] == '-')) {
		negative = s[0] == '-';
		s.remove_prefix(1);
	}
	if (s.empty()) {
		return std::nullopt;
	}
	std::int64_t v = 0;
	for (char c : s) {
		if (c < '0' || c > '9') {
			return std::nullopt;
		}
		v = v * 10 + (c - '0');
		if (v > 2'147'483'648LL) {
			return std::nullopt;
		}
	}
	if (negative) {
		v = -v;
	}
	if (v < INT32_MIN || v > INT32_MAX) {
		return std::nullopt;
	}
	return static_cast<std::int32_t>(v);
}

// A string of ASCII digits as an i64, or 0 when the value does not fit, as the Rust duration reader gives it.
std::int64_t digits_or_zero(std::string_view s) {
	std::int64_t v = 0;
	for (char c : s) {
		std::int64_t d = c - '0';
		if (v > (INT64_MAX - d) / 10) {
			return 0;
		}
		v = v * 10 + d;
	}
	return v;
}

// A "yyyyMMdd" date, as Java's smart resolver reads it: a day 29 to 31 past the month end becomes the last day
// of the month.
std::optional<std::int64_t> parse_date(std::string_view s) {
	if (s.size() != 8) {
		return std::nullopt;
	}
	auto y = digits(s.substr(0, 4));
	auto m = digits(s.substr(4, 2));
	auto d = digits(s.substr(6, 2));
	if (!y || !m || !d) {
		return std::nullopt;
	}
	if (*m < 1 || *m > 12 || *d < 1 || *d > 31) {
		return std::nullopt;
	}
	auto year = static_cast<std::int64_t>(*y);
	return days::epoch_day(year, *m, std::min(*d, days::month_length(year, *m)));
}

// A "yyyyMMdd'T'HHmmss" local time. 24:00:00 is the midnight that ends the day.
std::optional<std::int64_t> parse_time(std::string_view s) {
	if (s.size() != 15 || s.substr(8, 1) != "T") {
		return std::nullopt;
	}
	auto day = parse_date(s.substr(0, 8));
	if (!day) {
		return std::nullopt;
	}
	auto h = digits(s.substr(9, 2));
	auto mi = digits(s.substr(11, 2));
	auto sec = digits(s.substr(13, 2));
	if (!h || !mi || !sec) {
		return std::nullopt;
	}
	if (*mi > 59 || *sec > 59 || *h > 24 || (*h == 24 && (*mi > 0 || *sec > 0))) {
		return std::nullopt;
	}
	return *day * DAY_SECS + static_cast<std::int64_t>(*h * 3600 + *mi * 60 + *sec);
}

// The local time of `t` as a civil date-time. jiff's civil range is the years -9999 to 9999, so a time past it
// gives nullopt. convert checks the instant against jiff's Timestamp range after the zone step.
std::optional<std::chrono::local_seconds> to_civil(std::int64_t t) {
	days::Ymd d = days::ymd(day_of(t));
	if (d.year < -9999 || d.year > 9999) {
		return std::nullopt;
	}
	return std::chrono::local_seconds{std::chrono::seconds{t}};
}

// The instant of local time `local` in `zone`, by jiff's "compatible" rule: a time in a gap moves forward by the
// gap, so it takes the offset from before the gap, and a time in an overlap takes the earlier instant.
std::chrono::sys_seconds to_instant(const std::chrono::time_zone* zone, std::chrono::local_seconds local) {
	auto info = zone->get_info(local);
	if (info.result == std::chrono::local_info::nonexistent) {
		return std::chrono::sys_seconds{local.time_since_epoch()} - info.first.offset;
	}
	return zone->to_sys(local, std::chrono::choose::earliest);
}

// The UTC zone, as Rust's TimeZone::UTC.
const std::chrono::time_zone* utc_zone() {
	static const std::chrono::time_zone* zone = std::chrono::locate_zone("UTC");
	return zone;
}

// The zone of an IANA name, or nullptr when there is none. jiff's lookup ignores ASCII case, so a name that
// locate_zone rejects is retried case-insensitively over the zones and links of the tz database. Shortcut: a
// linear scan of about 600 names on each miss. Upgrade path: a map of the lower-case names, built once.
const std::chrono::time_zone* find_zone(std::string_view id) {
	try {
		return std::chrono::locate_zone(id);
	} catch (const std::runtime_error&) {
		// The name is not in the database; the search below may still find it under another case.
	}
	for (const std::chrono::time_zone& z : std::chrono::get_tzdb().zones) {
		if (text::eq_ignore_ascii_case(z.name(), id)) {
			return std::chrono::locate_zone(z.name());
		}
	}
	for (const std::chrono::time_zone_link& l : std::chrono::get_tzdb().links) {
		if (text::eq_ignore_ascii_case(l.name(), id)) {
			return std::chrono::locate_zone(l.name());
		}
	}
	return nullptr;
}

// The local time in `to` of local time `t` in `from`. A time in a gap moves forward by the gap; a time in an
// overlap takes the earlier offset, as Java's atZone.
std::optional<std::int64_t> convert(std::int64_t t, const std::chrono::time_zone* from,
                                    const std::chrono::time_zone* to) {
	auto civil = to_civil(t);
	if (!civil) {
		return std::nullopt;
	}
	std::chrono::sys_seconds sys = to_instant(from, *civil);
	// jiff's Timestamp holds instants from -009999-01-02T01:59:59Z to 9999-12-30T22:00:00.999999999Z. A conversion
	// past either end fails in jiff, so the caller drops the event.
	if (sys.time_since_epoch().count() < TIMESTAMP_MIN || sys.time_since_epoch().count() > TIMESTAMP_MAX) {
		return std::nullopt;
	}
	return static_cast<std::int64_t>(to->to_local(sys).time_since_epoch().count());
}

// The day of occurrence number `count` of `e`. Shortcut: a scan day by day, which stops after 100 years.
// Upgrade path: step by the repeat unit.
std::int64_t count_end(const model::Event& e, std::int32_t count) {
	std::int32_t left = count;
	std::int64_t stop = e.date + 36'525;
	for (std::int64_t d = e.date; d < stop; ++d) {
		if (model::occurs_on(e, d)) {
			--left;
			if (left == 0) {
				return d;
			}
		}
	}
	return stop;
}

// BYDAY of a "Nth weekday of the month" repeat: "3MO", or "-1MO" for the last one.
std::string week_code(const model::Event& e) {
	std::uint32_t w = model::month_week(e);
	std::string n = w == 5 ? std::string("-1") : std::to_string(w);
	return n + std::string{DAY_CODES[days::dow(e.date)]};
}

// The RRULE value of `e`, or nullopt for a one-time event.
std::optional<std::string> rule(const model::Event& e) {
	std::string r = "FREQ=";
	switch (e.repeat) {
	case model::repeat::DAY:
		r += "DAILY";
		break;
	case model::repeat::WEEK: {
		std::int32_t mask = e.week_days == 0 ? (static_cast<std::int32_t>(1) << days::dow(e.date)) : e.week_days;
		std::string list;
		for (std::int32_t i = 0; i < 7; ++i) {
			if ((mask & (1 << i)) != 0) {
				if (!list.empty()) {
					list += ',';
				}
				list += std::string{DAY_CODES[static_cast<std::size_t>(i)]};
			}
		}
		r += "WEEKLY;WKST=SU;BYDAY=" + list;
		break;
	}
	case model::repeat::MONTH_DAY:
		r += "MONTHLY;BYDAY=" + week_code(e);
		break;
	case model::repeat::MONTH_DATE:
		r += std::format("MONTHLY;BYMONTHDAY={}", days::ymd(e.date).day);
		break;
	case model::repeat::YEAR:
		r += "YEARLY";
		break;
	default:
		return std::nullopt;
	}
	if (e.every > 1) {
		r += std::format(";INTERVAL={}", e.every);
	}
	if (e.repeat_end >= 0 && e.repeat_end <= EXPORT_LAST) {
		r += ";UNTIL=";
		if (e.untimed) {
			r += ymd_text(e.repeat_end);
		} else {
			r += time_text(e.repeat_end * DAY_SECS + 23 * 3600 + 59 * 60 + 59);
		}
	}
	return r;
}

// The parameters of a property, by upper-case name.
using Params = std::map<std::string, std::string>;

// One content line, as name, parameters, and value.
struct Prop {
	std::string name;
	Params params;
	std::string value;
};

// One VEVENT, as one Date Book event for each day it covers. An item with a recurrence day changes one
// occurrence of the series with the same uid.
struct Item {
	std::string uid;
	std::optional<std::int64_t> recurrence_day;
	std::vector<model::Event> events;
	bool simplified = false;
};

// The parts of a DURATION or TRIGGER value: `[+-]P[nW][nD][T[nH][nM][nS]]`. A part that is there holds its
// number, or 0 when the number does not fit.
struct Duration {
	bool negative = false;
	std::optional<std::int64_t> weeks;
	std::optional<std::int64_t> days;
	std::optional<std::int64_t> hours;
	std::optional<std::int64_t> minutes;
	std::optional<std::int64_t> seconds;
};

// Returns the duration of `s`, or nullopt when it is not one.
std::optional<Duration> duration(std::string_view s) {
	Duration d;
	std::string_view rest = s;
	if (rest.starts_with('+')) {
		rest.remove_prefix(1);
	} else if (rest.starts_with('-')) {
		rest.remove_prefix(1);
		d.negative = true;
	}
	if (!rest.starts_with('P')) {
		return std::nullopt;
	}
	rest.remove_prefix(1);
	// Each part may come once and in order: W, D, then after T: H, M, S.
	int stage = 0;
	bool timed = false;
	while (!rest.empty()) {
		if (!timed && rest.starts_with('T')) {
			timed = true;
			stage = 2;
			rest.remove_prefix(1);
			continue;
		}
		std::size_t n = 0;
		while (n < rest.size() && rest[n] >= '0' && rest[n] <= '9') {
			++n;
		}
		if (n == 0 || n == rest.size()) {
			return std::nullopt;
		}
		std::int64_t value = digits_or_zero(rest.substr(0, n));
		char unit = rest[n];
		int slot = 0;
		if (!timed && unit == 'W') {
			slot = 1;
		} else if (!timed && unit == 'D') {
			slot = 2;
		} else if (timed && unit == 'H') {
			slot = 3;
		} else if (timed && unit == 'M') {
			slot = 4;
		} else if (timed && unit == 'S') {
			slot = 5;
		} else {
			return std::nullopt;
		}
		if (slot <= stage) {
			return std::nullopt;
		}
		stage = slot;
		switch (slot) {
		case 1:
			d.weeks = value;
			break;
		case 2:
			d.days = value;
			break;
		case 3:
			d.hours = value;
			break;
		case 4:
			d.minutes = value;
			break;
		default:
			d.seconds = value;
			break;
		}
		rest.remove_prefix(n + 1);
	}
	return d;
}

// Signed minutes of a duration, or nullopt when they do not fit in 64 bits, as Kotlin's Math.multiplyExact and
// addExact fail.
std::optional<std::int64_t> minutes(const Duration& d) {
	const std::array<std::pair<std::int64_t, std::int64_t>, 5> parts = {{{d.weeks.value_or(0), 10'080},
	                                                                     {d.days.value_or(0), 1440},
	                                                                     {d.hours.value_or(0), 60},
	                                                                     {d.minutes.value_or(0), 1},
	                                                                     {d.seconds.value_or(0) / 60, 1}}};
	std::int64_t v = 0;
	for (const auto& [n, per] : parts) {
		std::int64_t m = 0;
		if (__builtin_mul_overflow(n, per, &m) || __builtin_add_overflow(v, m, &v)) {
			return std::nullopt;
		}
	}
	return d.negative ? -v : v;
}

// The end of a DURATION from local time `start`, or nullopt when it falls past the dates of Java's LocalDateTime
// (the years -999999999 to 999999999), where Kotlin's plusMinutes fails.
std::optional<std::int64_t> duration_end(std::int64_t start, const Duration& d) {
	constexpr std::int64_t FIRST_DAY = -365'243'219'162;
	constexpr std::int64_t LAST_DAY = 365'241'780'471;
	std::optional<std::int64_t> m = minutes(d);
	std::int64_t secs = 0;
	std::int64_t end = 0;
	if (!m || __builtin_mul_overflow(*m, std::int64_t{60}, &secs) || __builtin_add_overflow(start, secs, &end)) {
		return std::nullopt;
	}
	if (day_of(end) < FIRST_DAY || day_of(end) > LAST_DAY) {
		return std::nullopt;
	}
	return end;
}

// A TRIGGER value as Date Book (advance, unit), in the unit of the file.
std::pair<std::int32_t, std::int32_t> advance(std::string_view value) {
	std::optional<Duration> d = duration(text::trim(value));
	if (!d) {
		return {0, 0};
	}
	std::optional<std::int64_t> m = minutes(*d);
	if (!m) {
		return {0, 0};
	}
	std::int64_t before = -*m;
	if (before <= 0) {
		return {0, 0};
	}
	std::int32_t unit = 0;
	if (!d->hours && !d->minutes && !d->seconds) {
		unit = 2;
	} else if (!d->minutes && !d->seconds) {
		unit = 1;
	}
	constexpr std::array<std::int64_t, 3> per_unit = {1, 60, 1440};
	return {static_cast<std::int32_t>(before / per_unit[static_cast<std::size_t>(unit)]), unit};
}

// True when the value is a DATE: a VALUE=DATE parameter, or an 8-character value.
bool is_date(const Params& params, std::string_view v) {
	auto it = params.find("VALUE");
	if (it != params.end() && text::eq_ignore_ascii_case(it->second, "DATE")) {
		return true;
	}
	return code_point_count(text::trim(v)) == 8;
}

// A DATE or DATE-TIME value as the file writes it, with no zone change, or nullopt when it does not parse.
std::optional<std::int64_t> wall(const Params& params, std::string_view value) {
	std::string_view v = text::trim(value);
	if (is_date(params, v)) {
		auto d = parse_date(first_chars(v, 8));
		if (!d) {
			return std::nullopt;
		}
		return *d * DAY_SECS;
	}
	if (v.ends_with('Z')) {
		return parse_time(v.substr(0, v.size() - 1));
	}
	return parse_time(v);
}

// The zone of a DATE-TIME value: UTC for a "Z" time, else its TZID. nullptr for a DATE and a floating time.
// Shortcut: a TZID that is not an IANA zone name (Outlook writes Windows names) reads as local time.
// Upgrade path: read the offsets from the VTIMEZONE of the file.
const std::chrono::time_zone* source_zone(const Params& params, std::string_view value) {
	std::string_view v = text::trim(value);
	if (is_date(params, v)) {
		return nullptr;
	}
	if (v.ends_with('Z')) {
		return utc_zone();
	}
	auto it = params.find("TZID");
	return it != params.end() ? find_zone(it->second) : nullptr;
}

// A DATE or DATE-TIME value as a local time in `zone`, or nullopt when it does not parse.
std::optional<std::int64_t> time(const Params& params, std::string_view value, const std::chrono::time_zone* zone) {
	std::optional<std::int64_t> t = wall(params, value);
	if (!t) {
		return std::nullopt;
	}
	const std::chrono::time_zone* src = source_zone(params, value);
	if (src == nullptr) {
		return t;
	}
	return convert(*t, src, zone);
}

// True when zones `a` and `b` keep the offset between them that they have at `from` until `to`. Date Book keeps one
// wall time for a series, so a series from a zone whose offset to the device zone changes moves.
bool keeps_offset(const std::chrono::time_zone* a, const std::chrono::time_zone* b, std::chrono::sys_seconds from,
                  std::chrono::sys_seconds to) {
	auto diff = [a, b](std::chrono::sys_seconds t) { return a->get_info(t).offset - b->get_info(t).offset; };
	const std::chrono::seconds d0 = diff(from);
	std::chrono::sys_seconds t = from;
	for (;;) {
		std::chrono::sys_seconds next = std::min(a->get_info(t).end, b->get_info(t).end);
		if (next >= to) {
			return true;
		}
		if (diff(next) != d0) {
			return false;
		}
		t = next;
	}
}

// The parts of an RRULE value, by upper-case name, with upper-case values.
std::map<std::string, std::string> rule_map(std::string_view value) {
	std::map<std::string, std::string> r;
	for (std::string_view part : text::split(text::trim(value), ';')) {
		auto eq = part.find('=');
		if (eq != std::string_view::npos && eq > 0) {
			r[text::to_uppercase(part.substr(0, eq))] = text::to_uppercase(part.substr(eq + 1));
		}
	}
	return r;
}

// The first day of a week of rule `r` (0 = Sunday): its WKST, else Monday, as RFC 5545 has it.
std::int32_t week_start(const std::map<std::string, std::string>& r) {
	auto it = r.find("WKST");
	if (it != r.end()) {
		auto pos = std::find(DAY_CODES.begin(), DAY_CODES.end(), std::string_view(it->second));
		if (pos != DAY_CODES.end()) {
			return static_cast<std::int32_t>(pos - DAY_CODES.begin());
		}
	}
	return 1;
}

// True when Date Book's weeks, which start on Sunday, group the days of `mask` as weeks that start on day
// `week_start` do, after the days move `shift` days later.
bool same_weeks(std::int32_t mask, std::int32_t week_start, std::int64_t shift) {
	std::optional<std::int64_t> week;
	for (std::int32_t d = 0; d < 7; ++d) {
		if ((mask & (std::int32_t{1} << d)) == 0) {
			continue;
		}
		std::int64_t w = floor_div(week_start + floor_mod(d - week_start, 7) + shift, 7);
		if (week && *week != w) {
			return false;
		}
		week = w;
	}
	return true;
}

// The time of a property, or nullopt when there is no property or its value does not parse.
std::optional<std::int64_t> prop_time(const Prop* p, const std::chrono::time_zone* zone) {
	if (p == nullptr) {
		return std::nullopt;
	}
	return time(p->params, p->value, zone);
}

// Sets the repeat of `e` from the RRULE parts `r`. The rule reads against `src`, the DTSTART date as the file writes
// it; `e.date` is the start date in `zone`, which a UTC or TZID start can move by a day, and `start_secs` the start
// time there in seconds. True when Date Book shows the rule as the file means it; false when it shows it with a
// change, or cannot show it: `e` then stays a one-time event.
bool set_rule(model::Event& e, const std::map<std::string, std::string>& r, const std::chrono::time_zone* zone,
              std::int64_t src, std::int64_t start_secs) {
	static const Params no_params;
	for (const auto& [key, val] : r) {
		if (std::find(RULE_PARTS.begin(), RULE_PARTS.end(), std::string_view(key)) == RULE_PARTS.end()) {
			return false;
		}
	}
	days::Ymd s = days::ymd(src);
	days::Ymd local = days::ymd(e.date);
	std::int64_t delta = e.date - src;
	auto get = [&r](const char* key) -> std::optional<std::string> {
		auto it = r.find(key);
		if (it == r.end()) {
			return std::nullopt;
		}
		return it->second;
	};
	// A missing FREQ reads as "", which matches no FREQ below, as Rust's None does.
	std::string freq = get("FREQ").value_or("");
	std::int32_t every = 1;
	if (auto v = get("INTERVAL")) {
		every = parse_i32(*v).value_or(1);
	}
	if (every < 1) {
		return false;
	}
	std::optional<std::vector<std::string>> byday;
	if (auto v = get("BYDAY")) {
		std::vector<std::string> ds;
		for (std::string_view d : text::split(*v, ',')) {
			ds.emplace_back(d.starts_with('+') ? d.substr(1) : d);
		}
		byday = std::move(ds);
	}
	// Bit 8 marks a day with a number before it, such as "2MO".
	std::optional<std::int32_t> mask;
	if (byday) {
		std::int32_t m = 0;
		for (const std::string& d : *byday) {
			auto pos = std::find(DAY_CODES.begin(), DAY_CODES.end(), std::string_view(d));
			m |= pos == DAY_CODES.end() ? 0x100 : static_cast<std::int32_t>(1) << (pos - DAY_CODES.begin());
		}
		mask = m;
	}
	auto month_day = get("BYMONTHDAY");
	if (month_day && ((freq != "MONTHLY" && freq != "YEARLY") || *month_day != std::to_string(s.day))) {
		return false;
	}
	if (auto m = get("BYMONTH")) {
		if (freq != "YEARLY" || *m != std::to_string(s.month)) {
			return false;
		}
	}
	std::int32_t week_days = 0;
	std::int32_t rep = model::repeat::NONE;
	if (freq == "DAILY") {
		if (!byday) {
			rep = model::repeat::DAY;
		} else if (*mask < 0x100 && every == 1) {
			week_days = *mask;
			rep = model::repeat::WEEK;
		} else {
			return false;
		}
	} else if (freq == "WEEKLY") {
		if (!byday) {
			rep = model::repeat::WEEK;
		} else if (*mask < 0x100) {
			week_days = *mask;
			rep = model::repeat::WEEK;
		} else {
			return false;
		}
	} else if (freq == "MONTHLY") {
		if (!byday) {
			rep = model::repeat::MONTH_DATE;
		} else if (!month_day && byday->size() == 1) {
			model::Event plain = e;
			plain.date = src;
			plain.week_days = 0;
			model::Event last_week = plain;
			last_week.week_days = model::repeat::LAST_WEEK;
			if ((*byday)[0] == week_code(plain)) {
				rep = model::repeat::MONTH_DAY;
			} else if ((*byday)[0] == week_code(last_week)) {
				// "-1SU" from a first date in both the 4th and the last week: the last week.
				week_days = model::repeat::LAST_WEEK;
				rep = model::repeat::MONTH_DAY;
			} else {
				return false;
			}
		} else {
			return false;
		}
	} else if (freq == "YEARLY" && !byday) {
		rep = model::repeat::YEAR;
	} else {
		return false;
	}
	bool exact = true;
	model::Event t = e;
	t.repeat = rep;
	t.every = every;
	t.week_days = week_days;
	t.repeat_end = -1;
	if (delta != 0) {
		// The local date is a day off the file's: the week days move with it, and a month or year day holds only
		// when both days are in every month.
		bool same_month = local.month == s.month;
		std::uint32_t last = std::max(s.day, local.day);
		if (rep == model::repeat::WEEK) {
			if (week_days != 0) {
				t.week_days = model::rotate_week_days(week_days, delta);
			}
		} else if (rep == model::repeat::MONTH_DATE) {
			exact = exact && same_month && last <= 28;
		} else if (rep == model::repeat::MONTH_DAY) {
			exact = false;
		} else if (rep == model::repeat::YEAR) {
			exact = exact && same_month && !(s.month == 2 && last >= 29);
		}
	}
	// RFC 5545 counts DTSTART as an occurrence even off the BYDAY days. Date Book cannot show that start with the
	// series, so the series starts on its first day on the rule.
	bool off_start =
	    rep == model::repeat::WEEK && week_days != 0 && (week_days & (std::int32_t{1} << days::dow(src))) == 0;
	if (off_start) {
		t.date = model::first_week_day(src, week_days, every, week_start(r)) + delta;
	}
	bool start_only = false;
	if (auto u = get("UNTIL")) {
		auto x = time(no_params, *u, zone);
		if (!x) {
			return false;
		}
		// A DATE-TIME UNTIL is the last start that occurs, so a later start time ends the day before.
		bool late = !is_date(no_params, *u) && start_secs > floor_mod(*x, DAY_SECS);
		t.repeat_end = day_of(*x) - (late ? 1 : 0);
	}
	if (auto c = get("COUNT")) {
		auto count = parse_i32(*c);
		if (!count || *count <= 0) {
			return false;
		}
		std::int32_t n = *count - (off_start ? 1 : 0);
		if (n == 0) {
			start_only = true;
		} else {
			t.repeat_end = count_end(t, n);
		}
	}
	if (off_start) {
		// No occurrence on the rule: the start alone, a one-time event.
		if (start_only || (t.repeat_end >= 0 && t.repeat_end < t.date)) {
			return true;
		}
		exact = false;
	}
	e.date = t.date;
	e.repeat = t.repeat;
	e.every = t.every;
	e.week_days = t.week_days;
	e.repeat_end = t.repeat_end;
	return exact;
}

// Splits a content line into its name, parameters, and value, or nullopt when it has no colon outside quotes.
std::optional<Prop> prop(std::string_view line) {
	bool quoted = false;
	std::optional<std::size_t> colon;
	for (std::size_t i = 0; i < line.size(); ++i) {
		if (line[i] == '"') {
			quoted = !quoted;
		} else if (line[i] == ':' && !quoted) {
			colon = i;
			break;
		}
	}
	if (!colon) {
		return std::nullopt;
	}
	std::vector<std::string_view> head = text::split(line.substr(0, *colon), ';');
	Prop p;
	for (std::size_t k = 1; k < head.size(); ++k) {
		std::string_view part = head[k];
		auto eq = part.find('=');
		if (eq != std::string_view::npos && eq > 0) {
			std::string_view v = part.substr(eq + 1);
			while (v.starts_with('"')) {
				v.remove_prefix(1);
			}
			while (v.ends_with('"')) {
				v.remove_suffix(1);
			}
			p.params[text::to_uppercase(part.substr(0, eq))] = std::string(v);
		}
	}
	std::string_view name = head[0];
	auto dot = name.rfind('.');
	if (dot != std::string_view::npos) {
		name = name.substr(dot + 1);
	}
	p.name = text::to_uppercase(name);
	p.value = std::string(line.substr(*colon + 1));
	return p;
}

// The content lines of `text` with each folded continuation joined to its line, as Kotlin's ICal.unfold. A blank
// line is dropped.
std::vector<std::string> unfold(std::string_view text) {
	std::vector<std::string> out;
	for (const std::string_view l : lines(text)) {
		if (continues(l) && !out.empty()) {
			out.back() += l.substr(1);
		} else if (!model::is_blank(l)) {
			out.emplace_back(l);
		}
	}
	return out;
}

// Reads the events of one VEVENT. Returns nullopt when it has no DTSTART or its DTSTART does not parse.
std::optional<Item> item(const std::vector<Prop>& props, const Prop* trigger, const std::chrono::time_zone* zone) {
	auto first = [&props](std::string_view n) -> const Prop* {
		for (const Prop& p : props) {
			if (p.name == n) {
				return &p;
			}
		}
		return nullptr;
	};
	const Prop* ds = first("DTSTART");
	if (ds == nullptr) {
		return std::nullopt;
	}
	auto start_time = time(ds->params, ds->value, zone);
	if (!start_time) {
		return std::nullopt;
	}
	std::int64_t start = *start_time;
	bool untimed = is_date(ds->params, ds->value);
	// The zone and the time that DTSTART names. A UTC or TZID start can fall on another day here.
	const std::chrono::time_zone* src_zone = source_zone(ds->params, ds->value);
	std::optional<std::int64_t> written = wall(ds->params, ds->value);
	if (!written) {
		return std::nullopt;
	}
	std::int64_t day0 = day_of(start);
	std::int64_t src = src_zone == nullptr ? day0 : day_of(*written);
	std::int64_t delta = day0 - src;
	std::string uid;
	if (const Prop* p = first("UID")) {
		uid = std::string(text::trim(p->value));
	}
	std::optional<std::int64_t> rid;
	if (auto t = prop_time(first("RECURRENCE-ID"), zone)) {
		rid = day_of(*t);
	}
	if (const Prop* st = first("STATUS");
	    st != nullptr && text::eq_ignore_ascii_case(text::trim(st->value), "CANCELLED")) {
		return Item{uid, rid, {}, false};
	}
	std::optional<std::int64_t> end = prop_time(first("DTEND"), zone);
	if (!end) {
		// A DURATION whose end falls past the dates of Java's LocalDateTime counts as none.
		if (const Prop* d = first("DURATION")) {
			if (auto dur = duration(text::trim(d->value))) {
				end = duration_end(start, *dur);
			}
		}
	}
	if (!end) {
		end = untimed ? start + DAY_SECS : start;
	}
	model::Event e;
	if (const Prop* p = first("SUMMARY")) {
		e.title = unescape(p->value);
	}
	if (const Prop* p = first("DESCRIPTION")) {
		e.note = unescape(p->value);
	}
	e.date = day0;
	e.untimed = untimed;
	if (const Prop* p = first("CLASS")) {
		std::string cls = text::to_uppercase(text::trim(p->value));
		e.is_private = cls == "PRIVATE" || cls == "CONFIDENTIAL";
	}
	bool simplified = false;
	// Days that a timed event covers after its first day, and its end time on the last one. An end at midnight
	// covers no part of that day.
	std::int64_t span = 0;
	std::int32_t last_end = 0;
	if (!untimed) {
		bool midnight = *end > start && is_midnight(*end);
		span = std::max<std::int64_t>(0, day_of(*end) - (midnight ? 1 : 0) - e.date);
		last_end = midnight ? 24 * 60 - 1 : hour_of(*end) * 60 + minute_of(*end);
		e.start = hour_of(start) * 60 + minute_of(start);
		e.end = span > 0 ? 24 * 60 - 1 : std::max(e.start, last_end);
	}
	if (trigger != nullptr) {
		auto [n, unit] = advance(trigger->value);
		e.alarm = true;
		e.alarm_advance = n;
		e.alarm_unit = unit;
	}
	const Prop* rule_prop = first("RRULE");
	std::optional<std::map<std::string, std::string>> parts;
	if (rule_prop != nullptr) {
		parts = rule_map(rule_prop->value);
	}
	if (!rid && parts) {
		if (!set_rule(e, *parts, zone, src, floor_mod(start, DAY_SECS))) {
			simplified = true;
		}
		if (e.repeats() && src_zone != nullptr) {
			// Shortcut: the first year of the series only. Upgrade path: each year to its end.
			std::chrono::sys_seconds from =
			    to_instant(src_zone, std::chrono::local_seconds{std::chrono::seconds{*written}});
			std::chrono::sys_seconds to = from + std::chrono::seconds{366 * DAY_SECS};
			if (e.repeat_end >= 0) {
				to = std::min(to, to_instant(zone, std::chrono::local_seconds{
				                                       std::chrono::seconds{(e.repeat_end + 1) * DAY_SECS}}));
			}
			if (!keeps_offset(src_zone, zone, from, to)) {
				simplified = true;
			}
		}
	}
	std::int64_t day_count = day_of(*end) - day0;
	if (untimed && day_count > 1) {
		// A multi-day untimed event becomes a daily repeat over its days.
		if (rule_prop == nullptr && !rid) {
			e.repeat = model::repeat::DAY;
			e.repeat_end = e.date + day_count - 1;
		} else {
			simplified = true;
		}
	}
	if (e.repeats()) {
		for (const Prop& x : props) {
			if (x.name != "EXDATE") {
				continue;
			}
			for (std::string_view v : text::split(x.value, ',')) {
				if (auto t = time(x.params, v, zone)) {
					e.exceptions.insert(day_of(*t));
				}
			}
		}
	}
	if (first("RDATE") != nullptr) {
		simplified = true;
	}
	std::vector<model::Event> events{e};
	std::int32_t mask = e.week_days == 0 ? (static_cast<std::int32_t>(1) << days::dow(e.date)) : e.week_days;
	if (e.repeat == model::repeat::WEEK && e.every > 1 && parts) {
		// Date Book counts weeks from Sunday and the file from its WKST, in its own zone. Each day part is checked; a
		// part moves the days by one more day.
		std::int32_t src_mask = model::rotate_week_days(mask, -delta);
		std::int32_t wkst = week_start(*parts);
		for (std::int64_t k = 0; k <= std::min<std::int64_t>(span, 30); ++k) {
			if (!same_weeks(src_mask, wkst, delta + k)) {
				simplified = true;
			}
		}
	}
	if (span > 0) {
		// A timed event that goes past midnight becomes one event for each day it covers.
		// Shortcut: at most 31 days. Upgrade path: an end date in Event.
		for (std::int64_t k = 1; k <= std::min<std::int64_t>(span, 30); ++k) {
			model::Event part = e;
			part.date = e.date + k;
			part.start = 0;
			part.end = k == span ? last_end : 24 * 60 - 1;
			part.alarm = false;
			part.repeat_end = e.repeat_end >= 0 ? e.repeat_end + k : -1;
			part.week_days = e.repeat == model::repeat::WEEK ? model::rotate_week_days(mask, k) : e.week_days;
			part.exceptions.clear();
			for (std::int64_t x : e.exceptions) {
				part.exceptions.insert(x + k);
			}
			events.push_back(part);
		}
		if (span > 30 || (e.repeats() && e.repeat != model::repeat::DAY && e.repeat != model::repeat::WEEK)) {
			simplified = true;
		}
	}
	return Item{uid, rid, std::move(events), simplified};
}

} // namespace

std::string export_ical(std::span<const model::Event> events, std::chrono::sys_seconds now) {
	std::string out;
	std::string stamp = time_text(static_cast<std::int64_t>(now.time_since_epoch().count())) + "Z";
	auto at = [](std::int64_t day, std::int32_t min) {
		return time_text(day * DAY_SECS + static_cast<std::int64_t>(min) * 60);
	};
	auto line = [&out](std::string_view s) { fold(s, out); };
	line("BEGIN:VCALENDAR");
	line("VERSION:2.0");
	line("PRODID:-//rearman//Date Book//EN");
	auto in_range = [](std::int64_t day) { return day >= EXPORT_FIRST && day <= EXPORT_LAST; };
	for (std::size_t index = 0; index < events.size(); ++index) {
		const model::Event& e = events[index];
		if (!in_range(e.date)) {
			continue;
		}
		std::int64_t d = model::first_day(e);
		if (d > EXPORT_LAST) {
			continue;
		}
		if (e.repeats()) {
			model::Event plain = e;
			plain.exceptions.clear();
			if (!model::occurs_on(plain, d)) {
				continue;
			}
		}
		// The sync uid is the same on every device. A record without one keeps its row id, and an unsaved record gets
		// one from this export.
		std::string uid = !e.uid.empty() ? e.uid
		                  : e.id != 0    ? std::to_string(e.id)
		                                 : std::format("new-{}-{}", stamp, index);
		line("BEGIN:VEVENT");
		line("UID:" + escape(uid) + "@com.rearman.datebook");
		line("DTSTAMP:" + stamp);
		if (e.untimed) {
			line("DTSTART;VALUE=DATE:" + ymd_text(d));
			line("DTEND;VALUE=DATE:" + ymd_text(d + 1));
		} else {
			line("DTSTART:" + at(d, e.start));
			line("DTEND:" + at(d, std::max(e.start, e.end)));
		}
		line("SUMMARY:" + escape(e.title));
		if (!e.note.empty()) {
			line("DESCRIPTION:" + escape(e.note));
		}
		if (e.is_private) {
			line("CLASS:PRIVATE");
		}
		if (std::optional<std::string> r = rule(e)) {
			line("RRULE:" + *r);
		}
		if (e.repeats()) {
			for (std::int64_t x : e.exceptions) {
				if (!in_range(x)) {
					continue;
				}
				if (e.untimed) {
					line("EXDATE;VALUE=DATE:" + ymd_text(x));
				} else {
					line("EXDATE:" + at(x, e.start));
				}
			}
		}
		if (e.alarm) {
			std::int32_t n = e.alarm_advance;
			line("BEGIN:VALARM");
			line("ACTION:DISPLAY");
			line("DESCRIPTION:" + escape(e.title));
			if (e.alarm_unit == 0) {
				line(std::format("TRIGGER:-PT{}M", n));
			} else if (e.alarm_unit == 1) {
				line(std::format("TRIGGER:-PT{}H", n));
			} else {
				line(std::format("TRIGGER:-P{}D", n));
			}
			line("END:VALARM");
		}
		line("END:VEVENT");
	}
	line("END:VCALENDAR");
	return out;
}

std::string escape(std::string_view s) {
	std::string out;
	for (char c : s) {
		switch (c) {
		case '\\':
			out += "\\\\";
			break;
		case ';':
			out += "\\;";
			break;
		case ',':
			out += "\\,";
			break;
		case '\r':
			break;
		case '\n':
			out += "\\n";
			break;
		default:
			out += c;
			break;
		}
	}
	return out;
}

void fold(std::string_view s, std::string& out) {
	std::size_t n = 0;
	std::size_t i = 0;
	while (i < s.size()) {
		std::size_t bytes = text::decode_at(s, i).len;
		if (n + bytes > 75) {
			out += "\r\n ";
			n = 1;
		}
		out += s.substr(i, bytes);
		n += bytes;
		i += bytes;
	}
	out += "\r\n";
}

std::string unescape(std::string_view s) {
	std::string b;
	std::size_t i = 0;
	while (i < s.size()) {
		if (s[i] == '\\' && i + 1 < s.size()) {
			std::size_t len = text::decode_at(s, i + 1).len;
			if (s[i + 1] == 'n' || s[i + 1] == 'N') {
				b += '\n';
			} else {
				b += s.substr(i + 1, len);
			}
			i += 1 + len;
			continue;
		}
		std::size_t len = text::decode_at(s, i).len;
		b += s.substr(i, len);
		i += len;
	}
	return b;
}

Parsed parse(std::string_view text, const std::chrono::time_zone* zone) {
	std::vector<Item> items;
	std::vector<std::string> stack;
	std::vector<Prop> props;
	std::optional<Prop> trigger;
	for (const std::string& l : unfold(text)) {
		std::optional<Prop> parsed = prop(l);
		if (!parsed) {
			continue;
		}
		Prop p = std::move(*parsed);
		if (p.name == "BEGIN") {
			stack.push_back(text::to_uppercase(text::trim(p.value)));
			if (stack.back() == "VEVENT") {
				props.clear();
				trigger.reset();
			}
		} else if (p.name == "END") {
			bool was_vevent = !stack.empty() && stack.back() == "VEVENT";
			if (!stack.empty()) {
				stack.pop_back();
			}
			if (was_vevent) {
				if (std::optional<Item> it = item(props, trigger ? &*trigger : nullptr, zone)) {
					items.push_back(std::move(*it));
				}
			}
		} else {
			std::size_t n = stack.size();
			if (n > 0 && stack.back() == "VEVENT") {
				props.push_back(std::move(p));
			} else if (n >= 2 && stack[n - 1] == "VALARM" && stack[n - 2] == "VEVENT" && p.name == "TRIGGER" &&
			           !trigger) {
				trigger = std::move(p);
			}
		}
	}
	// A changed or cancelled occurrence becomes an exception of its series, in each day part.
	std::vector<std::pair<std::size_t, std::int64_t>> marks;
	for (const Item& o : items) {
		if (!o.recurrence_day || o.uid.empty()) {
			continue;
		}
		for (std::size_t s = 0; s < items.size(); ++s) {
			if (!items[s].recurrence_day && items[s].uid == o.uid) {
				marks.emplace_back(s, *o.recurrence_day);
				break;
			}
		}
	}
	for (const auto& [s, day] : marks) {
		std::int64_t k = 0;
		for (model::Event& ev : items[s].events) {
			if (ev.repeats()) {
				ev.exceptions.insert(day + k);
			}
			++k;
		}
	}
	Parsed out;
	for (const Item& it : items) {
		if (it.simplified) {
			++out.simplified;
		}
	}
	for (Item& it : items) {
		for (model::Event& e : it.events) {
			out.events.push_back(std::move(e));
		}
	}
	return out;
}

std::vector<std::string_view> lines(std::string_view text) {
	if (text.starts_with("\xEF\xBB\xBF")) {
		text.remove_prefix(3);
	}
	std::vector<std::string_view> out;
	std::size_t start = 0;
	for (;;) {
		const std::size_t nl = text.find('\n', start);
		std::string_view l = text.substr(start, nl == std::string_view::npos ? std::string_view::npos : nl - start);
		if (l.ends_with('\r')) {
			l.remove_suffix(1);
		}
		out.push_back(l);
		if (nl == std::string_view::npos) {
			return out;
		}
		start = nl + 1;
	}
}

bool continues(std::string_view l) {
	return l.starts_with(' ') || l.starts_with('\t');
}

} // namespace palm::ical
