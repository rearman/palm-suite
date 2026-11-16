// links: model
#include "model_form.hpp"

#include "palm/days.hpp"
#include "palm/model.hpp"

#include <climits>
#include <compare>
#include <cstdint>
#include <doctest/doctest.h>
#include <set>
#include <string>
#include <vector>

using namespace palm;

TEST_CASE("recur_vectors") {
	const vectors::Value recur = vectors::load("recur");
	for (const vectors::Value& x : vectors::arr(recur.at("events"))) {
		model::Event e = vectors::ev_from(x.at("event"));
		INFO(e.title);
		CHECK(model::first_day(e) == vectors::i(x.at("firstDay")));
		CHECK(model::last_day(e) == vectors::i(x.at("lastDay")));
		CHECK(model::is_daily(e) == vectors::b(x.at("isDaily")));
		CHECK(model::describe(e) == vectors::s(x.at("describe")));
		CHECK(model::short_name(e) == vectors::s(x.at("shortName")));
		std::vector<std::int64_t> found;
		for (std::int64_t d = vectors::i(x.at("from")); d <= vectors::i(x.at("to")); ++d) {
			if (model::occurs_on(e, d)) {
				found.push_back(d);
			}
		}
		std::vector<std::int64_t> want;
		for (const vectors::Value& d : vectors::arr(x.at("days"))) {
			want.push_back(vectors::i(d));
		}
		CHECK(found == want);
	}
}

TEST_CASE("fmt_vectors") {
	const vectors::Value fmt = vectors::load("fmt");
	for (const vectors::Value& x : vectors::arr(fmt.at("times"))) {
		bool h24 = vectors::b(x.at("h24"));
		auto min = vectors::i32(x.at("min"));
		CHECK(model::time(min, h24) == vectors::s(x.at("time")));
		CHECK(model::time_short(min, h24) == vectors::s(x.at("timeShort")));
	}
	for (const vectors::Value& x : vectors::arr(fmt.at("days"))) {
		std::int64_t d = vectors::i(x.at("day"));
		CHECK(model::day_title(d) == vectors::s(x.at("dayTitle")));
		CHECK(model::short_date(d) == vectors::s(x.at("shortDate")));
		CHECK(model::dow_date(d) == vectors::s(x.at("dowDate")));
	}
	for (const vectors::Value& x : vectors::arr(fmt.at("ordinals"))) {
		CHECK(model::ordinal(vectors::i32(x.at("n"))) == vectors::s(x.at("ordinal")));
	}
}

TEST_CASE("text_rules_follow_rust_chars") {
	// Latin-1 and Latin Extended-A letters fold case as Rust's char methods do.
	CHECK(model::compare_ignore_case("é", "É") == std::strong_ordering::equal);
	CHECK(model::compare_ignore_case("a", "B") == std::strong_ordering::less);
	CHECK(model::compare_ignore_case("ā", "Ā") == std::strong_ordering::equal);
	CHECK(model::compare_ignore_case("İ", "i") == std::strong_ordering::greater);
	// A memo title is its first line, trimmed of Unicode white space (U+00A0 is white space).
	model::Memo m;
	m.text = "  Hello \r\nworld";
	CHECK(m.title() == "Hello");
	CHECK(model::is_blank("　 \t"));
	CHECK_FALSE(model::is_blank(" x"));
}

TEST_CASE("first_week_day_of_a_huge_interval_is_direct") {
	// Monday November 16, 2026, every 2147483647 weeks on Sunday: the Sunday of the second repeat week.
	model::Event e;
	e.date = days::epoch_day(2026, 11, 16);
	e.repeat = model::repeat::WEEK;
	e.every = INT32_MAX;
	e.week_days = 1;
	CHECK(model::first_day(e) == days::epoch_day(2026, 11, 15) + std::int64_t{7} * INT32_MAX);
	// With weeks from Monday, the first Sunday is in the week of the start.
	CHECK(model::first_week_day(days::epoch_day(2026, 1, 5), 1, 2, 1) == days::epoch_day(2026, 1, 11));
	CHECK(model::rotate_week_days(1 << 1, -1) == 1);
	CHECK(model::rotate_week_days(1 << 6, 1) == 1);
	CHECK(model::rotate_week_days(1 << 0, 8) == 1 << 1);
}

TEST_CASE("text_past_u_ffff_sorts_as_java_utf16") {
	// Java compares UTF-16 units: U+1D400 is a surrogate pair (D835 DC00), below U+FF01.
	CHECK(model::compare_utf16("\U0001D400", "！") == std::strong_ordering::less);
	CHECK(model::compare_ignore_case("\U0001D400", "！") == std::strong_ordering::less);
	CHECK(model::compare_utf16("a", "ab") == std::strong_ordering::less);
	CHECK(model::compare_utf16("b", "a") == std::strong_ordering::greater);
	// Deseret capital and small long I differ in Java's char-by-char case folding.
	CHECK(model::compare_ignore_case("\U00010400", "\U00010428") == std::strong_ordering::less);
}

TEST_CASE("a_moved_weekly_series_turns_its_days_and_keeps_its_exceptions") {
	// Mondays and Wednesdays from Monday November 2, 2026, without Wednesday November 4, moved a day later.
	model::Event e;
	e.date = days::epoch_day(2026, 11, 2);
	e.repeat = model::repeat::WEEK;
	e.week_days = (1 << 1) | (1 << 3);
	e.exceptions.insert(days::epoch_day(2026, 11, 4));
	e.repeat_end = days::epoch_day(2026, 11, 30);
	const model::Event m = model::move_series(e, 1);
	CHECK(m.date == days::epoch_day(2026, 11, 3));
	CHECK(m.repeat_end == days::epoch_day(2026, 12, 1));
	CHECK(m.week_days == ((1 << 2) | (1 << 4)));
	CHECK(m.exceptions == std::set<std::int64_t>{days::epoch_day(2026, 11, 5)});
	CHECK(model::occurs_on(m, days::epoch_day(2026, 11, 10)));
	CHECK(model::occurs_on(m, days::epoch_day(2026, 11, 12)));
	CHECK_FALSE(model::occurs_on(m, days::epoch_day(2026, 11, 5)));
	// A monthly series keeps its last-week flag.
	model::Event monthly;
	monthly.date = 20000;
	monthly.repeat = model::repeat::MONTH_DAY;
	monthly.week_days = model::repeat::LAST_WEEK;
	CHECK(model::move_series(monthly, 7).week_days == model::repeat::LAST_WEEK);
	// A series with no end keeps none.
	CHECK(model::move_series(monthly, 7).repeat_end == -1);
}
