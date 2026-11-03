// links: model ical
#include "model_form.hpp"

#include "palm/days.hpp"
#include "palm/ical.hpp"
#include "palm/model.hpp"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <doctest/doctest.h>
#include <stdexcept>
#include <string>
#include <vector>

using namespace palm;

namespace {

// The "yyyy-MM-ddTHH:mm:ssZ" text of a vector as a UTC instant.
std::chrono::sys_seconds parse_instant(const std::string& text) {
	int y = 0;
	int mo = 0;
	int d = 0;
	int h = 0;
	int mi = 0;
	int sec = 0;
	if (std::sscanf(text.c_str(), "%d-%d-%dT%d:%d:%dZ", &y, &mo, &d, &h, &mi, &sec) != 6) {
		throw std::runtime_error("not an instant: " + text);
	}
	std::int64_t day = days::epoch_day(y, static_cast<std::uint32_t>(mo), static_cast<std::uint32_t>(d));
	return std::chrono::sys_seconds{std::chrono::seconds{day * 86'400 + h * 3600 + mi * 60 + sec}};
}

} // namespace

TEST_CASE("ical_vectors") {
	const vectors::Value v = vectors::load("ical");
	const vectors::Value& ex = v.at("export");
	std::vector<model::Event> events;
	for (const vectors::Value& x : vectors::arr(ex.at("events"))) {
		events.push_back(vectors::ev_from(x));
	}
	CHECK(ical::export_ical(events, parse_instant(vectors::s(ex.at("now")))) == vectors::s(ex.at("text")));
	for (const vectors::Value& x : vectors::arr(v.at("parse"))) {
		INFO(vectors::s(x.at("name")));
		const std::chrono::time_zone* zone = std::chrono::locate_zone(vectors::s(x.at("zone")));
		ical::Parsed p = ical::parse(vectors::s(x.at("text")), zone);
		CHECK(vectors::events_to(p.events) == x.at("events"));
		CHECK(static_cast<long long>(p.simplified) == vectors::i(x.at("simplified")));
	}
}

TEST_CASE("the_last_week_flag_picks_the_last_week_from_a_4th_week_date") {
	// October 25, 2026 is both the 4th and the last Sunday of October.
	model::Event fourth;
	fourth.title = "Club";
	fourth.date = days::epoch_day(2026, 10, 25);
	fourth.untimed = true;
	fourth.repeat = model::repeat::MONTH_DAY;
	model::Event last = fourth;
	last.week_days = model::repeat::LAST_WEEK;
	// November 2026 has five Sundays: the 4th is the 22nd, the last the 29th.
	CHECK(model::occurs_on(fourth, days::epoch_day(2026, 11, 22)));
	CHECK_FALSE(model::occurs_on(fourth, days::epoch_day(2026, 11, 29)));
	CHECK(model::occurs_on(last, days::epoch_day(2026, 11, 29)));
	CHECK_FALSE(model::occurs_on(last, days::epoch_day(2026, 11, 22)));
	CHECK(model::describe(last) == "The last Sunday of every month");
	const std::vector<model::Event> one{last};
	const std::string text = ical::export_ical(one, std::chrono::sys_seconds{});
	CHECK(text.contains("RRULE:FREQ=MONTHLY;BYDAY=-1SU\r\n"));
	const ical::Parsed back = ical::parse(text, std::chrono::locate_zone("UTC"));
	REQUIRE(back.events.size() == 1);
	CHECK(back.events[0].repeat == model::repeat::MONTH_DAY);
	CHECK(back.events[0].week_days == model::repeat::LAST_WEEK);
}

TEST_CASE("time_zone_gap_moves_forward_and_overlap_takes_earlier") {
	const std::chrono::time_zone* utc = std::chrono::locate_zone("UTC");
	// 02:30 on March 8, 2026 is in the gap of New York: it moves forward to 03:30 EDT, which is 07:30 UTC.
	const std::string gap = "BEGIN:VEVENT\r\nDTSTART;TZID=America/New_York:20260308T023000\r\nEND:VEVENT\r\n";
	const ical::Parsed g = ical::parse(gap, utc);
	REQUIRE(g.events.size() == 1);
	CHECK(g.events[0].start == 7 * 60 + 30);
	// 01:30 on November 1, 2026 is in the overlap: the earlier instant, EDT, which is 05:30 UTC.
	const std::string fold_text = "BEGIN:VEVENT\r\nDTSTART;TZID=America/New_York:20261101T013000\r\nEND:VEVENT\r\n";
	const ical::Parsed f = ical::parse(fold_text, utc);
	REQUIRE(f.events.size() == 1);
	CHECK(f.events[0].start == 5 * 60 + 30);
}

TEST_CASE("a_time_past_jiffs_timestamp_range_drops_the_event") {
	const std::chrono::time_zone* utc = std::chrono::locate_zone("UTC");
	// 23:59:59 on December 31, 9999 in Kolkata is 18:29:59 UTC that day, past the last Timestamp
	// (9999-12-30T22:00:00Z).
	const std::string kolkata_past = "BEGIN:VEVENT\r\nDTSTART;TZID=Asia/Kolkata:99991231T235959\r\nEND:VEVENT\r\n";
	CHECK(ical::parse(kolkata_past, utc).events.empty());
	const std::string utc_past = "BEGIN:VEVENT\r\nDTSTART:99991231T235959Z\r\nEND:VEVENT\r\n";
	CHECK(ical::parse(utc_past, utc).events.empty());
	// 12:00 on December 30, 9999 in Kolkata is 06:30 UTC that day, inside the range.
	const std::string kolkata_in = "BEGIN:VEVENT\r\nDTSTART;TZID=Asia/Kolkata:99991230T120000\r\nEND:VEVENT\r\n";
	const ical::Parsed k = ical::parse(kolkata_in, utc);
	REQUIRE(k.events.size() == 1);
	CHECK(k.events[0].date == days::epoch_day(9999, 12, 30));
	CHECK(k.events[0].start == 6 * 60 + 30);
}
