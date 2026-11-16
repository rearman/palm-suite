// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records note_dialog date_picker category todo address_dialog address date_shared event_dialog date
// The tests of the Day view layout that need no display: the columns of overlapping events.
#include "../src/date_internal.hpp"

#include "palm/days.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

namespace date_shared = palm::date_shared;
namespace days = palm::days;
namespace detail = palm::date::detail;
namespace model = palm::model;

namespace {

// A timed occurrence from `start` to `end` minutes.
date_shared::Occ timed(std::int32_t start, std::int32_t end) {
	model::Event e;
	e.date = days::epoch_day(2026, 10, 19);
	e.start = start;
	e.end = end;
	return date_shared::Occ{e, e.date};
}

// The x of each box of `occs` laid out 400 px wide, in the order of `occs`.
std::vector<int> xs(const std::vector<date_shared::Occ>& occs) {
	std::vector<int> out(occs.size(), 0);
	for (const auto& [i, r, header] : detail::layout_day(occs, 400)) {
		out[i] = r.x;
	}
	return out;
}

} // namespace

TEST_CASE("two_zero_length_events_at_one_time_lie_side_by_side") {
	const std::vector<int> x = xs({timed(9 * 60, 9 * 60), timed(9 * 60, 9 * 60)});
	CHECK(x[0] != x[1]);
	// A zero-length event takes the half hour its box covers, so an event from 9:15 lies beside it too.
	const std::vector<int> y = xs({timed(9 * 60, 9 * 60), timed(9 * 60 + 15, 10 * 60)});
	CHECK(y[0] != y[1]);
	// An event from 9:30 starts below the box, in the first column.
	const std::vector<int> z = xs({timed(9 * 60, 9 * 60), timed(9 * 60 + 30, 10 * 60)});
	CHECK(z[0] == z[1]);
}
