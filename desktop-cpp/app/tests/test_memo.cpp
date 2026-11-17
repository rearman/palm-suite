// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records category memo
// The Memo Pad editor's save: only the fields that the editor changed go to the memo as the store holds it.
#include "palm/memo.hpp"

#include <doctest/doctest.h>

namespace memo = palm::memo;
namespace model = palm::model;

TEST_CASE("an_edit_of_the_text_keeps_a_category_and_private_that_a_sync_changed") {
	model::Memo base;
	base.id = 4;
	base.text = "Groceries";
	base.category = 1;
	// The editor changed the text alone.
	model::Memo edited = base;
	edited.text = "Groceries\nmilk";
	// Meanwhile a sync moved the memo to category 2 and made it private.
	model::Memo stored = base;
	stored.category = 2;
	stored.is_private = true;
	const model::Memo m = memo::merge_edits(base, edited, stored);
	CHECK(m.text == "Groceries\nmilk");
	CHECK(m.category == 2);
	CHECK(m.is_private);
	// A category that the editor changed itself wins over the sync's.
	edited.category = 3;
	CHECK(memo::merge_edits(base, edited, stored).category == 3);
	// A text that the editor left alone keeps the sync's text.
	stored.text = "Groceries\neggs";
	edited.text = base.text;
	CHECK(memo::merge_edits(base, edited, stored).text == "Groceries\neggs");
}
