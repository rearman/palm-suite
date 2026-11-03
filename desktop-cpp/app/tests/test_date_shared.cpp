// links: assets draw msg mnemonics platform_ui dialogs combo text_edit note_dialog date_shared
// The tests of desktop-rs/app/src/date_shared.rs that need no display: the repeat summary, the titles and time labels,
// and the wrap of a title.
#include "palm/date_shared.hpp"

#include "palm/days.hpp"

#include <doctest/doctest.h>

#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace date_shared = palm::date_shared;
namespace days = palm::days;
namespace model = palm::model;

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
