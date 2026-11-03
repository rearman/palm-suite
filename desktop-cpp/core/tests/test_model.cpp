// links: model
#include "model_form.hpp"

#include "palm/model.hpp"

#include <cstdint>
#include <doctest/doctest.h>
#include <string>
#include <vector>

using namespace palm;

TEST_CASE("recur_vectors") {
	const vectors::Value recur = vectors::load("recur");
	for (const vectors::Value& x : vectors::arr(recur.at("events"))) {
		model::Event e = vectors::ev_from(x.at("event"));
		INFO(e.title);
		CHECK(model::first_day(e) == vectors::i(x.at("firstDay")));
		CHECK(model::last_day(e) == vectors::i(x.at("lastDay")));
		CHECK(model::is_daily(e) == vectors::b(x.at("isDaily")));
		CHECK(model::describe(e) == vectors::s(x.at("describe")));
		CHECK(model::short_name(e) == vectors::s(x.at("shortName")));
		std::vector<std::int64_t> found;
		for (std::int64_t d = vectors::i(x.at("from")); d <= vectors::i(x.at("to")); ++d) {
			if (model::occurs_on(e, d)) {
				found.push_back(d);
			}
		}
		std::vector<std::int64_t> want;
		for (const vectors::Value& d : vectors::arr(x.at("days"))) {
			want.push_back(vectors::i(d));
		}
		CHECK(found == want);
	}
}

TEST_CASE("fmt_vectors") {
	const vectors::Value fmt = vectors::load("fmt");
	for (const vectors::Value& x : vectors::arr(fmt.at("times"))) {
		bool h24 = vectors::b(x.at("h24"));
		auto min = vectors::i32(x.at("min"));
		CHECK(model::time(min, h24) == vectors::s(x.at("time")));
		CHECK(model::time_short(min, h24) == vectors::s(x.at("timeShort")));
	}
	for (const vectors::Value& x : vectors::arr(fmt.at("days"))) {
		std::int64_t d = vectors::i(x.at("day"));
		CHECK(model::day_title(d) == vectors::s(x.at("dayTitle")));
		CHECK(model::short_date(d) == vectors::s(x.at("shortDate")));
		CHECK(model::dow_date(d) == vectors::s(x.at("dowDate")));
	}
	for (const vectors::Value& x : vectors::arr(fmt.at("ordinals"))) {
		CHECK(model::ordinal(vectors::i32(x.at("n"))) == vectors::s(x.at("ordinal")));
	}
}

TEST_CASE("text_rules_follow_rust_chars") {
	// Latin-1 and Latin Extended-A letters fold case as Rust's char methods do.
	CHECK(model::compare_ignore_case("é", "É") == std::strong_ordering::equal);
	CHECK(model::compare_ignore_case("a", "B") == std::strong_ordering::less);
	CHECK(model::compare_ignore_case("ā", "Ā") == std::strong_ordering::equal);
	CHECK(model::compare_ignore_case("İ", "i") == std::strong_ordering::greater);
	// A memo title is its first line, trimmed of Unicode white space (U+00A0 is white space).
	model::Memo m;
	m.text = "  Hello \r\nworld";
	CHECK(m.title() == "Hello");
	CHECK(model::is_blank("　 \t"));
	CHECK_FALSE(model::is_blank(" x"));
}
