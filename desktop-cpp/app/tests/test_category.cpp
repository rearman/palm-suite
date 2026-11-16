// links: assets draw msg mnemonics platform_ui dialogs category
// The test of desktop-rs/app/src/category.rs: a menu label keeps its slashes, ampersands, and at signs.
#include "palm/category.hpp"

#include "palm/store.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <filesystem>
#include <string>
#include <vector>

namespace category = palm::category;
namespace fs = std::filesystem;
namespace store = palm::store;

TEST_CASE("menu_label keeps slashes, ampersands, and at signs") {
	CHECK_EQ(category::menu_label("A/B & C @ D \\ E"), "A\\/B && C @@ D \\\\ E");
	CHECK_EQ(category::menu_label("Work"), "Work");
}

TEST_CASE("merge_category moves the records to the kept category and deletes the merged one") {
	const fs::path dir = fs::temp_directory_path() / ("palm-category-" + store::new_uid());
	fs::create_directories(dir);
	{
		auto s = store::DesktopStore::open(dir / std::string(store::FILE_NAME)).value();
		const auto from = s.add_category("Merged", 0).id;
		const auto into = s.add_category("Kept", 0).id;
		for (const auto cat : {from, from, into, category::UNFILED}) {
			palm::model::Todo t;
			t.title = "item";
			t.category = cat;
			s.save_todo(t);
		}
		category::merge_category(s, 0, from, into);
		CHECK(std::ranges::none_of(s.categories, [&](const palm::model::Category& c) { return c.id == from; }));
		CHECK_EQ(std::ranges::count_if(s.todos, [&](const palm::model::Todo& t) { return t.category == into; }), 3);
		CHECK_EQ(std::ranges::count_if(s.todos, [](const palm::model::Todo& t) { return t.category == 0; }), 1);
		category::merge_category(s, 0, category::UNFILED, into);
		CHECK_EQ(std::ranges::count_if(s.todos, [](const palm::model::Todo& t) { return t.category == 0; }), 1);
	}
	fs::remove_all(dir);
}

TEST_CASE("after_merges follows each merge in order and keeps any other id") {
	const std::vector<category::Merge> merges = {{3, 5}, {5, 7}, {9, category::UNFILED}};
	CHECK_EQ(category::after_merges(3, merges), 7);
	CHECK_EQ(category::after_merges(5, merges), 7);
	CHECK_EQ(category::after_merges(9, merges), category::UNFILED);
	CHECK_EQ(category::after_merges(category::ALL, merges), category::ALL);
	CHECK_EQ(category::after_merges(4, {}), 4);
}
