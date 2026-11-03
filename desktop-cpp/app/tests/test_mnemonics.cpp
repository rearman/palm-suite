// links: mnemonics
// The six tests of desktop-rs/app/src/mnemonics.rs: the accelerator rules and the original labels.
#include "palm/mnemonics.hpp"

#include <doctest/doctest.h>

#include <optional>
#include <string>

namespace mn = palm::mnemonics;

TEST_CASE("strip removes the accelerator") {
	CHECK(mn::strip("Cu&t") == std::string("Cut"));
	CHECK(mn::strip("&Yes") == std::string("Yes"));
}

TEST_CASE("strip keeps a double ampersand") {
	CHECK(mn::strip("A && B") == std::nullopt);
	CHECK(mn::strip("&&x &Yes") == std::string("&&x Yes"));
}

TEST_CASE("strip ignores an ampersand before a space") {
	CHECK(mn::strip("A & B") == std::nullopt);
	CHECK(mn::strip("Ok&") == std::nullopt);
}

TEST_CASE("accelerator is the first letter after an ampersand") {
	CHECK(mn::accelerator("&Yes") == U'Y');
	CHECK(mn::accelerator("&&x &Yes") == U'Y');
	CHECK(mn::accelerator("&&x") == std::nullopt);
	CHECK(mn::accelerator("Plain") == std::nullopt);
}

TEST_CASE("target depends on the state") {
	CHECK(mn::target("Cu&t", false) == "Cut");
	CHECK(mn::target("Cu&t", true) == "Cu&t");
	CHECK(mn::target("Cut", false) == "Cut");
}

TEST_CASE("original follows label changes by code") {
	CHECK(mn::pick_original("Cu&t", "Cu&t") == "Cu&t");
	CHECK(mn::pick_original("Cu&t", "Cut") == "Cu&t");
	CHECK(mn::pick_original("Cu&t", "Paste&x") == "Paste&x");
	CHECK(mn::pick_original(std::nullopt, "Ok&") == "Ok&");
}
