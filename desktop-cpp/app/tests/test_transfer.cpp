// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records note_dialog category address_dialog address transfer
//
// The test of desktop-rs/app/src/transfer.rs that needs no display: the extension of an export name.
#include "palm/transfer.hpp"

#include <doctest/doctest.h>

#include <filesystem>

namespace fs = std::filesystem;
namespace detail = palm::transfer::detail;

TEST_CASE("an export name takes the extension of its format when it has none") {
	CHECK(detail::with_ext(fs::path("a/b"), "ics") == fs::path("a/b.ics"));
	CHECK(detail::with_ext(fs::path("a/b.JSON"), "ics") == fs::path("a/b.JSON"));
	CHECK(detail::with_ext(fs::path("a/b.txt"), "vcf") == fs::path("a/b.txt.vcf"));
	CHECK(detail::with_ext(fs::path(".json"), "ics") == fs::path(".json.ics"));
}
