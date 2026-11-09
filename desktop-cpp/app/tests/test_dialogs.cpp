// links: assets draw mnemonics platform_ui dialogs
// The test of desktop-rs/app/src/dialogs.rs that needs no display: the wrap of a message.
#include "palm/dialogs.hpp"

#include <doctest/doctest.h>

#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace dialogs = palm::dialogs;

TEST_CASE("message_text_wraps") {
	// Ten pixels per character: "aaa bbb" is 70 px, so it fits a 70 px column and "ccc" goes to a new line.
	const std::function<int(std::string_view)> chars = [](std::string_view s) {
		return static_cast<int>(s.size()) * 10;
	};
	CHECK(dialogs::detail::wrap_lines("aaa bbb ccc\nd", 70, chars) == std::vector<std::string>{"aaa bbb", "ccc", "d"});
}
