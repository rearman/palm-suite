// links: assets draw
// The 4 tests of desktop-rs/app/src/draw.rs: the cut of a text, the arrow, the check mark, and the accelerator.
#include "palm/draw.hpp"

#include "palm/text.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>

namespace draw = palm::draw;

namespace {

// The number of code points of a text, as Rust's chars().count().
int chars(std::string_view s) {
	int n = 0;
	for (std::size_t i = 0; i < s.size(); ++n) {
		i += palm::text::decode_at(s, i).len;
	}
	return n;
}

} // namespace

TEST_CASE("fit_cuts_with_dots") {
	// One pixel per character.
	const std::function<int(std::string_view)> m = [](std::string_view s) { return chars(s); };
	CHECK(draw::fit("Hello", 5, m) == "Hello");
	CHECK(draw::fit("Hello", 4, m) == "H...");
	CHECK(draw::fit("Hello", 2, m) == "...");
}

TEST_CASE("arrow_is_a_4_by_7_triangle_on_its_base") {
	// The base is the left column of a right arrow and the right column of a left arrow; the tip is on row 3.
	for (const bool right : {true, false}) {
		const auto rows = draw::arrow_rows(right);
		int pixels = 0;
		for (std::size_t r = 0; r < rows.size(); ++r) {
			const auto [dx, dy, len] = rows[r];
			pixels += len;
			CHECK(dy == static_cast<int>(r));
			CHECK(dx >= 0);
			CHECK(dx + len <= 4);
		}
		CHECK(pixels == 16);
	}
	const auto right = draw::arrow_rows(true);
	const auto left = draw::arrow_rows(false);
	CHECK(right[3] == std::tuple<int, int, int>{0, 3, 4});
	CHECK(left[3] == std::tuple<int, int, int>{0, 3, 4});
	CHECK(right[0] == std::tuple<int, int, int>{0, 0, 1});
	CHECK(left[0] == std::tuple<int, int, int>{3, 0, 1});
}

TEST_CASE("tick_stays_in_its_7_by_7_box") {
	for (const auto& [x, y, w, h] : draw::tick_rects()) {
		CHECK(x >= 0);
		CHECK(x + w <= 7);
		CHECK(y >= 0);
		CHECK(y + h <= 7);
		CHECK(w == 1);
		CHECK(h == 3);
	}
}

TEST_CASE("accel_split_finds_the_letter") {
	const auto print = draw::accel_split("&Print");
	CHECK(print.first == "Print");
	CHECK(print.second == std::optional<std::size_t>{0});

	const auto save = draw::accel_split("Save &As");
	CHECK(save.first == "Save As");
	CHECK(save.second == std::optional<std::size_t>{5});

	const auto both = draw::accel_split("A && B");
	CHECK(both.first == "A & B");
	CHECK_FALSE(both.second.has_value());

	const auto plain = draw::accel_split("Plain");
	CHECK(plain.first == "Plain");
	CHECK_FALSE(plain.second.has_value());
}
