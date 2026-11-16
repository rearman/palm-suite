// links: model ical
#include "model_form.hpp"

#include "palm/days.hpp"
#include "palm/ical.hpp"
#include "palm/model.hpp"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <doctest/doctest.h>
#include <initializer_list>
#include <stdexcept>
#include <string>
#include <string_view>
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

namespace {

// One VEVENT of `lines` in a VCALENDAR.
std::string cal(std::initializer_list<std::string_view> lines) {
	std::string out = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n";
	for (std::string_view l : lines) {
		out += l;
		out += "\r\n";
	}
	return out + "END:VEVENT\r\nEND:VCALENDAR\r\n";
}

const std::chrono::time_zone* chicago() {
	return std::chrono::locate_zone("America/Chicago");
}

} // namespace

TEST_CASE("a_utc_start_on_the_day_before_moves_the_week_days") {
	// 03:00 UTC on Tuesday November 3, 2026 is 21:00 on Monday in Chicago.
	const ical::Parsed p = ical::parse(cal({"DTSTART:20261103T030000Z", "RRULE:FREQ=WEEKLY;BYDAY=TU"}), chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].date == days::epoch_day(2026, 11, 2));
	CHECK(p.events[0].start == 21 * 60);
	CHECK(p.events[0].week_days == 1 << 1);
	CHECK(model::occurs_on(p.events[0], days::epoch_day(2026, 11, 9)));
	// UTC has no daylight saving time and Chicago has, so the wall time moves in March.
	CHECK(p.simplified == 1);
}

TEST_CASE("a_utc_start_on_the_month_before_is_simplified") {
	// 03:00 UTC on November 1, 2026 is 22:00 on October 31 in Chicago, a day that short months lack.
	const ical::Parsed p = ical::parse(cal({"DTSTART:20261101T030000Z", "RRULE:FREQ=MONTHLY"}), chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].date == days::epoch_day(2026, 10, 31));
	CHECK(p.simplified == 1);
}

TEST_CASE("a_week_start_of_monday_splits_sunday_from_monday") {
	CHECK(ical::parse(cal({"DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU"}), chicago())
	          .simplified == 1);
	CHECK(ical::parse(cal({"DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU;WKST=SU"}), chicago())
	          .simplified == 0);
	// With WKST=MO the first Sunday is in the week of Monday January 5.
	const ical::Parsed p =
	    ical::parse(cal({"DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SU"}), chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].date == days::epoch_day(2026, 1, 11));
	CHECK(model::occurs_on(p.events[0], days::epoch_day(2026, 1, 25)));
	CHECK_FALSE(model::occurs_on(p.events[0], days::epoch_day(2026, 1, 18)));
}

TEST_CASE("until_before_the_start_time_ends_the_day_before") {
	const ical::Parsed p =
	    ical::parse(cal({"DTSTART:20261110T090000", "RRULE:FREQ=DAILY;UNTIL=20261115T080000"}), chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].repeat_end == days::epoch_day(2026, 11, 14));
}

TEST_CASE("count_with_a_start_off_the_rule_counts_the_start") {
	// Wednesday October 21, 2026, with Mondays: the start, then October 26 and November 2.
	const ical::Parsed p =
	    ical::parse(cal({"DTSTART:20261021T090000", "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=3"}), chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].date == days::epoch_day(2026, 10, 26));
	CHECK(p.events[0].repeat_end == days::epoch_day(2026, 11, 2));
	CHECK(p.simplified == 1);
	const ical::Parsed one =
	    ical::parse(cal({"DTSTART:20261021T090000", "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=1"}), chicago());
	REQUIRE(one.events.size() == 1);
	CHECK(one.events[0].repeat == model::repeat::NONE);
	CHECK(one.events[0].date == days::epoch_day(2026, 10, 21));
	CHECK(one.simplified == 0);
}

TEST_CASE("export_leaves_out_an_event_that_never_occurs") {
	model::Event never;
	never.date = days::epoch_day(2026, 10, 21);
	never.repeat = model::repeat::WEEK;
	never.week_days = 1 << 1;
	never.repeat_end = days::epoch_day(2026, 10, 24);
	const std::vector<model::Event> one{never};
	CHECK_FALSE(ical::export_ical(one, std::chrono::sys_seconds{}).contains("BEGIN:VEVENT"));
}

TEST_CASE("export_uid_comes_from_the_sync_uid") {
	model::Event a;
	a.id = 5;
	a.uid = "abc-1";
	a.date = 20000;
	model::Event b;
	b.date = 20000;
	model::Event c;
	c.date = 20001;
	const std::vector<model::Event> list{a, b, c};
	const std::string text = ical::export_ical(list, parse_instant("2026-10-22T12:34:56Z"));
	CHECK(text.contains("UID:abc-1@com.rearman.datebook\r\n"));
	CHECK(text.contains("UID:new-20261022T123456Z-1@com.rearman.datebook\r\n"));
	CHECK(text.contains("UID:new-20261022T123456Z-2@com.rearman.datebook\r\n"));
}

TEST_CASE("export_skips_days_past_the_year_9999") {
	model::Event e;
	e.date = 20000;
	e.repeat = model::repeat::DAY;
	e.repeat_end = 4'000'000'000'000'000;
	e.exceptions.insert(4'000'000'000'000'000);
	const std::vector<model::Event> one{e};
	const std::string text = ical::export_ical(one, std::chrono::sys_seconds{});
	CHECK(text.contains("RRULE:FREQ=DAILY\r\n"));
	CHECK_FALSE(text.contains("EXDATE"));
}

TEST_CASE("a_duration_past_the_date_range_counts_as_none") {
	for (std::string_view d : {"DURATION:P9999999999999999W", "DURATION:P99999999999W"}) {
		const ical::Parsed p = ical::parse(cal({"DTSTART:20261110T090000", d}), chicago());
		REQUIRE(p.events.size() == 1);
		CHECK(p.events[0].end == 9 * 60);
	}
	const ical::Parsed a = ical::parse(
	    cal({"DTSTART:20261110T090000", "BEGIN:VALARM", "TRIGGER:-P9999999999999999W", "END:VALARM"}), chicago());
	REQUIRE(a.events.size() == 1);
	CHECK(a.events[0].alarm_advance == 0);
}

TEST_CASE("a_fold_inside_a_utf8_sequence_joins_the_bytes") {
	const std::string text = "BEGIN:VEVENT\r\nDTSTART:20261110T090000\r\nSUMMARY:M\xC3\r\n \xBCller\r\nEND:VEVENT\r\n";
	const ical::Parsed p = ical::parse(text, chicago());
	REQUIRE(p.events.size() == 1);
	CHECK(p.events[0].title == "M\xC3\xBCller");
}

TEST_CASE("lines_drop_the_bom_and_the_cr_before_each_lf") {
	const std::vector<std::string_view> l = ical::lines("\xEF\xBB\xBF"
	                                                    "A:1\r\n B\nC:\r\r\n");
	REQUIRE(l.size() == 4);
	CHECK(l[0] == "A:1");
	CHECK(l[1] == " B");
	CHECK(l[2] == "C:\r");
	CHECK(l[3].empty());
	CHECK(ical::continues(l[1]));
	CHECK(ical::continues("\tx"));
	CHECK_FALSE(ical::continues(l[0]));
	CHECK_FALSE(ical::continues(""));
}
