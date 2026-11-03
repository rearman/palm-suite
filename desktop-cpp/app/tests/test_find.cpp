// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records note_dialog category address_dialog address find
//
// The tests of desktop-rs/app/src/find.rs that need no display: the whole-word rule and the order of the result rows.
#include "palm/find.hpp"

#include "palm/model.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cstdint>
#include <vector>

namespace {

using palm::find::detail::ResultKey;

// A timed or untimed Date Book record on `date` that starts at `start` minutes.
palm::runner::Record event(std::int64_t date, std::int32_t start, bool untimed) {
	palm::model::Event e;
	e.date = date;
	e.start = start;
	e.untimed = untimed;
	return e;
}

} // namespace

TEST_CASE("a whole word needs no word character at its edges") {
	using palm::find::detail::has_whole_word;
	CHECK(has_whole_word("Call Bob today", "bob", false));
	CHECK_FALSE(has_whole_word("Call Bobby today", "bob", false));
	CHECK_FALSE(has_whole_word("Call Bob today", "bob", true));
	CHECK_FALSE(has_whole_word("bob_x snake_bob", "bob", false));
	CHECK(has_whole_word("bob_x, bob.", "bob", false));
	// Java's \b also sits between a word character and a sign that starts or ends the word.
	CHECK(has_whole_word("1+1=2", "+", false));
}

TEST_CASE("results list address then date then todo then memo") {
	std::vector<palm::runner::Record> found{
	    palm::model::Memo{},    event(20390, 10 * 60, false), palm::model::Todo{},   event(20389, 13 * 60, false),
	    palm::model::Address{}, event(20389, 9 * 60, false),  event(20389, 0, true),
	};
	std::stable_sort(found.begin(), found.end(), [](const palm::runner::Record& a, const palm::runner::Record& b) {
		return palm::find::detail::result_order(a) < palm::find::detail::result_order(b);
	});
	std::vector<ResultKey> keys;
	for (const palm::runner::Record& r : found) {
		keys.push_back(palm::find::detail::result_order(r));
	}
	const std::vector<ResultKey> expected{
	    ResultKey{0, 0, 0},           ResultKey{1, 20389, -1},      ResultKey{1, 20389, 9 * 60},
	    ResultKey{1, 20389, 13 * 60}, ResultKey{1, 20390, 10 * 60}, ResultKey{2, 0, 0},
	    ResultKey{3, 0, 0},
	};
	CHECK(keys == expected);
}
