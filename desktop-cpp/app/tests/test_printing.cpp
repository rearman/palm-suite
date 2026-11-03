// links: assets draw mnemonics platform_ui dialogs printing
// The test of desktop-rs/app/src/printing.rs that needs no display or printer: the rows of a wrapped text.
#include "palm/printing.hpp"

#include "palm/text.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace printing = palm::printing;

TEST_CASE("rows_break_at_spaces_and_cut_a_long_word") {
	// One pixel per character.
	const std::function<int(std::string_view)> chars = [](std::string_view s) { return static_cast<int>(s.size()); };
	CHECK(printing::wrap("one two three", 7, chars) == std::vector<std::string>{"one two", "three"});
	CHECK(printing::wrap("a\n\nb", 10, chars) == std::vector<std::string>{"a", "", "b"});
	CHECK(printing::wrap("abcdefghij", 4, chars) == std::vector<std::string>{"abcd", "efgh", "ij"});
	CHECK(printing::wrap("", 4, chars) == std::vector<std::string>{""});
}

TEST_CASE("rows_count_and_cut_characters_not_bytes") {
	// One pixel per code point, as Rust's chars().count(): "é" is two bytes and one character.
	const std::function<int(std::string_view)> code_points = [](std::string_view s) {
		int n = 0;
		for (std::size_t i = 0; i < s.size(); i += palm::text::decode_at(s, i).len) {
			++n;
		}
		return n;
	};
	CHECK(printing::wrap("éé éé", 3, code_points) == std::vector<std::string>{"éé", "éé"});
	CHECK(printing::wrap("ééééé", 2, code_points) == std::vector<std::string>{"éé", "éé", "é"});
}
