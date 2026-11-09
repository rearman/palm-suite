// links: assets draw
// The tests of desktop-rs/app/src/draw.rs: the cut of a text and the accelerator.
#include "palm/draw.hpp"

#include "palm/text.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <functional>
#include <optional>
#include <string>
#include <string_view>

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
