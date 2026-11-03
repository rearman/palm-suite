// links: days model
#include "palm/days.hpp"
#include "palm/model.hpp"
#include <array>
#include <cstdint>
#include <doctest/doctest.h>
#include <limits>

using namespace palm::days;

TEST_CASE("round_trips_over_wide_ranges") {
	for (std::int64_t d = -800000; d < 800000; d += 37) {
		Ymd y = ymd(d);
		CHECK_MESSAGE(epoch_day(y.year, y.month, y.day) == d, d);
		CHECK(y.day >= 1);
		CHECK(y.day <= month_length(y.year, y.month));
	}
	CHECK(ymd(0) == Ymd{1970, 1, 1});
	CHECK(epoch_day(2024, 2, 29) == 19782);
	CHECK(dow(epoch_day(2026, 10, 22)) == 4);
}

TEST_CASE("extreme_days_wrap_without_undefined_behavior") {
	constexpr std::int64_t max = std::numeric_limits<std::int64_t>::max();
	constexpr std::int64_t min = std::numeric_limits<std::int64_t>::min();
	CHECK(wrapping_add(max, 1) == min);
	CHECK(wrapping_sub(min, 1) == max);
	CHECK(wrapping_mul(max, 2) == -2);

	for (std::int64_t d : {max, max - 3, min, min + 3}) {
		Ymd y = ymd(d);
		CHECK(y.month >= 1);
		CHECK(y.month <= 12);
		CHECK(y.day >= 1);
		CHECK(y.day <= 31);
		CHECK(dow(d) <= 6);
	}
	(void)epoch_day(min, 1, 1);
	(void)epoch_day(max, 12, 31);
}

TEST_CASE("events_at_extreme_dates_do_not_overflow") {
	using palm::model::Event;
	constexpr std::int64_t max = std::numeric_limits<std::int64_t>::max();
	constexpr std::int64_t min = std::numeric_limits<std::int64_t>::min();
	const std::array<std::int32_t, 6> kinds{palm::model::repeat::NONE,       palm::model::repeat::DAY,
	                                        palm::model::repeat::WEEK,       palm::model::repeat::MONTH_DAY,
	                                        palm::model::repeat::MONTH_DATE, palm::model::repeat::YEAR};

	for (std::int64_t start : {max - 3, min}) {
		for (std::int32_t kind : kinds) {
			for (std::int32_t every : {1, 3}) {
				Event e;
				e.date = start;
				e.repeat = kind;
				e.every = every;
				// A week mask without the start day, so first_day runs its search.
				e.week_days = kind == palm::model::repeat::WEEK ? 1 << ((dow(start) + 1) % 7) : 0;
				CHECK(palm::model::first_day(e) >= e.date);
				CHECK(palm::model::last_day(e) >= e.date);
				for (std::int64_t i = 0; i < 4; ++i) {
					(void)palm::model::is_last_week(start + i);
					(void)palm::model::occurs_on(e, start + i);
				}
				(void)palm::model::occurs_on(e, max);
				(void)palm::model::occurs_on(e, min);
			}
		}
	}
}
