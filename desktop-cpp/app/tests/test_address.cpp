// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records note_dialog category address_dialog address
//
// The tests of desktop-rs/app/src/address.rs that need no display: the card lines, the phone slots and the Dial digits,
// and the cut of a list name.
#include "palm/address.hpp"

#include "palm/model.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace address = palm::address;
namespace model = palm::model;

namespace {

std::array<std::string, 4> labels() {
	return {"Birthday", "Custom 2", "Custom 3", "Custom 4"};
}

} // namespace

TEST_CASE("card_lines skip empty fields and join the city line") {
	model::Address a;
	a.company = "Acme";
	a.phones = {"555-0100", "", " ", "", "ann@acme.com"};
	a.address = "1 Main St\nSuite 2";
	a.city = "Springfield";
	a.zip = "12345";
	a.custom = {"May 1", "", "", ""};
	a.note = "Gate code 9";
	const std::vector<std::string> want{
	    "Acme",    "Work:  555-0100",   "E-mail:  ann@acme.com", "1 Main St",
	    "Suite 2", "Springfield 12345", "Birthday: May 1",       "Gate code 9",
	};
	CHECK(address::card_lines(a, labels()) == want);

	model::Address b;
	b.city = "Austin";
	b.state = "TX";
	const std::vector<std::string> want_b{"Austin, TX"};
	CHECK(address::card_lines(b, labels()) == want_b);
}

TEST_CASE("dial skips email and blank phones") {
	model::Address a;
	a.phones = {"1", "", "3", "4", "a@b.c"};
	const std::vector<std::size_t> want{0, 2, 3};
	CHECK(address::detail::phone_slots(a) == want);
	CHECK(address::detail::tel_digits("+1 (555) 010-0199 x2") == "+155501001992");
}

TEST_CASE("fit_name cuts a list name part by part") {
	// One pixel per character.
	const std::function<int(std::string_view)> m = [](std::string_view s) { return static_cast<int>(s.size()); };
	CHECK(address::detail::fit_name("Fitzgerald, Alexandra", 21, m) == "Fitzgerald, Alexandra");
	CHECK(address::detail::fit_name("Fitzgerald, Alexandra", 20, m) == "Fitzge..., Alexandra");
	CHECK(address::detail::fit_name("Fitzgerald, Alexandra", 16, m) == "Fitz..., Alex...");
	CHECK(address::detail::fit_name("Acme Corporation", 10, m) == "Acme Co...");
}
