// links: assets draw msg mnemonics platform_ui dialogs category
// The test of desktop-rs/app/src/category.rs: a menu label keeps its slashes, ampersands, and at signs.
#include "palm/category.hpp"

#include <doctest/doctest.h>

namespace category = palm::category;

TEST_CASE("menu_label keeps slashes, ampersands, and at signs") {
	CHECK_EQ(category::menu_label("A/B & C @ D \\ E"), "A\\/B && C @@ D \\\\ E");
	CHECK_EQ(category::menu_label("Work"), "Work");
}
