// links: logic model
#include "model_form.hpp"

#include "palm/logic.hpp"
#include "palm/model.hpp"

#include <cctype>
#include <stdexcept>
#include <string>
#include <utility>

namespace {

using palm::logic::Action;
using palm::logic::LocalState;
using vectors::arr;
using vectors::b;
using vectors::s;
using vectors::Value;

// The result of one check beside its input, for the failure message.
std::string case_text(const Value& input, const Value& actual) {
	return "input " + input.dump() + "; actual " + actual.dump();
}

// The local state named by a vector; any other text is a tombstone, as in the Rust test.
LocalState local_of(const std::string& text) {
	if (text == "NONE") {
		return LocalState::None;
	}
	if (text == "CLEAN") {
		return LocalState::Clean;
	}
	if (text == "DIRTY") {
		return LocalState::Dirty;
	}
	return LocalState::Tomb;
}

// The Debug name of an action, as the Rust enum prints it.
std::string debug_name(Action action) {
	switch (action) {
	case Action::Skip:
		return "Skip";
	case Action::Apply:
		return "Apply";
	case Action::Drop:
		return "Drop";
	case Action::CopyThenApply:
		return "CopyThenApply";
	case Action::CopyThenDrop:
		return "CopyThenDrop";
	case Action::Rebase:
		return "Rebase";
	}
	std::unreachable();
}

// The vector name of an action (COPY_THEN_APPLY) as a Debug name (CopyThenApply): each word
// keeps its first character and takes the rest in lower case.
std::string name_of_vector(const std::string& text) {
	std::string out;
	std::size_t start = 0;
	while (true) {
		std::size_t end = text.find('_', start);
		std::string word = text.substr(start, end == std::string::npos ? std::string::npos : end - start);
		if (word.empty()) {
			throw std::runtime_error("empty word in action name: " + text);
		}
		out += word[0];
		for (std::size_t k = 1; k < word.size(); ++k) {
			out += static_cast<char>(std::tolower(static_cast<unsigned char>(word[k])));
		}
		if (end == std::string::npos) {
			break;
		}
		start = end + 1;
	}
	return out;
}

} // namespace

TEST_CASE("logic_vectors") {
	const auto v = vectors::load("logic");
	for (const auto& x : arr(v.at("decide"))) {
		const Action action = palm::logic::decide(local_of(s(x.at("local"))), b(x.at("remoteDeleted")), b(x.at("same")),
		                                          b(x.at("lastWins")));
		const std::string name = debug_name(action);
		const std::string kotlin = name_of_vector(s(x.at("action")));
		CHECK_MESSAGE(name == kotlin, x.dump());
	}
	for (const auto& x : arr(v.at("freeTimes"))) {
		std::vector<std::pair<std::int32_t, std::int32_t>> timed;
		for (const Value& p : arr(x.at("timed"))) {
			timed.emplace_back(vectors::i32(p.at(0)), vectors::i32(p.at(1)));
		}
		const auto got = palm::model::free_times(timed, vectors::i32(x.at("dayStart")), vectors::i32(x.at("dayEnd")),
		                                         b(x.at("ends")));
		Value got_json = Value::array();
		for (const std::int32_t t : got) {
			got_json.push_back(t);
		}
		CHECK_MESSAGE(got_json == x.at("free"), case_text(x, got_json));
	}
	for (const auto& x : arr(v.at("splitFuture"))) {
		const auto [shortened, series] = palm::model::split_future(
		    vectors::ev_from(x.at("orig")), vectors::i(x.at("occDay")), vectors::ev_from(x.at("work")));
		const Value shortened_json = shortened ? vectors::ev_to(*shortened) : Value();
		CHECK_MESSAGE(shortened_json == x.at("shortened"), case_text(x, shortened_json));
		const Value series_json = vectors::ev_to(series);
		CHECK_MESSAGE(series_json == x.at("series"), case_text(x, series_json));
	}
	for (const auto& x : arr(v.at("dupKey"))) {
		const Value key_json = vectors::ev_to(vectors::ev_from(x.at("event")).dup_key());
		CHECK_MESSAGE(key_json == x.at("key"), case_text(x, key_json));
	}
	for (const auto& x : arr(v.at("addressNames"))) {
		const auto a = vectors::addr_from(x.at("address"));
		const Value by_name = a.list_name(false);
		CHECK_MESSAGE(by_name == x.at("byName"), case_text(x, by_name));
		const Value by_company = a.list_name(true);
		CHECK_MESSAGE(by_company == x.at("byCompany"), case_text(x, by_company));
		const auto phone = a.list_phone();
		const Value phone_json = phone ? Value::array({phone->first, std::string(1, phone->second)}) : Value();
		CHECK_MESSAGE(phone_json == x.at("phone"), case_text(x, phone_json));
		const Value empty_json = a.is_empty();
		CHECK_MESSAGE(empty_json == x.at("isEmpty"), case_text(x, empty_json));
	}
	const Value& mo = v.at("memoOrder");
	std::vector<palm::model::Memo> memos;
	for (const Value& x : arr(mo.at("memos"))) {
		memos.push_back(vectors::memo_from(x));
	}
	const auto memo_ids = [](std::span<const palm::model::Memo> list) {
		Value out = Value::array();
		for (const palm::model::Memo& m : list) {
			out.push_back(m.id);
		}
		return out;
	};
	const Value alphabetic = memo_ids(palm::model::memo_order(memos, true));
	CHECK_MESSAGE(alphabetic == mo.at("alphabetic"), case_text(mo, alphabetic));
	const Value manual = memo_ids(palm::model::memo_order(memos, false));
	CHECK_MESSAGE(manual == mo.at("manual"), case_text(mo, manual));
	const Value& mv = mo.at("move");
	auto moved = palm::model::memo_order(memos, false);
	const auto changed = palm::model::move_memo(moved, static_cast<std::size_t>(vectors::i(mv.at("from"))),
	                                            static_cast<std::size_t>(vectors::i(mv.at("to"))));
	const Value order = memo_ids(moved);
	CHECK_MESSAGE(order == mv.at("order"), case_text(mv, order));
	Value changed_json = Value::array();
	for (const std::size_t k : changed) {
		changed_json.push_back(moved[k].id);
	}
	CHECK_MESSAGE(changed_json == mv.at("changed"), case_text(mv, changed_json));
	const Value& ao = v.at("addressOrder");
	std::vector<palm::model::Address> addrs;
	for (const Value& x : arr(ao.at("addresses"))) {
		addrs.push_back(vectors::addr_from(x));
	}
	const auto address_ids = [](std::span<const palm::model::Address> list) {
		Value out = Value::array();
		for (const palm::model::Address& a : list) {
			out.push_back(a.id);
		}
		return out;
	};
	const Value by_name_ids = address_ids(palm::model::address_order(addrs, false));
	CHECK_MESSAGE(by_name_ids == ao.at("byName"), case_text(ao, by_name_ids));
	const Value by_company_ids = address_ids(palm::model::address_order(addrs, true));
	CHECK_MESSAGE(by_company_ids == ao.at("byCompany"), case_text(ao, by_company_ids));
	const Value& sc = v.at("sortCategories");
	std::vector<palm::model::Category> cats;
	for (const Value& x : arr(sc.at("categories"))) {
		cats.push_back(vectors::cat_from(x));
	}
	palm::model::sort_categories(cats);
	Value sorted = Value::array();
	for (const palm::model::Category& c : cats) {
		sorted.push_back(c.id);
	}
	CHECK_MESSAGE(sorted == sc.at("sorted"), case_text(sc, sorted));
}
