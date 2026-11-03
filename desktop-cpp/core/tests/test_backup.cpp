// links: model backup
#include "model_form.hpp"

#include "palm/backup.hpp"

#include <algorithm>
#include <cstdint>
#include <doctest/doctest.h>
#include <expected>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

using namespace palm;

namespace {

// The JSON of a backup's content, with the same keys as the Rust content_to.
vectors::Value content_to(const backup::Content& c) {
	vectors::Value todos = vectors::Value::array();
	for (const model::Todo& t : c.todos) {
		todos.push_back(vectors::todo_to(t));
	}
	vectors::Value memos = vectors::Value::array();
	for (const model::Memo& m : c.memos) {
		memos.push_back(vectors::memo_to(m));
	}
	vectors::Value addresses = vectors::Value::array();
	for (const model::Address& a : c.addresses) {
		addresses.push_back(vectors::addr_to(a));
	}
	return vectors::Value{
	    {"events", vectors::events_to(c.events)},
	    {"todos", todos},
	    {"memos", memos},
	    {"addresses", addresses},
	    {"todoCategories", c.todo_categories},
	    {"memoCategories", c.memo_categories},
	    {"addressCategories", c.address_categories},
	};
}

} // namespace

TEST_CASE("backup_vectors") {
	const vectors::Value v = vectors::load("backup");
	const vectors::Value& w = v.at("write");
	const auto names = vectors::names_by_id(w.at("categoryNames"));
	const vectors::Value& c = w.at("content");
	backup::Content content;
	for (const vectors::Value& x : vectors::arr(c.at("events"))) {
		content.events.push_back(vectors::ev_from(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("todos"))) {
		content.todos.push_back(vectors::todo_from(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("memos"))) {
		content.memos.push_back(vectors::memo_from(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("addresses"))) {
		content.addresses.push_back(vectors::addr_from(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("todoCategories"))) {
		content.todo_categories.push_back(vectors::s(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("memoCategories"))) {
		content.memo_categories.push_back(vectors::s(x));
	}
	for (const vectors::Value& x : vectors::arr(c.at("addressCategories"))) {
		content.address_categories.push_back(vectors::s(x));
	}
	const std::string text = backup::write(content, [&](std::int32_t id) -> std::string {
		const auto it = names.find(id);
		return it == names.end() ? std::string() : it->second;
	});
	CHECK(vectors::json_of(text) == vectors::json_of(vectors::s(w.at("text"))));

	for (const vectors::Value& x : vectors::arr(v.at("read"))) {
		std::vector<std::string> ids;
		vectors::Value calls = vectors::Value::array();
		auto got = backup::read(vectors::s(x.at("text")), [&](std::string_view n, std::int32_t app) -> std::int32_t {
			calls.push_back(vectors::Value::array({std::string(n), app}));
			const std::string k = std::to_string(app) + "/" + std::string(n);
			const auto it = std::ranges::find(ids, k);
			if (it != ids.end()) {
				return 100 + static_cast<std::int32_t>(it - ids.begin());
			}
			ids.push_back(k);
			return 99 + static_cast<std::int32_t>(ids.size());
		});
		const std::string name = vectors::s(x.at("name"));
		INFO(name);
		if (got) {
			CHECK(content_to(*got) == x.at("content"));
			CHECK(calls == x.at("calls"));
		} else {
			CHECK(x.at("error") == true);
		}
	}
}
