// links: assets draw msg mnemonics platform_ui dialogs combo text_edit note_dialog date_shared
// The tests of desktop-rs/app/src/date_shared.rs that need no display: the repeat summary, the titles and time labels,
// the wrap of a title, the All move of a series, and a new event.
#include "palm/date_shared.hpp"

#include "palm/days.hpp"

#include <doctest/doctest.h>

#include <filesystem>
#include <functional>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace date_shared = palm::date_shared;
namespace days = palm::days;
namespace model = palm::model;
namespace settings = palm::settings;

namespace {

// An event on `date` that repeats with `kind` every `every`.
model::Event ev(std::int64_t date, std::int32_t kind, std::int32_t every) {
	model::Event e;
	e.date = date;
	e.repeat = kind;
	e.every = every;
	return e;
}

} // namespace

TEST_CASE("the_repeat_summary_reads_as_palm_desktop_writes_it") {
	// 2026-10-20 is a Tuesday.
	const std::int64_t tue = days::epoch_day(2026, 10, 20);
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::NONE, 1)).empty());
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::DAY, 1)) == "Every day");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::DAY, 2)) == "Every other day");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::WEEK, 1)) == "Every Tuesday");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::WEEK, 2)) == "Every other Tuesday");
	model::Event mon_tue = ev(tue, model::repeat::WEEK, 3);
	mon_tue.week_days = 0b110;
	CHECK(date_shared::describe_desktop(mon_tue) == "Every 3rd week on Mon, and Tue");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::MONTH_DATE, 1)) == "The 20th of every month");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::MONTH_DATE, 3)) == "The 20th of every 3rd month");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::YEAR, 1)) == "October 20 every year");
	CHECK(date_shared::describe_desktop(ev(tue, model::repeat::MONTH_DAY, 1)) == "The 3rd Tuesday of every month");
	// 2026-10-25 is both the 4th and the last Sunday of its month; the LAST_WEEK flag picks the last.
	model::Event sun = ev(days::epoch_day(2026, 10, 25), model::repeat::MONTH_DAY, 1);
	CHECK(date_shared::describe_desktop(sun) == "The 4th Sunday of every month");
	sun.week_days = model::repeat::LAST_WEEK;
	CHECK(date_shared::describe_desktop(sun) == "The last Sunday of every month");
	model::Event until = ev(tue, model::repeat::DAY, 1);
	until.repeat_end = days::epoch_day(2026, 12, 31);
	CHECK(date_shared::describe_desktop(until) == "Every day until 12/31/26");
}

TEST_CASE("titles_time_labels_and_week_numbers") {
	CHECK(date_shared::date_title(days::epoch_day(2026, 10, 23)) == "Friday, October 23, 2026");
	CHECK(date_shared::time_label(0, false) == "12:00a");
	CHECK(date_shared::time_label(13 * 60 + 5, false) == "1:05p");
	CHECK(date_shared::time_label(13 * 60 + 5, true) == "13:05");
	CHECK(date_shared::week_of_year(days::epoch_day(2026, 10, 18), false) == 43);
	CHECK(date_shared::week_of_year(days::epoch_day(2026, 10, 19), true) == 43);
	CHECK(date_shared::week_of_year(days::epoch_day(2026, 1, 1), false) == 1);
	// 2027 starts on a Friday: its first days are ISO week 0 in weekOfYear.
	CHECK(date_shared::week_of_year(days::epoch_day(2027, 1, 1), true) == 0);
}

TEST_CASE("words_wrap_at_spaces_and_long_words_cut_between_characters") {
	// Six px per character (ASCII only), so a line of 12 px holds two characters.
	const std::function<int(std::string_view)> six = [](std::string_view s) { return static_cast<int>(s.size()) * 6; };
	CHECK(date_shared::wrap_words("aa bb cc", 12, 12, six) == std::vector<std::string>{"aa", "bb", "cc"});
	CHECK(date_shared::wrap_words("abcdefgh", 12, 12, six) == std::vector<std::string>{"ab", "cd", "ef", "gh"});
	// The first line, after the markers, may be wider than the rest.
	CHECK(date_shared::wrap_words("a b cd", 18, 12, six) == std::vector<std::string>{"a b", "cd"});
	CHECK(date_shared::wrap_words("   ", 12, 12, six).empty());
}

TEST_CASE("moving_a_weekly_series_with_all_turns_its_days_and_exceptions") {
	const std::int64_t mon = days::epoch_day(2026, 10, 19);
	REQUIRE(days::dow(mon) == 1);
	model::Event e = ev(mon, model::repeat::WEEK, 1);
	e.week_days = 1 << 1;
	e.exceptions = {mon + 7};
	e.repeat_end = mon + 28;
	// The occurrence of the third Monday is dragged to its Wednesday, and All is answered.
	const date_shared::Occ occ{e, mon + 14};
	model::Event work = e;
	work.date = mon + 16;
	const model::Event moved = date_shared::move_all(occ, work);
	CHECK(moved.date == mon + 2);
	CHECK(moved.week_days == 1 << 3);
	CHECK(moved.exceptions == std::set<std::int64_t>{mon + 9});
	CHECK(moved.repeat_end == mon + 30);
	// Days that the form set itself stay as the form has them.
	work.week_days = (1 << 2) | (1 << 4);
	CHECK(date_shared::move_all(occ, work).week_days == ((1 << 2) | (1 << 4)));
	// So does a repeat end that the form changed along with the date: it is not shifted again.
	work.repeat_end = mon + 35;
	CHECK(date_shared::move_all(occ, work).repeat_end == mon + 35);
}

TEST_CASE("a_new_event_in_the_last_hour_ends_at_11_59_pm") {
	const settings::Settings none =
	    settings::Settings::open(std::filesystem::temp_directory_path() / "palm-test-date-shared-no-settings");
	const std::int64_t day = days::epoch_day(2026, 10, 19);
	const model::Event late = date_shared::new_event("Late", day, 23 * 60, none);
	CHECK(late.start == 23 * 60);
	CHECK(late.end == 23 * 60 + 59);
	CHECK_FALSE(late.untimed);
	const model::Event morning = date_shared::new_event("Morning", day, 8 * 60, none);
	CHECK(morning.end == 9 * 60);
	const model::Event untimed = date_shared::new_event("Any time", day, std::nullopt, none);
	CHECK(untimed.untimed);
	CHECK(untimed.start == 8 * 60);
	CHECK(untimed.end == 9 * 60);
	CHECK(untimed.title == "Any time");
}
