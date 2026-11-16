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

TEST_CASE("charset_reads_the_plain_bytes") {
	const std::vector<vcard::Parsed> c =
	    vcard::parse("BEGIN:VCARD\r\nN;CHARSET=ISO-8859-1:M\xFCller;J\xF6rg\r\nEND:VCARD\r\n");
	REQUIRE(c.size() == 1);
	CHECK(c[0].address.last_name == "M\xC3\xBCller");
	CHECK(c[0].address.first_name == "J\xC3\xB6rg");
}

TEST_CASE("a_fold_inside_a_utf8_sequence_joins_the_bytes") {
	const std::vector<vcard::Parsed> c =
	    vcard::parse("BEGIN:VCARD\r\nFN:Ann\r\nNOTE:M\xC3\r\n \xBCller\r\nEND:VCARD\r\n");
	REQUIRE(c.size() == 1);
	CHECK(c[0].address.note == "M\xC3\xBCller");
}

TEST_CASE("a_quoted_printable_line_end_is_lf") {
	const std::vector<vcard::Parsed> c =
	    vcard::parse("BEGIN:VCARD\r\nFN:Ann\r\nNOTE;ENCODING=QUOTED-PRINTABLE:a=0D=0Ab\r\nEND:VCARD\r\n");
	REQUIRE(c.size() == 1);
	CHECK(c[0].address.note == "a\nb");
}
