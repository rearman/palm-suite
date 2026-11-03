// links: model codec
#include "model_form.hpp"

#include "palm/codec.hpp"
#include "palm/model.hpp"

#include <cstdint>
#include <doctest/doctest.h>
#include <functional>
#include <string>
#include <string_view>
#include <unordered_map>

using namespace palm;

TEST_CASE("codec_vectors") {
	const vectors::Value v = vectors::load("codec");
	const std::unordered_map<std::int32_t, std::string> uids = vectors::names_by_id(v.at("categoryUids"));
	const std::function<std::int32_t(std::string_view)> cat_id = [&uids](std::string_view uid) -> std::int32_t {
		for (const auto& [id, name] : uids) {
			if (name == uid) {
				return id;
			}
		}
		return 0;
	};
	for (const vectors::Value& x : vectors::arr(v.at("events"))) {
		const model::Event rec = vectors::ev_from(x.at("record"));
		CHECK(vectors::json_of(codec::event(rec)) == vectors::json_of(vectors::s(x.at("text"))));
		const auto got = codec::to_event(vectors::s(x.at("text")), vectors::s(x.at("uid")));
		REQUIRE(got.has_value());
		CHECK(vectors::ev_to(*got) == x.at("record"));
	}
	for (const vectors::Value& x : vectors::arr(v.at("todos"))) {
		const model::Todo rec = vectors::todo_from(x.at("record"));
		CHECK(vectors::json_of(codec::todo(rec, vectors::s(x.at("categoryUid")))) ==
		      vectors::json_of(vectors::s(x.at("text"))));
		const auto got = codec::to_todo(vectors::s(x.at("text")), vectors::s(x.at("uid")), cat_id);
		REQUIRE(got.has_value());
		CHECK(vectors::todo_to(*got) == x.at("record"));
	}
	for (const vectors::Value& x : vectors::arr(v.at("memos"))) {
		const model::Memo rec = vectors::memo_from(x.at("record"));
		CHECK(vectors::json_of(codec::memo(rec, vectors::s(x.at("categoryUid")))) ==
		      vectors::json_of(vectors::s(x.at("text"))));
		const auto got = codec::to_memo(vectors::s(x.at("text")), vectors::s(x.at("uid")), cat_id);
		REQUIRE(got.has_value());
		CHECK(vectors::memo_to(*got) == x.at("record"));
	}
	for (const vectors::Value& x : vectors::arr(v.at("addresses"))) {
		const model::Address rec = vectors::addr_from(x.at("record"));
		CHECK(vectors::json_of(codec::address(rec, vectors::s(x.at("categoryUid")))) ==
		      vectors::json_of(vectors::s(x.at("text"))));
		const auto got = codec::to_address(vectors::s(x.at("text")), vectors::s(x.at("uid")), cat_id);
		REQUIRE(got.has_value());
		CHECK(vectors::addr_to(*got) == x.at("record"));
	}
	for (const vectors::Value& x : vectors::arr(v.at("categories"))) {
		const model::Category rec = vectors::cat_from(x.at("record"));
		CHECK(vectors::json_of(codec::category(rec)) == vectors::json_of(vectors::s(x.at("text"))));
		const auto got =
		    codec::to_category(vectors::s(x.at("text")), vectors::s(x.at("uid")), vectors::i32(x.at("id")));
		REQUIRE(got.has_value());
		CHECK(vectors::cat_to(*got) == x.at("record"));
	}
	const vectors::Value& sc = v.at("shortcuts");
	const codec::ShortCuts list = vectors::pairs(sc.at("list"));
	CHECK(vectors::json_of(codec::shortcuts(list)) == vectors::json_of(vectors::s(sc.at("text"))));
	const auto sc_back = codec::to_shortcuts(vectors::s(sc.at("text")));
	REQUIRE(sc_back.has_value());
	CHECK(*sc_back == list);
	for (const vectors::Value& x : vectors::arr(v.at("v1"))) {
		const std::string text = vectors::s(x.at("text"));
		const std::string kind_name = vectors::s(x.at("kind"));
		INFO(text);
		if (kind_name == "event") {
			const auto got = codec::to_event(text, "v1");
			REQUIRE(got.has_value());
			CHECK(vectors::ev_to(*got) == x.at("record"));
		} else if (kind_name == "todo") {
			const auto got = codec::to_todo(text, "v1", cat_id);
			REQUIRE(got.has_value());
			CHECK(vectors::todo_to(*got) == x.at("record"));
		} else if (kind_name == "memo") {
			const auto got = codec::to_memo(text, "v1", cat_id);
			REQUIRE(got.has_value());
			CHECK(vectors::memo_to(*got) == x.at("record"));
		} else if (kind_name == "address") {
			const auto got = codec::to_address(text, "v1", cat_id);
			REQUIRE(got.has_value());
			CHECK(vectors::addr_to(*got) == x.at("record"));
		} else if (kind_name == "category") {
			const auto got = codec::to_category(text, "v1", 4);
			REQUIRE(got.has_value());
			CHECK(vectors::cat_to(*got) == x.at("record"));
		} else {
			const auto got = codec::to_shortcuts(text);
			REQUIRE(got.has_value());
			CHECK(*got == vectors::pairs(x.at("list")));
			continue;
		}
		CHECK(codec::kind(text) == kind_name);
	}
	for (const vectors::Value& x : vectors::arr(v.at("kinds"))) {
		const std::string text = vectors::s(x.at("text"));
		INFO(x.dump());
		CHECK(codec::kind(text) == vectors::s(x.at("kind")));
	}
}
