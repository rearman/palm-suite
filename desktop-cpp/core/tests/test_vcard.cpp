// links: model ical vcard
#include "model_form.hpp"

#include "palm/model.hpp"
#include "palm/vcard.hpp"

#include <cstdint>
#include <doctest/doctest.h>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

using namespace palm;

TEST_CASE("vcard_vectors") {
	const vectors::Value v = vectors::load("vcard");
	const vectors::Value& ex = v.at("export");
	const std::unordered_map<std::int32_t, std::string> names = vectors::names_by_id(ex.at("categoryNames"));
	std::vector<model::Address> list;
	for (const vectors::Value& x : vectors::arr(ex.at("addresses"))) {
		list.push_back(vectors::addr_from(x));
	}
	auto name_of = [&names](std::int32_t id) -> std::optional<std::string> {
		auto it = names.find(id);
		if (it == names.end()) {
			return std::nullopt;
		}
		return it->second;
	};
	CHECK(vcard::export_vcard(list, name_of) == vectors::s(ex.at("text")));
	for (const vectors::Value& x : vectors::arr(v.at("parse"))) {
		INFO(vectors::s(x.at("name")));
		vectors::Value cards = vectors::Value::array();
		for (const vcard::Parsed& c : vcard::parse(vectors::s(x.at("text")))) {
			vectors::Value category = c.category_name ? vectors::Value(*c.category_name) : vectors::Value(nullptr);
			cards.push_back(vectors::Value{{"address", vectors::addr_to(c.address)}, {"category", category}});
		}
		CHECK(cards == x.at("cards"));
	}
}
